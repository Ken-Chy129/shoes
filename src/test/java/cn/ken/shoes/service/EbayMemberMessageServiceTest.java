package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.client.EbayTradingApiClient.MemberQuestion;
import cn.ken.shoes.client.EbayTradingQuotaGuard;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayMemberMessageMapper;
import cn.ken.shoes.model.entity.EbayMemberMessageDO;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayMemberMessageServiceTest {

    private EbayTradingApiClient client;
    private EbayTradingQuotaGuard guard;
    private EbayMemberMessageMapper mapper;
    private EbayProperties properties;
    private EbayMemberMessageService service;
    @TempDir
    Path tempDir;
    private Path settingsFile;

    @BeforeEach
    void setUp() {
        client = mock(EbayTradingApiClient.class);
        guard = new EbayTradingQuotaGuard();
        mapper = mock(EbayMemberMessageMapper.class);
        properties = new EbayProperties();
        properties.getMessages().setEnabled(true);
        properties.getMessages().setAutoReply(true);
        EbayProperties.ReplyRule shipping = new EbayProperties.ReplyRule();
        shipping.setName("shipping");
        shipping.setKeywords(List.of("ship", "tracking"));
        shipping.setReply("Ships in 2 days.");
        properties.getMessages().setRules(List.of(shipping));
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        settingsFile = tempDir.resolve("ebay-messages.json");
        service = new EbayMemberMessageService(client, guard, mapper, properties, direct,
                Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneOffset.UTC), settingsFile);
        when(mapper.insertIfAbsent(any())).thenReturn(1);
    }

    @Test
    void repliesWhenRuleMatchesAndRecordsResult() {
        assertThat(service.handle(question("1", "When will it ship?"), "push")).isTrue();

        verify(client).answerQuestion("item1", "1", "buyer1", "Ships in 2 days.");
        EbayMemberMessageDO result = lastResult();
        assertThat(result.getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_REPLIED);
        assertThat(result.getMatchedRule()).isEqualTo("shipping");
    }

    @Test
    void keywordMatchesWholeWordsOnly() {
        // 按整词匹配："relationship" 不能命中关键词 "ship"
        assertThat(service.match(question("1", "Is this a relationship gift?"))).isNull();
        assertThat(service.match(question("2", "TRACKING number please"))).isNotNull();
    }

    @Test
    void ruleWithoutKeywordsRepliesToEverythingAfterEarlierRules() {
        EbayProperties.ReplyRule shipping = properties.getMessages().getRules().get(0);
        EbayProperties.ReplyRule all = new EbayProperties.ReplyRule();
        all.setName("all");
        all.setReply("Thanks, we will reply soon.");
        properties.getMessages().setRules(List.of(shipping, all));

        assertThat(service.match(question("1", "Do you have size 12?")).getName()).isEqualTo("all");
        assertThat(service.match(question("2", "ship today?")).getName()).isEqualTo("shipping");
        EbayMemberMessageService.validate(properties.getMessages());
    }

    @Test
    void buyerAlreadyAutoRepliedGoesToManual() {
        when(mapper.hasRepliedSender("buyer1", "2")).thenReturn(true);

        service.handle(question("2", "ship today?"), "push");

        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
        assertThat(lastResult().getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_MANUAL);
        assertThat(lastResult().getError()).isEqualTo("该买家之前已自动回复过");
    }

    @Test
    void unmatchedQuestionGoesToManualWithoutCallingEbay() {
        service.handle(question("1", "Do you have size 12?"), "push");

        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
        assertThat(lastResult().getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_MANUAL);
    }

    @Test
    void recordsOnlyWhenAutoReplyIsOff() {
        properties.getMessages().setAutoReply(false);

        service.handle(question("1", "ship today?"), "push");

        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
        assertThat(lastResult().getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_MANUAL);
        assertThat(lastResult().getMatchedRule()).isEqualTo("shipping");
    }

    @Test
    void duplicatePushIsIgnored() {
        when(mapper.insertIfAbsent(any())).thenReturn(0);

        assertThat(service.handle(question("1", "ship?"), "push")).isFalse();

        verify(mapper, never()).claimFailed(anyString());
        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reconcileRetriesPreviouslyFailedMessage() {
        when(mapper.insertIfAbsent(any())).thenReturn(0);
        when(mapper.claimFailed("1")).thenReturn(1);

        assertThat(service.handle(question("1", "ship?"), "reconcile")).isTrue();

        verify(client).answerQuestion("item1", "1", "buyer1", "Ships in 2 days.");
    }

    @Test
    void failedReplyIsMarkedForRetry() {
        doThrow(new EbayApiException("eBay AddMemberMessageRTQ失败：boom"))
                .when(client).answerQuestion(anyString(), anyString(), anyString(), anyString());

        service.handle(question("1", "ship?"), "push");

        assertThat(lastResult().getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_FAILED);
    }

    @Test
    void stopsReplyingWhenDailyMessageBudgetIsUsed() {
        properties.getMessages().setDailyCallLimit(2);
        guard.recordRequest("AddMemberMessageRTQ");
        guard.recordRequest("GetMemberMessages");
        guard.recordRequest("ReviseInventoryStatus"); // 其他接口不计入消息额度

        service.handle(question("1", "ship?"), "push");

        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
        assertThat(lastResult().getStatus()).isEqualTo(EbayMemberMessageDO.STATUS_MANUAL);
        assertThat(service.usedCallsToday()).isEqualTo(2);
    }

    @Test
    void stopsReplyingWhileTradingQuotaIsTripped() {
        guard.tripped("GetOrders");

        service.handle(question("1", "ship?"), "push");

        verify(client, never()).answerQuestion(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void skipsAnsweredAndNonQuestionMessages() {
        MemberQuestion answered = new MemberQuestion("1", "AskSellerQuestion", null, "item1", null,
                "buyer1", null, "ship?", "Answered", null);
        MemberQuestion other = new MemberQuestion("2", "ResponseToASQQuestion", null, "item1", null,
                "buyer1", null, "ship?", "Unanswered", null);

        assertThat(service.handle(answered, "reconcile")).isFalse();
        assertThat(service.handle(other, "reconcile")).isFalse();
        verify(mapper, never()).insertIfAbsent(any());
    }

    @Test
    void reconcileWalksPagesAndHandlesNewQuestions() {
        when(client.getUnansweredMessages(any(), any(), anyInt()))
                .thenReturn(new EbayTradingApiClient.MemberQuestionPage(List.of(question("1", "ship?")), 2))
                .thenReturn(new EbayTradingApiClient.MemberQuestionPage(List.of(question("2", "size?")), 2));

        assertThat(service.reconcile()).isEqualTo(2);

        verify(client).getUnansweredMessages(Instant.parse("2026-11-15T01:00:00Z"),
                Instant.parse("2026-11-18T01:00:00Z"), 1);
        verify(client).getUnansweredMessages(any(), any(), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void disabledServiceDoesNothing() {
        properties.getMessages().setEnabled(false);

        assertThat(service.reconcile()).isEqualTo(-1);
        verify(client, never()).getUnansweredMessages(any(), any(), anyInt());
    }

    @Test
    void reconcileIfDueWaitsForConfiguredInterval() {
        when(client.getUnansweredMessages(any(), any(), anyInt()))
                .thenReturn(new EbayTradingApiClient.MemberQuestionPage(List.of(), 1));

        service.reconcileIfDue();
        service.reconcileIfDue();

        verify(client, org.mockito.Mockito.times(1)).getUnansweredMessages(any(), any(), anyInt());
    }

    @Test
    void savedSettingsKeepSecretsWhenBlankAndSurviveRestart() throws Exception {
        properties.getMessages().setDevId("secret-dev-id");
        properties.getMessages().setDeliveryUrl("https://example.com/api/ebay/notifications/trading");
        EbayProperties.MessageSettings form = new EbayProperties.MessageSettings();
        form.setEnabled(true);
        form.setReconcileIntervalMinutes(60);
        EbayProperties.ReplyRule rule = new EbayProperties.ReplyRule();
        rule.setName(" stock ");
        rule.setKeywords(List.of(" in stock ", " "));
        rule.setReply("Yes, in stock.");
        form.setRules(List.of(rule));

        service.saveSettings(form);

        EbayProperties.MessageSettings now = properties.getMessages();
        assertThat(now.getDevId()).isEqualTo("secret-dev-id");
        assertThat(now.getDeliveryUrl()).startsWith("https://example.com");
        assertThat(now.getRules().get(0).getKeywords()).containsExactly("in stock");
        assertThat(now.getRules().get(0).getName()).isEqualTo("stock");
        assertThat(service.settingsView().toJSONString()).doesNotContain("secret-dev-id")
                .contains("\"devIdConfigured\":true");

        // 模拟重启：yml 默认值 + 文件覆盖
        properties.setMessages(new EbayProperties.MessageSettings());
        service.loadSettings();
        assertThat(properties.getMessages().getReconcileIntervalMinutes()).isEqualTo(60);
        assertThat(properties.getMessages().getDevId()).isEqualTo("secret-dev-id");
        assertThat(properties.getMessages().getRules()).hasSize(1);
    }

    @Test
    void invalidSettingsAreRejectedAndNotWritten() {
        EbayProperties.MessageSettings form = new EbayProperties.MessageSettings();
        form.setReconcileIntervalMinutes(5);
        assertThatThrownBy(() -> service.saveSettings(form)).hasMessageContaining("对账间隔");

        EbayProperties.MessageSettings badRule = new EbayProperties.MessageSettings();
        EbayProperties.ReplyRule rule = new EbayProperties.ReplyRule();
        rule.setName("long");
        rule.setKeywords(List.of("x"));
        rule.setReply("a".repeat(EbayTradingApiClient.MAX_REPLY_LENGTH + 1));
        badRule.setRules(List.of(rule));
        assertThatThrownBy(() -> service.saveSettings(badRule)).hasMessageContaining("第1条规则");

        assertThat(Files.exists(settingsFile)).isFalse();
        assertThat(properties.getMessages().isAutoReply()).isTrue();
    }

    private EbayMemberMessageDO lastResult() {
        ArgumentCaptor<EbayMemberMessageDO> captor = ArgumentCaptor.forClass(EbayMemberMessageDO.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateResult(captor.capture());
        return captor.getValue();
    }

    private static MemberQuestion question(String id, String body) {
        return new MemberQuestion(id, "AskSellerQuestion", "General", "item1", "Air Jordan 1",
                "buyer1", "Question", body, "Unanswered", Instant.parse("2026-11-18T00:59:00Z"));
    }
}
