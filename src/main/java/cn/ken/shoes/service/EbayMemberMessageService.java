package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayQuotaExceededException;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.EbayTradingApiClient.MemberQuestion;
import cn.ken.shoes.client.EbayTradingQuotaGuard;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayMemberMessageMapper;
import cn.ken.shoes.model.entity.EbayMemberMessageDO;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * eBay 买家提问（Ask Seller a Question）自动回复。
 *
 * <p>主路径是 AskSellerQuestion 推送：推送里已带完整提问，按规则匹配后直接 AddMemberMessageRTQ 回复，
 * 只消耗 1 次 Trading 调用。推送可能丢失（发版、网络），所以再用低频 GetMemberMessages 对账兜底。
 *
 * <p>额度保护：消息相关调用与改价、订单共用 Trading 日额度，这里按 {@link EbayTradingQuotaGuard}
 * 的当日统计单独限额（{@code ebay.messages.dailyCallLimit}），到达上限后只记录不回复，转人工。
 */
@Slf4j
@Service
public class EbayMemberMessageService {

    private static final int MAX_RECONCILE_PAGES = 5;
    private static final int MAX_LIST = 500;

    private final EbayTradingApiClient tradingClient;
    private final EbayTradingQuotaGuard quotaGuard;
    private final EbayMemberMessageMapper mapper;
    private final EbayProperties properties;
    private final ExecutorService executor;
    private final Clock clock;
    private final AtomicBoolean reconciling = new AtomicBoolean();

    @Autowired
    public EbayMemberMessageService(EbayTradingApiClient tradingClient, EbayTradingQuotaGuard quotaGuard,
                                    EbayMemberMessageMapper mapper, EbayProperties properties) {
        this(tradingClient, quotaGuard, mapper, properties,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "ebay-member-message");
                    thread.setDaemon(true);
                    return thread;
                }), Clock.systemUTC());
    }

    EbayMemberMessageService(EbayTradingApiClient tradingClient, EbayTradingQuotaGuard quotaGuard,
                             EbayMemberMessageMapper mapper, EbayProperties properties,
                             ExecutorService executor, Clock clock) {
        this.tradingClient = tradingClient;
        this.quotaGuard = quotaGuard;
        this.mapper = mapper;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public boolean isEnabled() {
        return settings().isEnabled();
    }

    // ==================== 推送 ====================

    /** 处理已验签的推送：只认 AskSellerQuestion，放到后台线程处理，推送端点立即返回 200。 */
    public void onNotification(EbayPlatformNotificationParser.Notification notification) {
        if (!EbayTradingApiClient.ASK_SELLER_QUESTION.equals(notification.eventName())) {
            log.info("忽略eBay推送事件:{}", notification.eventName());
            return;
        }
        if (!isEnabled()) {
            return;
        }
        List<MemberQuestion> questions = EbayTradingApiClient.parseMemberQuestions(notification.response());
        try {
            executor.execute(() -> questions.forEach(q -> safeHandle(q, EbayMemberMessageDO.SOURCE_PUSH)));
        } catch (RejectedExecutionException e) {
            log.warn("eBay买家提问处理线程已关闭，等待对账补处理, count:{}", questions.size());
        }
    }

    // ==================== 兜底对账 ====================

    /** 拉取最近未回复的买家提问并补处理，返回本次新处理的条数；正在对账时返回 -1。 */
    public int reconcile() {
        if (!isEnabled() || !reconciling.compareAndSet(false, true)) {
            return -1;
        }
        try {
            int days = Math.max(1, Math.min(settings().getReconcileDays(), 30));
            Instant to = clock.instant();
            Instant from = to.minus(Duration.ofDays(days));
            int handled = 0;
            for (int page = 1; page <= MAX_RECONCILE_PAGES; page++) {
                if (!hasBudget(1)) {
                    log.info("eBay消息调用已达今日上限{}次，跳过对账", settings().getDailyCallLimit());
                    break;
                }
                EbayTradingApiClient.MemberQuestionPage result = tradingClient.getUnansweredMessages(from, to, page);
                for (MemberQuestion question : result.questions()) {
                    if (isBuyerQuestion(question) && safeHandle(question, EbayMemberMessageDO.SOURCE_RECONCILE)) {
                        handled++;
                    }
                }
                if (page >= result.totalPages()) {
                    break;
                }
            }
            if (handled > 0) {
                log.info("eBay买家提问对账补处理{}条", handled);
            }
            return handled;
        } catch (EbayQuotaExceededException e) {
            log.warn("eBay买家提问对账跳过: {}", e.getMessage());
            return 0;
        } finally {
            reconciling.set(false);
        }
    }

    // ==================== 处理单条 ====================

    /** 返回是否本次新认领并处理了这条消息。 */
    private boolean safeHandle(MemberQuestion question, String source) {
        try {
            return handle(question, source);
        } catch (RuntimeException e) {
            log.error("处理eBay买家提问失败, messageId:{}", question.messageId(), e);
            return false;
        }
    }

    boolean handle(MemberQuestion question, String source) {
        if (!isBuyerQuestion(question)) {
            return false;
        }
        if (!claim(question, source)) {
            return false;
        }
        EbayMemberMessageDO result = new EbayMemberMessageDO();
        result.setMessageId(question.messageId());
        EbayProperties.ReplyRule rule = match(question);
        if (rule == null) {
            result.setStatus(EbayMemberMessageDO.STATUS_MANUAL);
            result.setError("未命中自动回复规则");
            mapper.updateResult(result);
            log.info("eBay买家提问未命中规则，转人工, messageId:{}, itemId:{}", question.messageId(), question.itemId());
            return true;
        }
        result.setMatchedRule(rule.getName());
        if (!settings().isAutoReply()) {
            result.setStatus(EbayMemberMessageDO.STATUS_MANUAL);
            result.setError("自动回复未开启");
            mapper.updateResult(result);
            return true;
        }
        if (!hasBudget(1)) {
            result.setStatus(EbayMemberMessageDO.STATUS_MANUAL);
            result.setError("消息调用已达今日上限");
            mapper.updateResult(result);
            return true;
        }
        try {
            tradingClient.answerQuestion(question.itemId(), question.messageId(), question.senderId(), rule.getReply());
            result.setStatus(EbayMemberMessageDO.STATUS_REPLIED);
            result.setReply(rule.getReply());
            result.setRepliedTime(Date.from(clock.instant()));
            log.info("eBay买家提问已自动回复, messageId:{}, rule:{}", question.messageId(), rule.getName());
        } catch (EbayApiException | IllegalArgumentException e) {
            // 额度用完或接口失败：标记 failed，下次对账会重新认领重试
            result.setStatus(EbayMemberMessageDO.STATUS_FAILED);
            result.setError(truncate(e.getMessage(), 255));
            log.warn("eBay买家提问自动回复失败, messageId:{}, error:{}", question.messageId(), e.getMessage());
        }
        mapper.updateResult(result);
        return true;
    }

    private boolean claim(MemberQuestion question, String source) {
        EbayMemberMessageDO row = new EbayMemberMessageDO();
        row.setMessageId(question.messageId());
        row.setItemId(question.itemId());
        row.setItemTitle(truncate(question.itemTitle(), 255));
        row.setSenderId(question.senderId());
        row.setQuestionType(question.questionType());
        row.setSubject(truncate(question.subject(), 255));
        row.setBody(truncate(question.body(), 4000));
        row.setStatus(EbayMemberMessageDO.STATUS_PROCESSING);
        row.setSource(source);
        row.setMessageTime(question.createdAt() == null ? null : Date.from(question.createdAt()));
        if (mapper.insertIfAbsent(row) > 0) {
            return true;
        }
        // 只有对账才重试之前失败的消息，推送重发直接忽略
        return EbayMemberMessageDO.SOURCE_RECONCILE.equals(source) && mapper.claimFailed(question.messageId()) > 0;
    }

    /** 只处理买家发起的提问；对账用 MailMessageType=All，会带回其他类型的消息。 */
    static boolean isBuyerQuestion(MemberQuestion question) {
        if (question == null || question.messageId() == null || question.senderId() == null) {
            return false;
        }
        if (question.status() != null && !"Unanswered".equals(question.status())) {
            return false;
        }
        return question.messageType() == null
                || EbayTradingApiClient.ASK_SELLER_QUESTION.equals(question.messageType());
    }

    /** 按配置顺序匹配，关键词不区分大小写、按整词（短语）匹配；提问同时命中多个规则时取第一条。 */
    EbayProperties.ReplyRule match(MemberQuestion question) {
        String text = ((question.subject() == null ? "" : question.subject()) + " "
                + (question.body() == null ? "" : question.body())).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        for (EbayProperties.ReplyRule rule : settings().getRules()) {
            if (rule == null || rule.getReply() == null || rule.getReply().isBlank()
                    || rule.getReply().length() > EbayTradingApiClient.MAX_REPLY_LENGTH) {
                continue;
            }
            for (String keyword : rule.getKeywords()) {
                if (keyword != null && !keyword.isBlank() && containsWord(text, keyword.toLowerCase(Locale.ROOT).trim())) {
                    return rule;
                }
            }
        }
        return null;
    }

    private static boolean containsWord(String text, String keyword) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    // ==================== 额度 ====================

    /** 今日（太平洋时间）消息相关调用次数，含重试。 */
    public long usedCallsToday() {
        long used = 0;
        for (Map.Entry<String, Map<String, Long>> entry : quotaGuard.snapshot().entrySet()) {
            if (EbayTradingApiClient.MESSAGE_CALLS.contains(entry.getKey())) {
                used += entry.getValue().getOrDefault("requests", 0L);
            }
        }
        return used;
    }

    boolean hasBudget(int calls) {
        return quotaGuard.getBlockedUntil() == null
                && usedCallsToday() + calls <= Math.max(0, settings().getDailyCallLimit());
    }

    // ==================== 管理 ====================

    public void subscribe() {
        String url = settings().getDeliveryUrl();
        tradingClient.subscribeAskSellerQuestion(url);
        log.info("已订阅eBay AskSellerQuestion推送, url:{}", url);
    }

    public JSONObject status() {
        JSONObject result = new JSONObject(true);
        EbayProperties.MessageSettings settings = settings();
        result.put("enabled", settings.isEnabled());
        result.put("autoReply", settings.isAutoReply());
        result.put("signingConfigured", settings.getDevId() != null && !settings.getDevId().isBlank());
        result.put("deliveryUrl", settings.getDeliveryUrl());
        result.put("dailyCallLimit", settings.getDailyCallLimit());
        result.put("usedCallsToday", usedCallsToday());
        result.put("rules", settings.getRules().stream().map(EbayProperties.ReplyRule::getName).toList());
        return result;
    }

    public JSONObject notificationPreferences() {
        JSONObject result = new JSONObject(true);
        result.put("application", tradingClient.getNotificationPreferences("Application"));
        result.put("user", tradingClient.getNotificationPreferences("User"));
        return result;
    }

    public List<EbayMemberMessageDO> recent(String status, int limit) {
        return mapper.selectRecent(status, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    private EbayProperties.MessageSettings settings() {
        EbayProperties.MessageSettings settings = properties.getMessages();
        return settings == null ? new EbayProperties.MessageSettings() : settings;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
