package cn.ken.shoes.util;

import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.StockXRateLimitType;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.model.stockx.StockXAccount;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * StockX 限流（429）统一处理器。
 * <p>
 * 所有 StockX GraphQL 写调用都经由 {@link #execute} 包裹：
 * <ol>
 *     <li><b>检测</b>：响应体出现 {@code "httpStatusCode":429} / "Too Many Requests" / "Batch usage limit exceeded" 即判定限流。</li>
 *     <li><b>自适应退避</b>：命中限流后按梯度 {@code RETRY_LADDER_MS}（5s 快重试抓瞬时抖动，其后 30s→60s→120s→300s 封顶，
 *     对齐 StockX 5 分钟窗口配额）在<em>同一调用点</em>重发同一批，因此 service 内部循环的中途进度得以保留。</li>
 *     <li><b>预算/快失败</b>：
 *         <ul>
 *             <li>任务线程（{@link #beginTaskContext} 已注册上下文）：累计等待超 {@code MAX_TOTAL_WAIT_MS}（约15分钟）
 *             仍限流则抛 {@link StockXRateLimitException}（进度已保留）；任一次成功即重置梯度。</li>
 *             <li>同步线程（无上下文，如前端搜索）：仅 1 次秒级快重试后立即抛 {@link StockXRateLimitException} 快速失败，不做分钟级等待。</li>
 *         </ul>
 *     </li>
 * </ol>
 * 分钟级等待会广播账号级冷却：某账号等待期间，该账号的其它任务/调用也会感知（StockX 配额是账号级累积量）。
 *
 * @author Ken-Chy129
 */
@Slf4j
public class StockXRateLimitGuard {

    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern TOKEN_FIELD_PATTERN = Pattern.compile(
            "(?i)(\\\"(?:authorization|access_token|refresh_token|id_token|token)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

    private static final String GLOBAL_KEY = "_global";
    /** 冷却/取消等待时的分片粒度，保证取消与关闭能及时响应 */
    private static final long WAIT_SLICE_MS = 5000;

    /**
     * 限流重试等待梯度(ms)：首次秒级快重试抓瞬时抖动；其后按 StockX 5分钟窗口尺度递增，末位封顶重复。
     * Batch 配额是分钟级窗口(约500件/5min)，秒级重试对它无意义，故第2次起直接进分钟级探测。
     */
    private static final long[] RETRY_LADDER_MS = {5_000L, 30_000L, 60_000L, 120_000L, 300_000L};
    /** ≥此值视为"分钟级窗口等待"：广播账号冷却并更新UI提示；小于则为秒级快重试(仅日志)。 */
    private static final long WINDOW_SCALE_WAIT_MS = 30_000L;
    /** 单次调用累计等待预算，超过仍限流则终止本轮(进度保留)，约15分钟。成功即重置(下次调用从梯度0重来)。 */
    private static final long MAX_TOTAL_WAIT_MS = 15 * 60 * 1000L;

    /** 账号 -> 冷却截止时间戳（ms）。某账号在此之前的调用应等待/快速失败。 */
    private static final ConcurrentHashMap<String, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();
    /** 账号 -> 连续命中 429 次数（成功即清零，仅用于观测） */
    private static final ConcurrentHashMap<String, AtomicInteger> CONSECUTIVE_429 = new ConcurrentHashMap<>();

    private static final ThreadLocal<TaskContext> TASK_CONTEXT = new ThreadLocal<>();

    private static class TaskContext {
        final Supplier<Boolean> cancelled;
        final Consumer<String> onCooldown;
        final Runnable onRecover;
        boolean coolingNotified = false;

        TaskContext(Supplier<Boolean> cancelled, Consumer<String> onCooldown, Runnable onRecover) {
            this.cancelled = cancelled;
            this.onCooldown = onCooldown;
            this.onRecover = onRecover;
        }
    }

    // ==================== 任务上下文（runner 注册/清理） ====================

    /**
     * 任务线程启动时注册上下文：限流时将原地冷却等待而非快速失败。
     *
     * @param account    任务所属账号（提供限流策略）
     * @param cancelled  取消判定（冷却等待中分片检查；返回 true 则中断冷却抛出 {@link TaskCancelledException}）
     * @param onCooldown 进入冷却时的回调（如把任务 failReason 写为"限流冷却中…"，message 由本类生成）
     */
    public static void beginTaskContext(StockXAccount account, Supplier<Boolean> cancelled, Consumer<String> onCooldown) {
        beginTaskContext(account, cancelled, onCooldown, null);
    }

    /**
     * @param onRecover 账号从限流冷却恢复(冷却提示后首次成功)时触发一次，用于清除任务的"冷却中"提示；可为 null
     */
    public static void beginTaskContext(StockXAccount account, Supplier<Boolean> cancelled, Consumer<String> onCooldown, Runnable onRecover) {
        // account 目前仅用于标识任务归属账号（重试梯度/预算已是全局常量，不再从账号读限流策略）
        TASK_CONTEXT.set(new TaskContext(cancelled, onCooldown, onRecover));
    }

    public static void endTaskContext() {
        TASK_CONTEXT.remove();
    }

    // ==================== 核心执行 ====================

    /**
     * 包裹一次 StockX GraphQL 调用，处理限流。返回最终（非限流的）原始响应体；
     * httpCall 返回 null 则透传 null。持续限流时抛 {@link StockXRateLimitException}；冷却中被取消抛 {@link TaskCancelledException}。
     *
     * @param httpCall       实际 HTTP 调用（可被重复调用以重试同一请求）
     * @param accountName    账号名（null 表示无账号的全局调用）
     * @param fallbackPolicy 无任务上下文时使用的策略（通常由账号或全局默认推导）
     */
    /**
     * @param label            调用标识(如 GraphQL operationName)，仅用于限流日志定位到底是哪个操作被限
     * @param onFirstRateLimit 本次调用首次命中限流时触发一次的回调(如立即用 REST 通道探测)，可为 null
     */
    public static String execute(Supplier<String> httpCall, String accountName, String label, Runnable onFirstRateLimit) {
        String key = accountName != null ? accountName : GLOBAL_KEY;
        TaskContext ctx = TASK_CONTEXT.get();

        // 前置：账号已被（可能是其它线程）置于冷却中
        awaitOrThrowIfCoolingDown(key, accountName, ctx);

        int hit = 0;              // 本次调用已命中限流的次数，决定退避梯度
        long totalWaitMs = 0;     // 本次调用累计等待，超预算则终止本轮(进度保留)
        boolean firstHitHandled = false;
        while (true) {
            String raw = httpCall.get();
            if (raw == null) {
                return null;
            }
            if (!isRateLimited(raw)) {
                onSuccess(key);
                if (ctx != null) {
                    // 成功即视为恢复：清除"冷却中"提示；下次调用从梯度0重来，即"成功即重置"
                    if (ctx.coolingNotified && ctx.onRecover != null) {
                        try {
                            ctx.onRecover.run();
                        } catch (Exception e) {
                            log.warn("[{}] onRecover 回调异常: {}", key, e.getMessage());
                        }
                    }
                    ctx.coolingNotified = false;
                }
                return raw;
            }

            // 命中限流
            int consecutive = incrConsecutive(key);
            String signal = matchedSignal(raw);
            if (!firstHitHandled) {
                firstHitHandled = true;
                // 打印原始信号，便于事后定位到底是哪种限流(429节流 / Batch配额 / TooMany)、哪个操作触发
                log.warn("StockX限流命中[{}] op={} signal={} body={}", key, label, signal,
                        sanitizeForLog(raw, 800));
                if (onFirstRateLimit != null) {
                    try {
                        onFirstRateLimit.run();
                    } catch (Exception e) {
                        log.warn("[{}] onFirstRateLimit 回调异常: {}", key, e.getMessage());
                    }
                }
            }

            // 压价写操作由上层状态机决定 Bulk/Single 切换、真实探测和三小时休眠。
            // Guard 在这里必须保留首次真实响应并立即交还，不能先自行等待15分钟。
            if (isPriceUpdateOperation(label)) {
                StockXRateLimitType type = "BatchUsageLimit".equals(signal)
                        ? StockXRateLimitType.BATCH : StockXRateLimitType.GENERAL;
                throw new StockXRateLimitException(accountName, 0L,
                        "StockX压价请求限流(" + signal + ")", type, signal);
            }

            long waitMs = RETRY_LADDER_MS[Math.min(hit, RETRY_LADDER_MS.length - 1)];

            if (ctx == null) {
                // 同步调用(无任务上下文，如前端搜索)：仅一次秒级快重试抓瞬时抖动，仍限则快速失败，不做分钟级等待
                if (hit >= 1) {
                    throw new StockXRateLimitException(accountName, waitMs);
                }
            } else if (totalWaitMs + waitMs > MAX_TOTAL_WAIT_MS) {
                // 任务线程：累计等待超预算仍限流，判定为持续限流(疑似撞当日总配额)，终止本轮
                throw new StockXRateLimitException(accountName, waitMs,
                        "StockX持续限流，冷却重试后仍失败，任务已暂停(进度已保留，可手动继续)");
            }

            if (waitMs >= WINDOW_SCALE_WAIT_MS) {
                // 分钟级窗口等待：广播账号级冷却(让同账号其它调用也退避) + 更新任务"冷却中"提示
                markCooldown(key, waitMs);
                if (ctx != null) {
                    String msg = String.format("StockX限流冷却中，约%d分钟后自动重试 (已累计等待%d秒)",
                            Math.max(1, waitMs / 60000), totalWaitMs / 1000);
                    log.warn("[{}] {}", key, msg);
                    notifyCooldown(ctx, msg);
                }
            } else {
                // 秒级快重试：仅记日志，不更新UI提示
                log.warn("StockX限流[{}]: {}秒后快速重试 (连续{}次)", key, waitMs / 1000, consecutive);
            }

            if (!awaitCooldownOrCancel(waitMs, ctx != null ? ctx.cancelled : null)) {
                throw new TaskCancelledException();
            }
            totalWaitMs += waitMs;
            hit++;
        }
    }

    // ==================== 检测 ====================

    public static boolean isRateLimited(String rawBody) {
        if (rawBody == null) {
            return false;
        }
        return rawBody.contains("\"httpStatusCode\":429")
                || rawBody.contains("\"transportHttpStatus\":429")
                || rawBody.contains("Too Many Requests")
                || rawBody.contains("Batch usage limit exceeded");
    }

    private static boolean isPriceUpdateOperation(String label) {
        return "BulkUpdateListings".equals(label) || "UpdateSellerListing".equals(label);
    }

    /** 返回命中的限流信号类型，仅用于日志区分 429节流 / Batch配额 / TooMany。 */
    public static String matchedSignal(String rawBody) {
        return matchedSignal(0, rawBody);
    }

    /**
     * 结合 HTTP 状态和响应体进行诊断分类。Batch 文本优先于通用 429，避免同一响应被误归类。
     */
    public static String matchedSignal(int httpStatus, String rawBody) {
        if (rawBody == null) {
            rawBody = "";
        }
        if (rawBody.contains("Batch usage limit exceeded")) {
            return "BatchUsageLimit";
        }
        if (rawBody.contains("Too Many Requests")) {
            return "TooManyRequests";
        }
        if (rawBody.contains("\"transportHttpStatus\":429")) {
            return "HTTP429";
        }
        if (httpStatus == 429) {
            return "HTTP429";
        }
        if (rawBody.contains("\"httpStatusCode\":429")) {
            return "GraphQL429";
        }
        if (httpStatus == 403) {
            return "HTTP403";
        }
        if (rawBody.contains("blockScript")) {
            return "BlockScript";
        }
        return "other";
    }

    /** 只保留限流诊断相关响应头，避免日志带出 Set-Cookie 等敏感信息。 */
    public static Map<String, String> rateLimitHeaders(Headers headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        for (String name : headers.names()) {
            String lower = name.toLowerCase();
            if ("retry-after".equals(lower) || lower.contains("ratelimit") || lower.contains("rate-limit")) {
                result.put(name, headers.get(name));
            }
        }
        return result;
    }

    /** 响应体日志脱敏并限制长度。 */
    public static String sanitizeForLog(String rawBody, int maxLength) {
        if (rawBody == null) {
            return "null";
        }
        String sanitized = BEARER_PATTERN.matcher(rawBody).replaceAll("Bearer ***");
        sanitized = TOKEN_FIELD_PATTERN.matcher(sanitized).replaceAll("$1***$2");
        int limit = Math.max(maxLength, 0);
        if (sanitized.length() <= limit) {
            return sanitized;
        }
        return sanitized.substring(0, limit) + "...(truncated,total=" + sanitized.length() + ")";
    }

    // ==================== 冷却状态（账号级共享） ====================

    public static boolean isCoolingDown(String accountName) {
        String key = accountName != null ? accountName : GLOBAL_KEY;
        return getCooldownRemainingMs(key) > 0;
    }

    public static long getCooldownRemainingMs(String accountName) {
        String key = accountName != null ? accountName : GLOBAL_KEY;
        Long until = COOLDOWN_UNTIL.get(key);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    private static void markCooldown(String key, long cooldownMs) {
        COOLDOWN_UNTIL.put(key, System.currentTimeMillis() + cooldownMs);
    }

    private static void onSuccess(String key) {
        CONSECUTIVE_429.computeIfAbsent(key, k -> new AtomicInteger(0)).set(0);
        COOLDOWN_UNTIL.remove(key);
    }

    private static int incrConsecutive(String key) {
        return CONSECUTIVE_429.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    // ==================== 等待 / 退避 ====================

    private static void awaitOrThrowIfCoolingDown(String key, String accountName, TaskContext ctx) {
        long remaining = getCooldownRemainingMs(key);
        if (remaining <= 0) {
            return;
        }
        if (ctx == null) {
            throw new StockXRateLimitException(accountName, remaining);
        }
        String msg = String.format("StockX账号冷却中，约%d分钟后继续", Math.max(1, remaining / 60000));
        log.warn("[{}] {}", key, msg);
        notifyCooldown(ctx, msg);
        if (!awaitCooldownOrCancel(remaining, ctx.cancelled)) {
            throw new TaskCancelledException();
        }
    }

    /**
     * 分片等待冷却时长，期间定期检查取消标志与线程中断。
     *
     * @return true=正常等待结束；false=被取消/中断
     */
    public static boolean awaitCooldownOrCancel(long cooldownMs, Supplier<Boolean> cancelled) {
        long deadline = System.currentTimeMillis() + cooldownMs;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
                return false;
            }
            long slice = Math.min(WAIT_SLICE_MS, deadline - System.currentTimeMillis());
            if (slice <= 0) {
                break;
            }
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static void notifyCooldown(TaskContext ctx, String message) {
        ctx.coolingNotified = true;
        if (ctx.onCooldown != null) {
            try {
                ctx.onCooldown.accept(message);
            } catch (Exception e) {
                log.warn("onCooldown 回调异常: {}", e.getMessage());
            }
        }
    }
}
