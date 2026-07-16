package cn.ken.shoes.service;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.TaskTypeEnum;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.TimeUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class StockXShippingExtensionService {

    private final StockXClient stockXClient;
    private final StockXShippingExtensionTaskRecorder taskRecorder;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public StockXShippingExtensionService(StockXClient stockXClient,
                                          StockXShippingExtensionTaskRecorder taskRecorder) {
        this(stockXClient, taskRecorder, Clock.systemUTC());
    }

    StockXShippingExtensionService(StockXClient stockXClient) {
        this(stockXClient, noOpRecorder(), Clock.systemUTC());
    }

    StockXShippingExtensionService(StockXClient stockXClient, Clock clock) {
        this(stockXClient, noOpRecorder(), clock);
    }

    StockXShippingExtensionService(StockXClient stockXClient,
                                   StockXShippingExtensionTaskRecorder taskRecorder,
                                   Clock clock) {
        this.stockXClient = stockXClient;
        this.taskRecorder = taskRecorder;
        this.clock = clock;
    }

    public void extendAllEnabledAccounts() {
        extendAllEnabledAccounts("scheduled");
    }

    public void extendAllEnabledAccounts(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("StockX自动延期任务已在运行，跳过本次重复触发");
            return;
        }
        try {
            extendAccounts(StockXConfig.getEnabledAccounts(), trigger);
        } finally {
            running.set(false);
        }
    }

    void extendAccounts(List<StockXAccount> accounts) {
        extendAccounts(accounts, "manual");
    }

    void extendAccounts(List<StockXAccount> accounts, String trigger) {
        for (StockXAccount account : accounts) {
            long startTime = System.currentTimeMillis();
            try {
                Long taskId = createTask(account, trigger);
                executeAccount(account, taskId, startTime);
            } catch (Exception e) {
                log.error("StockX自动延期[{}]创建任务记录失败，继续处理其他账号: {}",
                        account.getName(), e.getMessage(), e);
            }
        }
    }

    /** 手动触发单账号延期；任务记录同步创建，实际执行使用虚拟线程。 */
    public Long startManualAccount(String accountId) {
        StockXAccount account = StockXConfig.getAccount(accountId);
        if (account == null || !account.isEnabled() || !running.compareAndSet(false, true)) {
            return null;
        }
        long startTime = System.currentTimeMillis();
        Long taskId = null;
        try {
            taskId = createTask(account, "manual");
            Long createdTaskId = taskId;
            Thread.startVirtualThread(() -> {
                try {
                    executeAccount(account, createdTaskId, startTime);
                } finally {
                    running.set(false);
                }
            });
            return taskId;
        } catch (Exception e) {
            running.set(false);
            failTask(taskId, e, startTime);
            throw e;
        }
    }

    private void executeAccount(StockXAccount account, Long taskId, long startTime) {
        try {
            ExtensionSummary summary = extendPendingOrdersForAccount(account, taskId);
            completeTask(taskId, summary, startTime);
            log.info("StockX自动延期[{}]完成: scanned={}, alreadyExtended={}, skipped={}, extended={}, failed={}",
                    account.getName(), summary.scanned(), summary.alreadyExtended(), summary.skipped(),
                    summary.extended(), summary.failed());
        } catch (Exception e) {
            failTask(taskId, e, startTime);
            log.error("StockX自动延期[{}]异常，继续处理其他账号: {}", account.getName(), e.getMessage(), e);
        }
    }

    public ExtensionSummary extendPendingOrdersForAccount(StockXAccount account) {
        return extendPendingOrdersForAccount(account, null);
    }

    private ExtensionSummary extendPendingOrdersForAccount(StockXAccount account, Long taskId) {
        int scanned = 0;
        int alreadyExtended = 0;
        int skipped = 0;
        int extended = 0;
        int failed = 0;
        int pageCount = 0;
        String after = null;
        Set<String> processedAskIds = new HashSet<>();

        try {
            while (true) {
                JSONObject asks = stockXClient.queryPendingAsks(after, account);
                if (asks == null) {
                    throw new IllegalStateException("查询待处理订单无响应");
                }
                if (asks.getBooleanValue("_unauthorized")) {
                    throw new IllegalStateException("TOKEN_EXPIRED");
                }
                pageCount++;

                JSONArray edges = asks.getJSONArray("edges");
                if (edges != null) {
                    for (Object edgeValue : edges) {
                        if (!(edgeValue instanceof JSONObject edge)) {
                            skipped++;
                            continue;
                        }
                        JSONObject node = edge.getJSONObject("node");
                        if (node == null) {
                            skipped++;
                            continue;
                        }
                        scanned++;
                        if (node.getBooleanValue("shippingExtensionRequested")) {
                            alreadyExtended++;
                            insertTaskItem(taskId, node, "已延期");
                            continue;
                        }
                        if (isShipDateTodayOrEarlier(node.getString("dateToShipBy"))) {
                            skipped++;
                            insertTaskItem(taskId, node, "跳过-截止日已到");
                            continue;
                        }

                        String askId = node.getString("id");
                        String orderNumber = node.getString("orderNumber");
                        if (StrUtil.isBlank(askId) || StrUtil.isBlank(orderNumber)) {
                            skipped++;
                            insertTaskItem(taskId, node, "跳过-缺少订单标识");
                            continue;
                        }
                        if (!processedAskIds.add(askId)) {
                            skipped++;
                            continue;
                        }

                        try {
                            if (stockXClient.extendShipDate(orderNumber, askId, account)) {
                                extended++;
                                insertTaskItem(taskId, node, "延期成功");
                            } else {
                                failed++;
                                insertTaskItem(taskId, node, "延期失败");
                            }
                        } catch (Exception e) {
                            failed++;
                            String reason = StrUtil.blankToDefault(e.getMessage(), "未知异常");
                            insertTaskItem(taskId, node, "延期失败(" + truncate(reason, 120) + ")");
                            if (e instanceof IllegalStateException && "TOKEN_EXPIRED".equals(e.getMessage())) {
                                throw e;
                            }
                            log.error("StockX自动延期[{}]单笔失败, askId:{}, reason:{}",
                                    account.getName(), askId, e.getMessage());
                        }
                    }
                }
                updateTaskProgress(taskId, pageCount, scanned, alreadyExtended, skipped, extended, failed);

                JSONObject pageInfo = asks.getJSONObject("pageInfo");
                boolean hasNextPage = pageInfo != null && pageInfo.getBooleanValue("hasNextPage");
                if (!hasNextPage) {
                    break;
                }
                String nextCursor = pageInfo.getString("endCursor");
                if (StrUtil.isBlank(nextCursor) || nextCursor.equals(after)) {
                    throw new IllegalStateException("待处理订单分页游标无效");
                }
                after = nextCursor;
            }
        } finally {
            updateTaskProgress(taskId, pageCount, scanned, alreadyExtended, skipped, extended, failed);
        }

        return new ExtensionSummary(scanned, alreadyExtended, skipped, extended, failed);
    }

    private Long createTask(StockXAccount account, String trigger) {
        TaskDO task = new TaskDO();
        task.setPlatform("stockx");
        task.setTaskType(TaskTypeEnum.EXTEND_SHIPPING.getCode());
        task.setAccountName(account.getName());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("trigger", trigger)
                .fluentPut("intervalHours", 12)
                .toJSONString());
        return taskRecorder.start(task);
    }

    private void completeTask(Long taskId, ExtensionSummary summary, long startTime) {
        if (taskId == null) {
            return;
        }
        taskRecorder.complete(taskId, TimeUtil.getCostMin(startTime),
                "扫描" + summary.scanned() + "条，延期成功" + summary.extended()
                        + "条，失败" + summary.failed() + "条");
    }

    private void failTask(Long taskId, Exception error, long startTime) {
        if (taskId == null) {
            return;
        }
        String reason = "TOKEN_EXPIRED".equals(error.getMessage())
                ? "Token已过期，请更新Token"
                : StrUtil.blankToDefault(error.getMessage(), "未知异常");
        taskRecorder.fail(taskId, TimeUtil.getCostMin(startTime), truncate(reason, 200));
    }

    private void updateTaskProgress(Long taskId, int pageCount, int scanned,
                                    int alreadyExtended, int skipped, int extended, int failed) {
        if (taskId == null) {
            return;
        }
        taskRecorder.updateProgress(taskId, pageCount, new JSONObject(true)
                .fluentPut("scanned", scanned)
                .fluentPut("alreadyExtended", alreadyExtended)
                .fluentPut("skipped", skipped)
                .fluentPut("extended", extended)
                .fluentPut("failed", failed)
                .toJSONString());
    }

    private void insertTaskItem(Long taskId, JSONObject node, String result) {
        if (taskId == null) {
            return;
        }
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        // task_item 暂无 askId 专用列；复用 listing_id 保存 ViewerAsks.node.id（延期 mutation 的 chainId）。
        item.setListingId(node.getString("id"));
        item.setOrderNumber(node.getString("orderNumber"));
        item.setOrderStatus("待处理");
        item.setOperateResult(result);
        item.setOperateTime(new Date());

        JSONObject variant = node.getJSONObject("productVariant");
        if (variant != null) {
            item.setProductId(variant.getString("id"));
            JSONObject traits = variant.getJSONObject("traits");
            if (traits != null) {
                item.setSize(traits.getString("size"));
            }
            JSONObject product = variant.getJSONObject("product");
            if (product != null) {
                item.setTitle(product.getString("title"));
                item.setStyleId(product.getString("styleId"));
            }
            JSONObject sizeChart = variant.getJSONObject("sizeChart");
            JSONArray displayOptions = sizeChart != null ? sizeChart.getJSONArray("displayOptions") : null;
            if (displayOptions != null) {
                for (Object value : displayOptions) {
                    Object jsonValue = JSON.toJSON(value);
                    if (!(jsonValue instanceof JSONObject option)) {
                        continue;
                    }
                    String displaySize = option.getString("size");
                    if (StrUtil.containsIgnoreCase(displaySize, "EU")) {
                        item.setEuSize(ShoesUtil.getShoesSizeFrom(displaySize));
                        break;
                    }
                }
            }
        }
        taskRecorder.record(item);
    }

    private String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static StockXShippingExtensionTaskRecorder noOpRecorder() {
        return new StockXShippingExtensionTaskRecorder() {
            @Override public Long start(TaskDO task) { return null; }
            @Override public void record(TaskItemDO item) { }
            @Override public void updateProgress(Long taskId, int pageCount, String attributes) { }
            @Override public void complete(Long taskId, String cost, String summary) { }
            @Override public void fail(Long taskId, String cost, String reason) { }
        };
    }

    /** 与 StockX Pro 前端保持一致：发货截止日为 UTC 今天或更早时不再允许延期。 */
    private boolean isShipDateTodayOrEarlier(String dateToShipBy) {
        if (StrUtil.isBlank(dateToShipBy)) {
            return false;
        }
        try {
            LocalDate shipDate = Instant.parse(dateToShipBy).atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate today = LocalDate.now(clock);
            return !shipDate.isAfter(today);
        } catch (Exception e) {
            log.warn("无法解析StockX发货截止日，交由延期接口判定: {}", dateToShipBy);
            return false;
        }
    }

    public record ExtensionSummary(int scanned, int alreadyExtended, int skipped, int extended, int failed) {
    }
}
