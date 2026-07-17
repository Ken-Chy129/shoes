package cn.ken.shoes.manager;

import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.exception.StockXNoResponseException;
import cn.ken.shoes.exception.StockXRateLimitException;
import cn.ken.shoes.exception.StockXRateLimitType;
import cn.ken.shoes.exception.TaskCancelledException;
import cn.ken.shoes.model.stockx.StockXAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 压价提交状态机：优先 Bulk，真实触发批量限流后切 Single；两个通道都持续限流时每3小时真实探测。
 */
@Slf4j
@Component
public class StockXPriceUpdateCoordinator {

    private static final long[] SINGLE_RETRY_DELAYS_MS = {5_000L, 30_000L, 60_000L};
    private static final long WAIT_SLICE_MS = 5_000L;

    private final StockXClient client;
    private final StockXPriceRateStateManager stateManager;
    private final Sleeper sleeper;

    @Autowired
    public StockXPriceUpdateCoordinator(StockXClient client, StockXPriceRateStateManager stateManager) {
        this(client, stateManager, Thread::sleep);
    }

    StockXPriceUpdateCoordinator(StockXClient client, StockXPriceRateStateManager stateManager, Sleeper sleeper) {
        this.client = client;
        this.stateManager = stateManager;
        this.sleeper = sleeper;
    }

    public Submission submit(List<Map<String, String>> items,
                             StockXAccount account,
                             Supplier<Boolean> cancelled,
                             BiConsumer<Map<String, String>, String> onItemFailure,
                             Consumer<String> onCooldown,
                             Runnable onRecover) {
        String accountName = account.getName();
        List<Map<String, String>> accepted = new ArrayList<>();
        String reference = "StockX-price-update";
        int index = 0;
        while (index < items.size()) {
            checkCancelled(cancelled);
            StockXPriceRateStateManager.Mode mode = stateManager.mode(accountName);

            if (mode == StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN) {
                awaitGlobalProbe(accountName, cancelled, onCooldown);
                if (stateManager.mode(accountName) != StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN) {
                    continue;
                }
                if (!stateManager.tryAcquireGlobalProbe(accountName)) {
                    await(1_000L, cancelled);
                    continue;
                }
                try {
                    if (submitGlobalProbe(items.get(index), account, cancelled, onCooldown)) {
                        accepted.add(items.get(index++));
                        if (onRecover != null) {
                            onRecover.run();
                        }
                    }
                } finally {
                    stateManager.releaseGlobalProbe(accountName);
                }
                continue;
            }

            if (mode == StockXPriceRateStateManager.Mode.SINGLE_FALLBACK) {
                Map<String, String> item = items.get(index);
                if (stateManager.shouldProbeBatch(accountName) && tryBatchProbe(item, account)) {
                    accepted.add(item);
                    index++;
                    if (onRecover != null) {
                        onRecover.run();
                    }
                    continue;
                }
                SingleOutcome outcome = submitSingleWithRetry(item, account, cancelled, onItemFailure, onCooldown);
                if (outcome == SingleOutcome.ACCEPTED) {
                    accepted.add(item);
                }
                index++;
                continue;
            }

            int batchSize = Math.min(stateManager.currentBulkBatchSize(accountName), items.size() - index);
            List<Map<String, String>> batch = List.copyOf(items.subList(index, index + batchSize));
            try {
                stateManager.recordBulkAttempt(accountName, batch.size(), false);
                reference = client.batchUpdateListingsGraphql(batch, account);
                accepted.addAll(batch);
                index += batch.size();
                if (mode == StockXPriceRateStateManager.Mode.BULK_RECOVERING) {
                    stateManager.onRecoveryBatchSuccess(accountName);
                }
            } catch (StockXNoResponseException noResponse) {
                stateManager.recordNoResponse(accountName);
                log.warn("[{}] Bulk无响应，保持当前配额模式并逐条改用Single，items:{}", accountName, batch.size());
                index += submitIndividually(batch, account, cancelled, onItemFailure, onCooldown, accepted);
            } catch (StockXRateLimitException rateLimit) {
                log.warn("[{}] Bulk真实限流, type:{}, signal:{}, items:{}",
                        accountName, rateLimit.getType(), rateLimit.getSignal(), batch.size());
                if (rateLimit.getType() == StockXRateLimitType.BATCH) {
                    stateManager.onBatchLimit(accountName, rateLimit.getSignal());
                } else {
                    stateManager.onBulkGeneralLimit(accountName, rateLimit.getSignal());
                }
                // 即使 StockX 只给通用429，也用同一真实待处理商品探测Single；Single成功即可确认只是Bulk通道受限。
                index += submitIndividually(batch, account, cancelled, onItemFailure, onCooldown, accepted);
            }
        }
        return new Submission(reference, List.copyOf(accepted));
    }

    private boolean tryBatchProbe(Map<String, String> item, StockXAccount account) {
        String accountName = account.getName();
        try {
            stateManager.recordBulkAttempt(accountName, 1, true);
            client.batchUpdateListingsGraphql(List.of(item), account);
            stateManager.onBatchProbeSuccess(accountName);
            log.info("[{}] Bulk真实探测成功，进入20→50→100渐进恢复", accountName);
            return true;
        } catch (StockXRateLimitException rateLimit) {
            if (rateLimit.getType() == StockXRateLimitType.BATCH) {
                stateManager.onBatchLimit(accountName, rateLimit.getSignal());
            } else {
                stateManager.onBulkGeneralLimit(accountName, rateLimit.getSignal());
            }
            log.warn("[{}] Bulk真实探测仍限流, type:{}, signal:{}",
                    accountName, rateLimit.getType(), rateLimit.getSignal());
            return false;
        } catch (StockXNoResponseException noResponse) {
            stateManager.recordNoResponse(accountName);
            stateManager.onBatchProbeDeferred(accountName, 60_000L, "NoResponse");
            log.warn("[{}] Bulk真实探测无响应，1分钟后再探测，本条改用Single", accountName);
            return false;
        }
    }

    private int submitIndividually(List<Map<String, String>> batch,
                                   StockXAccount account,
                                   Supplier<Boolean> cancelled,
                                   BiConsumer<Map<String, String>, String> onItemFailure,
                                   Consumer<String> onCooldown,
                                   List<Map<String, String>> accepted) {
        int processed = 0;
        for (Map<String, String> item : batch) {
            SingleOutcome outcome = submitSingleWithRetry(item, account, cancelled, onItemFailure, onCooldown);
            if (outcome == SingleOutcome.DEFERRED) {
                // 该条仍未提交，保留在当前位置；外层进入GLOBAL_COOLDOWN后醒来用同一真实商品探测。
                break;
            }
            if (outcome == SingleOutcome.ACCEPTED) {
                accepted.add(item);
            }
            processed++;
        }
        return processed;
    }

    private SingleOutcome submitSingleWithRetry(Map<String, String> item,
                                                 StockXAccount account,
                                                 Supplier<Boolean> cancelled,
                                                 BiConsumer<Map<String, String>, String> onItemFailure,
                                                 Consumer<String> onCooldown) {
        String accountName = account.getName();
        for (int attempt = 0; ; attempt++) {
            checkCancelled(cancelled);
            try {
                stateManager.recordSingleAttempt(accountName);
                client.updateSellerListingGraphql(item, account);
                return SingleOutcome.ACCEPTED;
            } catch (StockXNoResponseException noResponse) {
                // 请求可能已到达StockX，后续按listingId真实校验，避免重复改单。
                stateManager.recordNoResponse(accountName);
                log.warn("[{}] Single无响应，按已提交待校验处理, listingId:{}",
                        accountName, item.get("listingId"));
                return SingleOutcome.ACCEPTED;
            } catch (StockXRateLimitException rateLimit) {
                recordRateLimit(accountName, rateLimit);
                if (attempt >= SINGLE_RETRY_DELAYS_MS.length) {
                    stateManager.onGlobalLimit(accountName, rateLimit.getSignal());
                    String message = "StockX批量和单条压价均持续限流，任务保持运行，3小时后真实探测";
                    log.warn("[{}] {}，lastSignal:{}", accountName, message, rateLimit.getSignal());
                    notify(onCooldown, message);
                    return SingleOutcome.DEFERRED;
                }
                long delay = SINGLE_RETRY_DELAYS_MS[attempt];
                log.warn("[{}] Single真实限流, signal:{}, {}秒后重试同一条, attempt:{}/{}",
                        accountName, rateLimit.getSignal(), delay / 1000, attempt + 1,
                        SINGLE_RETRY_DELAYS_MS.length);
                await(delay, cancelled);
            } catch (TaskCancelledException e) {
                throw e;
            } catch (RuntimeException businessFailure) {
                if ("TOKEN_EXPIRED".equals(businessFailure.getMessage())) {
                    throw businessFailure;
                }
                String reason = trimReason(businessFailure.getMessage(), "单条压价提交失败");
                if (onItemFailure != null) {
                    onItemFailure.accept(item, reason);
                }
                log.error("[{}] Single业务失败, listingId:{}, reason:{}",
                        accountName, item.get("listingId"), reason);
                return SingleOutcome.FAILED;
            }
        }
    }

    private void awaitGlobalProbe(String accountName, Supplier<Boolean> cancelled, Consumer<String> onCooldown) {
        while (stateManager.mode(accountName) == StockXPriceRateStateManager.Mode.GLOBAL_COOLDOWN) {
            long remaining = stateManager.globalCooldownRemainingMs(accountName);
            if (remaining <= 0) {
                return;
            }
            notify(onCooldown, String.format("StockX限流冷却中，任务保持运行，约%d分钟后真实探测",
                    Math.max(1L, remaining / 60_000L)));
            await(remaining, cancelled);
        }
    }

    private boolean submitGlobalProbe(Map<String, String> item,
                                      StockXAccount account,
                                      Supplier<Boolean> cancelled,
                                      Consumer<String> onCooldown) {
        String accountName = account.getName();
        try {
            stateManager.recordProbeAttempt(accountName);
            stateManager.recordSingleAttempt(accountName);
            client.updateSellerListingGraphql(item, account);
            stateManager.onGlobalProbeSuccess(accountName);
            log.info("[{}] 3小时后Single真实探测成功，恢复执行并立即允许Bulk探测", accountName);
            return true;
        } catch (StockXNoResponseException noResponse) {
            stateManager.recordNoResponse(accountName);
            stateManager.onGlobalProbeSuccess(accountName);
            log.warn("[{}] 3小时后Single探测无响应，按已提交待校验处理并恢复执行", accountName);
            return true;
        } catch (StockXRateLimitException rateLimit) {
            recordRateLimit(accountName, rateLimit);
            stateManager.onGlobalLimit(accountName, rateLimit.getSignal());
            notify(onCooldown, "StockX真实探测仍限流，任务保持运行，3小时后再次探测");
            return false;
        }
    }

    private void await(long waitMs, Supplier<Boolean> cancelled) {
        long remaining = waitMs;
        while (remaining > 0) {
            checkCancelled(cancelled);
            long slice = Math.min(WAIT_SLICE_MS, remaining);
            try {
                sleeper.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException();
            }
            remaining -= slice;
        }
        checkCancelled(cancelled);
    }

    private static void checkCancelled(Supplier<Boolean> cancelled) {
        if (Thread.currentThread().isInterrupted()
                || cancelled != null && Boolean.TRUE.equals(cancelled.get())) {
            throw new TaskCancelledException();
        }
    }

    private static String trimReason(String reason, String fallback) {
        String value = reason == null || reason.isBlank() ? fallback : reason;
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private static void notify(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    private void recordRateLimit(String accountName, StockXRateLimitException rateLimit) {
        if (rateLimit.getType() == StockXRateLimitType.BATCH) {
            stateManager.recordBatchRateLimit(accountName, rateLimit.getSignal());
        } else {
            stateManager.recordGeneralRateLimit(accountName, rateLimit.getSignal());
        }
    }

    public record Submission(String reference, List<Map<String, String>> submittedItems) {
    }

    enum SingleOutcome {
        ACCEPTED,
        FAILED,
        DEFERRED
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
