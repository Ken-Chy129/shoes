package cn.ken.shoes.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockX 压价通道状态与观测指标。状态按账号共享，计数只用于观测，不参与本地主动限流。
 */
@Slf4j
@Component
public class StockXPriceRateStateManager {

    public enum Mode {
        BULK_ACTIVE,
        SINGLE_FALLBACK,
        BULK_RECOVERING,
        GLOBAL_COOLDOWN
    }

    private static final long BATCH_PROBE_DELAY_MS = 310_000L;
    private static final long GLOBAL_PROBE_DELAY_MS = 3 * 60 * 60 * 1000L;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final Clock clock;
    private final Path stateFile;
    private final ConcurrentHashMap<String, RuntimeState> states = new ConcurrentHashMap<>();

    public StockXPriceRateStateManager() {
        this(Clock.systemUTC(), Path.of("files", "stockx-price-rate-state.json"));
    }

    public StockXPriceRateStateManager(Clock clock, Path stateFile) {
        this.clock = clock;
        this.stateFile = stateFile;
        load();
    }

    public Snapshot snapshot(String accountName) {
        String account = normalize(accountName);
        RuntimeState state = state(account);
        synchronized (state) {
            return state.snapshot(account);
        }
    }

    public List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<>();
        states.forEach((account, state) -> {
            synchronized (state) {
                result.add(state.snapshot(account));
            }
        });
        result.sort(Comparator.comparing(Snapshot::accountName));
        return result;
    }

    public Mode mode(String accountName) {
        return snapshot(accountName).mode();
    }

    public int currentBulkBatchSize(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            return state.mode == Mode.BULK_RECOVERING ? state.recoveryBatchSize : DEFAULT_BATCH_SIZE;
        }
    }

    public boolean shouldProbeBatch(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            return state.mode == Mode.SINGLE_FALLBACK && clock.millis() >= state.nextBatchProbeAt;
        }
    }

    public boolean shouldProbeGlobal(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            return state.mode == Mode.GLOBAL_COOLDOWN && clock.millis() >= state.nextGlobalProbeAt;
        }
    }

    public long globalCooldownRemainingMs(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            return state.mode == Mode.GLOBAL_COOLDOWN
                    ? Math.max(0L, state.nextGlobalProbeAt - clock.millis()) : 0L;
        }
    }

    /** 到达探测时间后为同账号只授予一个执行者，避免现货/寄存任务同时探测。 */
    public boolean tryAcquireGlobalProbe(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            if (state.mode != Mode.GLOBAL_COOLDOWN
                    || clock.millis() < state.nextGlobalProbeAt
                    || state.globalProbeInFlight) {
                return false;
            }
            state.globalProbeInFlight = true;
            return true;
        }
    }

    public void releaseGlobalProbe(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.globalProbeInFlight = false;
        }
    }

    public void onBatchLimit(String accountName, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.SINGLE_FALLBACK;
            state.nextBatchProbeAt = clock.millis() + BATCH_PROBE_DELAY_MS;
            state.nextGlobalProbeAt = 0L;
            state.recoveryBatchSize = 20;
            state.batchRateLimitCount++;
            state.lastSignal = signal;
            state.lastRateLimitAt = clock.millis();
        }
        persist();
    }

    /** Bulk返回通用429：切到Single做真实判别，但不要误记成官方明确的BatchUsageLimit。 */
    public void onBulkGeneralLimit(String accountName, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.SINGLE_FALLBACK;
            state.nextBatchProbeAt = clock.millis() + BATCH_PROBE_DELAY_MS;
            state.nextGlobalProbeAt = 0L;
            state.recoveryBatchSize = 20;
            state.generalRateLimitCount++;
            state.lastSignal = signal;
            state.lastRateLimitAt = clock.millis();
        }
        persist();
    }

    public void recordGeneralRateLimit(String accountName, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.generalRateLimitCount++;
            state.lastSignal = signal;
            state.lastRateLimitAt = clock.millis();
        }
    }

    public void recordBatchRateLimit(String accountName, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.batchRateLimitCount++;
            state.lastSignal = signal;
            state.lastRateLimitAt = clock.millis();
        }
    }

    public void onBatchProbeDeferred(String accountName, long delayMs, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.SINGLE_FALLBACK;
            state.nextBatchProbeAt = clock.millis() + Math.max(1_000L, delayMs);
            state.lastSignal = signal;
        }
        persist();
    }

    public void onBatchProbeSuccess(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.BULK_RECOVERING;
            state.recoveryBatchSize = 20;
            state.nextBatchProbeAt = 0L;
            state.probeSuccessCount++;
        }
        persist();
    }

    public void onRecoveryBatchSuccess(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            if (state.mode != Mode.BULK_RECOVERING) {
                return;
            }
            if (state.recoveryBatchSize < 50) {
                state.recoveryBatchSize = 50;
            } else if (state.recoveryBatchSize < 100) {
                state.recoveryBatchSize = 100;
            } else {
                state.mode = Mode.BULK_ACTIVE;
                state.recoveryBatchSize = DEFAULT_BATCH_SIZE;
            }
        }
        persist();
    }

    public void onGlobalLimit(String accountName, String signal) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.GLOBAL_COOLDOWN;
            state.nextGlobalProbeAt = clock.millis() + GLOBAL_PROBE_DELAY_MS;
            state.nextBatchProbeAt = 0L;
            state.globalProbeInFlight = false;
            state.lastSignal = signal;
            state.lastRateLimitAt = clock.millis();
        }
        persist();
    }

    public void onGlobalProbeSuccess(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.mode = Mode.SINGLE_FALLBACK;
            state.nextGlobalProbeAt = 0L;
            state.nextBatchProbeAt = clock.millis();
            state.globalProbeInFlight = false;
            state.probeSuccessCount++;
        }
        persist();
    }

    public void recordBulkAttempt(String accountName, int itemCount, boolean probe) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.bulkRequestCount++;
            state.bulkItemCount += Math.max(0, itemCount);
            if (probe) {
                state.probeAttemptCount++;
            }
        }
    }

    public void recordSingleAttempt(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.singleRequestCount++;
            state.singleItemCount++;
        }
    }

    public void recordNoResponse(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.noResponseCount++;
        }
    }

    public void recordProbeAttempt(String accountName) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.probeAttemptCount++;
        }
    }

    public void recordConfirmed(String accountName, int count) {
        RuntimeState state = state(normalize(accountName));
        synchronized (state) {
            state.confirmedPriceUpdateCount += Math.max(0, count);
        }
    }

    private RuntimeState state(String accountName) {
        return states.computeIfAbsent(accountName, ignored -> new RuntimeState());
    }

    private static String normalize(String accountName) {
        return accountName == null || accountName.isBlank() ? "_global" : accountName;
    }

    private void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            Map<String, RuntimeState> saved = JSON.parseObject(json,
                    new TypeReference<Map<String, RuntimeState>>() { });
            if (saved != null) {
                states.putAll(saved);
            }
        } catch (Exception e) {
            log.warn("读取StockX压价限流状态失败，将从默认状态继续: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temp, JSON.toJSONString(states), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("持久化StockX压价限流状态失败: {}", e.getMessage());
        }
    }

    public record Snapshot(
            String accountName,
            Mode mode,
            long nextBatchProbeAt,
            long nextGlobalProbeAt,
            int currentBulkBatchSize,
            long bulkRequestCount,
            long bulkItemCount,
            long singleRequestCount,
            long singleItemCount,
            long batchRateLimitCount,
            long generalRateLimitCount,
            long noResponseCount,
            long probeAttemptCount,
            long probeSuccessCount,
            long confirmedPriceUpdateCount,
            String lastSignal,
            long lastRateLimitAt) {
    }

    public static class RuntimeState {
        public Mode mode = Mode.BULK_ACTIVE;
        public long nextBatchProbeAt;
        public long nextGlobalProbeAt;
        public int recoveryBatchSize = DEFAULT_BATCH_SIZE;
        public long bulkRequestCount;
        public long bulkItemCount;
        public long singleRequestCount;
        public long singleItemCount;
        public long batchRateLimitCount;
        public long generalRateLimitCount;
        public long noResponseCount;
        public long probeAttemptCount;
        public long probeSuccessCount;
        public long confirmedPriceUpdateCount;
        public String lastSignal;
        public long lastRateLimitAt;
        private transient boolean globalProbeInFlight;

        public Snapshot snapshot(String accountName) {
            int batchSize = mode == Mode.BULK_RECOVERING ? recoveryBatchSize : DEFAULT_BATCH_SIZE;
            return new Snapshot(accountName, mode, nextBatchProbeAt, nextGlobalProbeAt, batchSize,
                    bulkRequestCount, bulkItemCount, singleRequestCount, singleItemCount,
                    batchRateLimitCount, generalRateLimitCount, noResponseCount,
                    probeAttemptCount, probeSuccessCount, confirmedPriceUpdateCount,
                    lastSignal, lastRateLimitAt);
        }
    }
}
