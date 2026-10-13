package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * eBay 获取订单任务：用 Trading API 拉取最近 N 天（最多 90 天）的全部卖家订单，
 * 生成与卖家中心「下载报告」相同列的数据，并追加 货号/US码/EU码/订单日期/发货截止日期。
 *
 * <p>完整报告行保存为任务快照文件，导出时原样输出；任务明细只保存展示用的关键字段。
 */
@Slf4j
@Service
public class EbayOrderFetchService {

    public static final String TASK_TYPE = "ebay_fetch_orders";
    public static final int DEFAULT_DAYS = 90;
    private static final String REPORT_FILE = "ebay-orders.json";
    private static final int MAX_PAGES = 200;
    private static final int LOOKUP_BATCH = 500;

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final EbayListingMapper ebayListingMapper;
    private final EbayTradingApiClient tradingClient;
    private final EbayProperties properties;
    private final Executor executor;
    private final Clock clock;
    private final Path reportRoot;
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Autowired
    public EbayOrderFetchService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                                 EbayListingMapper ebayListingMapper, EbayTradingApiClient tradingClient,
                                 EbayProperties properties) {
        this(taskMapper, taskItemMapper, ebayListingMapper, tradingClient, properties,
                command -> Thread.ofVirtual().name("Ebay-Fetch-Orders").start(command),
                Clock.systemUTC(), Paths.get("files/task-input"));
    }

    EbayOrderFetchService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                          EbayListingMapper ebayListingMapper, EbayTradingApiClient tradingClient,
                          EbayProperties properties, Executor executor, Clock clock, Path reportRoot) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.ebayListingMapper = ebayListingMapper;
        this.tradingClient = tradingClient;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
        this.reportRoot = reportRoot.toAbsolutePath().normalize();
    }

    /** @return 任务ID；已有获取订单任务在运行时返回 null */
    public synchronized Long start(Integer days) {
        int lookbackDays = days == null || days <= 0 ? DEFAULT_DAYS : days;
        if (lookbackDays > EbayTradingApiClient.MAX_ORDER_DAYS) {
            throw new IllegalArgumentException("eBay最多只能获取最近" + EbayTradingApiClient.MAX_ORDER_DAYS + "天的订单");
        }
        if (!running.isEmpty() || taskMapper.selectRunningTask("ebay", TASK_TYPE,
                TaskDO.TaskStatusEnum.RUNNING.getCode()) != null) {
            return null;
        }
        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true).fluentPut("days", lookbackDays).toJSONString());
        taskMapper.insert(task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(task.getId(), cancelled);
        try {
            executor.execute(() -> run(task.getId(), lookbackDays, cancelled));
            return task.getId();
        } catch (RuntimeException e) {
            running.remove(task.getId());
            taskMapper.updateTaskFailed(task.getId(), "获取订单任务启动失败");
            throw e;
        }
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    void run(Long taskId, int days, AtomicBoolean cancelled) {
        try {
            Instant to = clock.instant();
            // 留 1 分钟余量，避免 CreateTimeFrom 恰好越过 eBay 的 90 天上限被拒绝。
            Instant from = to.minus(Duration.ofDays(days));
            if (days >= EbayTradingApiClient.MAX_ORDER_DAYS) {
                from = from.plus(Duration.ofMinutes(1));
            }
            List<Element> orders = new ArrayList<>();
            int page = 1;
            for (; page <= MAX_PAGES; page++) {
                if (cancelled.get()) {
                    taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                    return;
                }
                EbayTradingApiClient.OrdersPage result = tradingClient.getOrders(from, to, page);
                orders.addAll(result.orders());
                taskMapper.updateTaskRound(taskId, page);
                if (!result.hasMore() || result.orders().isEmpty()
                        || (result.totalPages() > 0 && page >= result.totalPages())) {
                    break;
                }
            }
            Set<String> feedbackLeft = optional("信用评价", tradingClient::getFeedbackLeftLineItemIds, Set.of());
            Map<String, String> shippingNames = optional("运送服务名称",
                    tradingClient::getShippingServiceDescriptions, Map.of());
            EbayOrderReportBuilder builder = new EbayOrderReportBuilder(feedbackLeft, shippingNames,
                    mappingLookup(orders));
            List<EbayOrderReportBuilder.Line> lines = new ArrayList<>();
            for (Element order : orders) {
                lines.addAll(builder.build(order));
            }
            lines.sort(Comparator.comparing(EbayOrderReportBuilder.Line::salesRecordNumber).reversed());
            if (cancelled.get()) {
                taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                return;
            }
            saveReport(taskId, lines);
            Map<String, Integer> statusCounts = new LinkedHashMap<>();
            for (EbayOrderReportBuilder.Line line : lines) {
                taskItemMapper.insert(toItem(taskId, line));
                statusCounts.merge(line.status(), 1, Integer::sum);
            }
            JSONObject attributes = new JSONObject(true)
                    .fluentPut("orders", orders.size())
                    .fluentPut("lines", lines.size())
                    .fluentPut("days", days);
            statusCounts.forEach(attributes::put);
            taskMapper.updateTaskAttributes(taskId, attributes.toJSONString());
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            log.info("eBay获取订单完成, taskId:{}, orders:{}, lines:{}", taskId, orders.size(), lines.size());
        } catch (Exception e) {
            log.error("eBay获取订单任务失败, taskId:{}", taskId, e);
            taskMapper.updateTaskFailed(taskId, "获取订单失败：" + safeError(e));
        } finally {
            running.remove(taskId);
        }
    }

    /** 读取导出用的完整报告行；任务没有快照（例如被清理）时返回空。 */
    public Optional<List<LinkedHashMap<String, String>>> loadReport(Long taskId) {
        Path path = reportPath(taskId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            JSONArray array = JSON.parseArray(Files.readString(path));
            List<LinkedHashMap<String, String>> rows = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject object = array.getJSONObject(i);
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                for (String column : EbayOrderReportBuilder.allColumns()) {
                    String value = object.getString(column);
                    row.put(column, value == null ? "" : value);
                }
                rows.add(row);
            }
            return Optional.of(rows);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取eBay订单报告失败: " + taskId, e);
        }
    }

    private void saveReport(Long taskId, List<EbayOrderReportBuilder.Line> lines) throws IOException {
        JSONArray array = new JSONArray();
        for (EbayOrderReportBuilder.Line line : lines) {
            JSONObject object = new JSONObject(true);
            object.putAll(line.columns());
            array.add(object);
        }
        Path path = reportPath(taskId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, array.toJSONString());
    }

    private Path reportPath(Long taskId) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("非法任务ID");
        }
        return reportRoot.resolve(String.valueOf(taskId)).resolve(REPORT_FILE).normalize();
    }

    private TaskItemDO toItem(Long taskId, EbayOrderReportBuilder.Line line) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setOrderNumber(line.orderId());
        item.setListingId(line.itemId());
        item.setSku(truncate(line.sku(), 50));
        item.setTitle(truncate(line.title(), 255));
        item.setStyleId(line.styleId());
        item.setSize(truncate(line.usSize(), 16));
        item.setEuSize(truncate(line.euSize(), 16));
        item.setSalePrice(line.price());
        item.setCurrencyCode("USD");
        item.setSoldOn(line.createdTime() == null ? null : Date.from(line.createdTime()));
        // 与 StockX 获取订单一致：operateTime 存发货截止时间。
        item.setOperateTime(line.shipBy() == null ? null : Date.from(line.shipBy()));
        item.setOrderStatus(line.status());
        item.setOperateResult(line.status());
        return item;
    }

    private EbayOrderReportBuilder.MappingLookup mappingLookup(List<Element> orders) {
        Set<String> skus = new LinkedHashSet<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Element order : orders) {
            for (Element tx : EbayTradingApiClient.children(
                    EbayTradingApiClient.firstElement(order, "TransactionArray"), "Transaction")) {
                String sku = EbayTradingApiClient.text(tx, "Variation/SKU");
                if (sku != null) {
                    skus.add(sku);
                }
                String itemId = EbayTradingApiClient.text(tx, "Item/ItemID");
                if (itemId != null) {
                    itemIds.add(itemId);
                }
            }
        }
        Map<String, EbayListingDO> bySku = new HashMap<>();
        Map<String, List<EbayListingDO>> byListing = new HashMap<>();
        try {
            for (List<String> batch : batches(skus)) {
                for (EbayListingDO listing : ebayListingMapper.selectBySkus(batch)) {
                    bySku.put(listing.getSku(), listing);
                }
            }
            for (List<String> batch : batches(itemIds)) {
                for (EbayListingDO listing : ebayListingMapper.selectByListingIds(batch)) {
                    byListing.computeIfAbsent(listing.getListingId(), key -> new ArrayList<>()).add(listing);
                }
            }
        } catch (RuntimeException e) {
            log.warn("eBay获取订单：读取本地上架映射失败，货号将从标题提取", e);
        }
        return new EbayOrderReportBuilder.MappingLookup() {
            @Override
            public EbayListingDO bySku(String sku) {
                return bySku.get(sku);
            }

            @Override
            public Collection<EbayListingDO> byListingId(String listingId) {
                return byListing.getOrDefault(listingId, List.of());
            }
        };
    }

    private static List<List<String>> batches(Set<String> values) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String value : values) {
            current.add(value);
            if (current.size() >= LOOKUP_BATCH) {
                batches.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private <T> T optional(String label, java.util.function.Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn("eBay获取订单：获取{}失败，相关列留默认值", label, e);
            return fallback;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
