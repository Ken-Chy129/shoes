package cn.ken.shoes.util;

import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.model.stockx.StockXAccount;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * StockX 限流（429）统一处理器。
 * <p>
 * 所有 StockX GraphQL 调用都经由 {@link #execute} 包裹：
 * <ol>
 *     <li><b>检测</b>：响应体出现 {@code "httpStatusCode":429} / "Too Many Requests" / "Batch usage limit exceeded" 即判定限流。</li>
 *     <li><b>短退避</b>：秒级指数退避重试（{@link RateLimitPolicy#maxRetries} 次），应对零星 429，对调用方透明。</li>
 *     <li><b>长冷却</b>：仍限流时——
 *         <ul>
 *             <li>任务线程（{@link #beginTaskContext} 已注册上下文）：原地冷却等待 {@link RateLimitPolicy#cooldownMs}（可被取消打断），
 *             然后在<em>同一调用点</em>重试，因此 service 内部循环的中途进度得以保留；累计冷却超过
 *             {@link RateLimitPolicy#maxCooldownCycles} 次仍限流则抛出 {@link StockXRateLimitException}。</li>
 *             <li>同步线程（无上下文，如前端搜索）：立即抛 {@link StockXRateLimitException} 快速失败，不做长等待。</li>
 *         </ul>
 *     </li>
 * </ol>
 * 冷却状态按账号共享：某账号冷却期间，该账号的其它任务/调用也会感知（StockX 配额是账号级累积量）。
 *
 * @author Ken-Chy129
 */
@Slf4j
public class StockXRateLimitGuard {

    private static final String GLOBAL_KEY = "_global";
    /** 冷却/取消等待时的分片粒度，保证取消与关闭能及时响应 */
    private static final long WAIT_SLICE_MS = 5000;

    /** 账号 -> 冷却截止时间戳（ms）。某账号在此之前的调用应等待/快速失败。 */
    private static final ConcurrentHashMap<String, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();
    /** 账号 -> 连续命中 429 次数（成功即清零，仅用于观测） */
    private static final ConcurrentHashMap<String, AtomicInteger> CONSECUTIVE_429 = new ConcurrentHashMap<>();

    private static final ThreadLocal<TaskContext> TASK_CONTEXT = new ThreadLocal<>();

    // ==================== 限流策略 ====================

    public static class RateLimitPolicy {
        public final int maxRetries;
        public final long backoffBaseMs;
        public final long backoffMaxMs;
        public final long cooldownMs;
        public final int maxCooldownCycles;

        public RateLimitPolicy(int maxRetries, long backoffBaseMs, long backoffMaxMs, long cooldownMs, int maxCooldownCycles) {
            this.maxRetries = maxRetries;
            this.backoffBaseMs = backoffBaseMs;
            this.backoffMaxMs = backoffMaxMs;
            this.cooldownMs = cooldownMs;
            this.maxCooldownCycles = maxCooldownCycles;
        }

        public static RateLimitPolicy of(StockXAccount account) {
            if (account == null) {
                return global();
            }
            return new RateLimitPolicy(
                    account.getRateLimitMaxRetries(),
                    account.getRateLimitBackoffBaseMs(),
                    account.getRateLimitBackoffMaxMs(),
                    account.getRateLimitCooldownMs(),
                    account.getMaxCooldownCycles());
        }

        public static RateLimitPolicy global() {
            return new RateLimitPolicy(
                    StockXConfig.DEFAULT_RATE_LIMIT_MAX_RETRIES,
                    StockXConfig.DEFAULT_RATE_LIMIT_BACKOFF_BASE_MS,
                    StockXConfig.DEFAULT_RATE_LIMIT_BACKOFF_MAX_MS,
                    StockXConfig.DEFAULT_RATE_LIMIT_COOLDOWN_MS,
                    StockXConfig.DEFAULT_MAX_COOLDOWN_CYCLES);
        }
    }

    private static class TaskContext {
        final RateLimitPolicy policy;
        final Supplier<Boolean> cancelled;
        final Consumer<String> onCooldown;
        int cyclesUsed = 0;

        TaskContext(RateLimitPolicy policy, Supplier<Boolean> cancelled, Consumer<String> onCooldown) {
            this.policy = policy;
            this.cancelled = cancelled;
            this.onCooldown = onCooldown;
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
        TASK_CONTEXT.set(new TaskContext(RateLimitPolicy.of(account), cancelled, onCooldown));
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
    public static String execute(Supplier<String> httpCall, String accountName, RateLimitPolicy fallbackPolicy) {
        return execute(httpCall, accountName, fallbackPolicy, null, null);
    }

    /**
     * @param label            调用标识(如 GraphQL operationName)，仅用于限流日志定位到底是哪个操作被限
     * @param onFirstRateLimit 本次调用首次命中限流时触发一次的回调(如立即用 REST 通道探测)，可为 null
     */
    public static String execute(Supplier<String> httpCall, String accountName, RateLimitPolicy fallbackPolicy,
                                 String label, Runnable onFirstRateLimit) {
        String key = accountName != null ? accountName : GLOBAL_KEY;
        TaskContext ctx = TASK_CONTEXT.get();
        RateLimitPolicy policy = ctx != null ? ctx.policy : fallbackPolicy;

        // 前置：账号已被（可能是其它线程）置于冷却中
        awaitOrThrowIfCoolingDown(key, accountName, ctx, policy);

        int attempt = 0;
        boolean firstHitHandled = false;
        while (true) {
            String raw = httpCall.get();
            if (raw == null) {
                return null;
            }
            if (!isRateLimited(raw)) {
                onSuccess(key);
                // 成功即清零冷却轮次：cyclesUsed 只统计"连续未恢复"的冷却，
                // 避免长任务因周期性(可恢复)限流累计到上限被误杀。
                if (ctx != null) {
                    ctx.cyclesUsed = 0;
                }
                return raw;
            }

            // 命中限流
            int consecutive = incrConsecutive(key);
            if (!firstHitHandled) {
                firstHitHandled = true;
                // 打印原始信号，便于事后定位到底是哪种限流(429节流 / Batch配额 / TooMany)、哪个操作触发
                log.warn("StockX限流命中[{}] op={} signal={} raw={}", key, label, matchedSignal(raw),
                        raw.substring(0, Math.min(300, raw.length())));
                if (onFirstRateLimit != null) {
                    try {
                        onFirstRateLimit.run();
                    } catch (Exception e) {
                        log.warn("[{}] onFirstRateLimit 回调异常: {}", key, e.getMessage());
                    }
                }
            }
            if (attempt < policy.maxRetries) {
                attempt++;
                long backoff = backoffMs(policy, attempt);
                log.warn("StockX限流[{}]: 第{}次短退避重试，等待{}ms (连续{}次)", key, attempt, backoff, consecutive);
                sleepCancellable(backoff, ctx);
                continue;
            }

            // 短退避耗尽 -> 长冷却
            markCooldown(key, policy.cooldownMs);
            if (ctx == null) {
                // 同步调用：快速失败
                throw new StockXRateLimitException(accountName, policy.cooldownMs);
            }
            ctx.cyclesUsed++;
            if (ctx.cyclesUsed > policy.maxCooldownCycles) {
                throw new StockXRateLimitException(accountName, policy.cooldownMs,
                        "StockX持续限流，已冷却重试" + policy.maxCooldownCycles + "次仍失败，任务终止(进度已保留)");
            }
            String msg = String.format("StockX限流冷却中，约%d分钟后自动重试 (第%d/%d次连续冷却)",
                    Math.max(1, policy.cooldownMs / 60000), ctx.cyclesUsed, policy.maxCooldownCycles);
            log.warn("[{}] {}", key, msg);
            notifyCooldown(ctx, msg);
            if (!awaitCooldownOrCancel(policy.cooldownMs, ctx.cancelled)) {
                throw new TaskCancelledException();
            }
            attempt = 0; // 冷却结束，原地重试
        }
    }

    // ==================== 检测 ====================

    public static boolean isRateLimited(String rawBody) {
        if (rawBody == null) {
            return false;
        }
        return rawBody.contains("\"httpStatusCode\":429")
                || rawBody.contains("Too Many Requests")
                || rawBody.contains("Batch usage limit exceeded");
    }

    /** 返回命中的限流信号类型，仅用于日志区分 429节流 / Batch配额 / TooMany。 */
    public static String matchedSignal(String rawBody) {
        if (rawBody == null) {
            return "none";
        }
        if (rawBody.contains("\"httpStatusCode\":429")) {
            return "429";
        }
        if (rawBody.contains("Too Many Requests")) {
            return "TooManyRequests";
        }
        if (rawBody.contains("Batch usage limit exceeded")) {
            return "BatchUsageLimit";
        }
        return "other";
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

    private static void awaitOrThrowIfCoolingDown(String key, String accountName, TaskContext ctx, RateLimitPolicy policy) {
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

    private static long backoffMs(RateLimitPolicy policy, int attempt) {
        long base = policy.backoffBaseMs * (1L << Math.min(attempt - 1, 16));
        long capped = Math.min(base, policy.backoffMaxMs);
        long jitter = (long) (capped * 0.2 * Math.random());
        return capped + jitter;
    }

    private static void sleepCancellable(long ms, TaskContext ctx) {
        Supplier<Boolean> cancelled = ctx != null ? ctx.cancelled : null;
        if (!awaitCooldownOrCancel(ms, cancelled)) {
            throw new TaskCancelledException();
        }
    }

    private static void notifyCooldown(TaskContext ctx, String message) {
        if (ctx.onCooldown != null) {
            try {
                ctx.onCooldown.accept(message);
            } catch (Exception e) {
                log.warn("onCooldown 回调异常: {}", e.getMessage());
            }
        }
    }
}
