package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayTradingApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayTrackingExcel;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * eBay 添加追踪号码任务：读取 Excel 中的 订单编号 + 追踪号码（+ 物流公司，默认 FedEx），
 * 通过 Trading API CompleteSale 给订单添加追踪号码并标记为已发货，
 * 效果与卖家中心「添加或编辑追踪号码」弹窗一致。
 *
 * <p>同一订单的多行会合并为一次提交（对应弹窗中的「其他追踪号码」）；
 * 订单上已存在的追踪号码会跳过，因此任务可以安全地重复执行。
 */
@Slf4j
@Service
public class EbayTrackingUploadService {

    public static final String TASK_TYPE = "ebay_add_tracking";
    public static final String DEFAULT_CARRIER = "FedEx";
    public static final int MAX_ROWS = 2000;
    private static final Pattern ORDER_ID = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z\\-]{2,63}$");
    private static final Pattern TRACKING_NUMBER = Pattern.compile("^[0-9A-Za-z\\-]{4,64}$");
    private static final Pattern CARRIER = Pattern.compile("^(联邦|联邦快递|[0-9A-Za-z][0-9A-Za-z \\-]{0,63})$");
    private static final Map<String, String> CARRIER_ALIASES = Map.ofEntries(
            Map.entry("fedex", "FedEx"),
            Map.entry("fed ex", "FedEx"),
            Map.entry("联邦", "FedEx"),
            Map.entry("联邦快递", "FedEx"),
            Map.entry("ups", "UPS"),
            Map.entry("usps", "USPS"),
            Map.entry("dhl", "DHL"),
            Map.entry("ems", "EMS"));

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final TaskInputSnapshotStore snapshotStore;
    private final EbayTradingApiClient tradingClient;
    private final EbayProperties properties;
    private final Executor executor;
    private final Map<Long, AtomicBoolean> running = new ConcurrentHashMap<>();

    @Autowired
    public EbayTrackingUploadService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                                     TaskInputSnapshotStore snapshotStore,
                                     EbayTradingApiClient tradingClient, EbayProperties properties) {
        this(taskMapper, taskItemMapper, snapshotStore, tradingClient, properties,
                command -> Thread.ofVirtual().name("Ebay-Add-Tracking").start(command));
    }

    EbayTrackingUploadService(TaskMapper taskMapper, TaskItemMapper taskItemMapper,
                              TaskInputSnapshotStore snapshotStore, EbayTradingApiClient tradingClient,
                              EbayProperties properties, Executor executor) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.snapshotStore = snapshotStore;
        this.tradingClient = tradingClient;
        this.properties = properties;
        this.executor = executor;
    }

    /** 一个订单要提交的全部追踪号码。 */
    record OrderTracking(String orderId, List<EbayTradingApiClient.ShipmentTracking> trackings) {
    }

    /**
     * @return 任务ID；已有添加追踪号码任务在运行时返回 null
     * @throws IllegalArgumentException Excel 内容不合法
     */
    public synchronized Long start(List<EbayTrackingExcel> rows) {
        List<EbayTrackingExcel> input = normalize(rows);
        List<OrderTracking> orders = group(input);
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
        task.setParams(new JSONObject(true)
                .fluentPut("inputCount", input.size())
                .fluentPut("orderCount", orders.size())
                .toJSONString());
        taskMapper.insert(task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        running.put(task.getId(), cancelled);
        try {
            snapshotStore.saveEbayTrackingInput(task.getId(), input);
            executor.execute(() -> run(task.getId(), orders, cancelled));
            return task.getId();
        } catch (RuntimeException e) {
            running.remove(task.getId());
            taskMapper.updateTaskFailed(task.getId(), "任务输入保存或启动失败");
            throw e;
        }
    }

    public void cancel(Long taskId) {
        AtomicBoolean cancelled = running.get(taskId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    void run(Long taskId, List<OrderTracking> orders, AtomicBoolean cancelled) {
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        try {
            for (int start = 0; start < orders.size(); start += EbayTradingApiClient.ORDER_ID_BATCH) {
                List<OrderTracking> batch = orders.subList(start,
                        Math.min(orders.size(), start + EbayTradingApiClient.ORDER_ID_BATCH));
                Map<String, Element> found = lookup(batch);
                for (OrderTracking order : batch) {
                    if (cancelled.get()) {
                        writeAttributes(taskId, orders.size(), succeeded, skipped, failed);
                        taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.CANCEL.getCode());
                        return;
                    }
                    String result = process(order, found);
                    if (result.startsWith("添加成功")) {
                        succeeded++;
                    } else if (result.startsWith("跳过")) {
                        skipped++;
                    } else {
                        failed++;
                    }
                    saveItems(taskId, order, found.get(order.orderId()), result);
                }
                taskMapper.updateTaskRound(taskId, Math.min(orders.size(), start + batch.size()));
                writeAttributes(taskId, orders.size(), succeeded, skipped, failed);
            }
            writeAttributes(taskId, orders.size(), succeeded, skipped, failed);
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
            log.info("eBay添加追踪号码完成, taskId:{}, 成功:{}, 跳过:{}, 失败:{}", taskId, succeeded, skipped, failed);
        } catch (Exception e) {
            log.error("eBay添加追踪号码任务失败, taskId:{}", taskId, e);
            writeAttributes(taskId, orders.size(), succeeded, skipped, failed);
            taskMapper.updateTaskFailed(taskId, "添加追踪号码失败：" + safeError(e));
        } finally {
            running.remove(taskId);
        }
    }

    /** 批量查询订单；查询失败时返回空，由逐单提交时直接调用 CompleteSale 兜底。 */
    private Map<String, Element> lookup(List<OrderTracking> batch) {
        Map<String, Element> found = new HashMap<>();
        try {
            for (Element order : tradingClient.getOrdersByIds(batch.stream().map(OrderTracking::orderId).toList())) {
                String orderId = EbayTradingApiClient.text(order, "OrderID");
                if (orderId != null) {
                    found.put(orderId, order);
                }
                // 旧格式订单号（itemId-transactionId）查询时也可能返回新格式订单号，两者都登记。
                String extendedId = EbayTradingApiClient.text(order, "ExtendedOrderID");
                if (extendedId != null) {
                    found.putIfAbsent(extendedId, order);
                }
            }
            return found;
        } catch (RuntimeException e) {
            log.warn("eBay添加追踪号码：批量查询订单失败，改为直接提交, {}", safeError(e));
            return new LookupFailed();
        }
    }

    /** 标记查询失败，避免把所有订单误判为「订单不存在」。 */
    private static final class LookupFailed extends HashMap<String, Element> {
    }

    private String process(OrderTracking order, Map<String, Element> found) {
        Element detail = found.get(order.orderId());
        List<EbayTradingApiClient.ShipmentTracking> pending = order.trackings();
        if (!(found instanceof LookupFailed)) {
            if (detail == null) {
                return "失败：eBay未找到该订单，请检查订单编号";
            }
            if ("Cancelled".equalsIgnoreCase(EbayTradingApiClient.text(detail, "OrderStatus"))) {
                return "失败：订单已取消";
            }
            Set<String> existing = existingTrackingNumbers(detail);
            pending = pending.stream()
                    .filter(tracking -> !existing.contains(key(tracking.trackingNumber())))
                    .toList();
            if (pending.isEmpty()) {
                return "跳过：追踪号码已存在";
            }
        }
        try {
            tradingClient.addTracking(order.orderId(), pending);
            return pending.size() == order.trackings().size() ? "添加成功"
                    : "添加成功(部分追踪号码已存在)";
        } catch (RuntimeException e) {
            log.warn("eBay添加追踪号码失败, orderId:{}, {}", order.orderId(), safeError(e));
            return "失败：" + safeError(e);
        }
    }

    private static Set<String> existingTrackingNumbers(Element order) {
        Set<String> numbers = new LinkedHashSet<>();
        for (Element tx : EbayTradingApiClient.children(
                EbayTradingApiClient.firstElement(order, "TransactionArray"), "Transaction")) {
            for (Element tracking : EbayTradingApiClient.children(
                    EbayTradingApiClient.firstElement(tx, "ShippingDetails"), "ShipmentTrackingDetails")) {
                String number = EbayTradingApiClient.text(tracking, "ShipmentTrackingNumber");
                if (number != null) {
                    numbers.add(key(number));
                }
            }
        }
        return numbers;
    }

    private void saveItems(Long taskId, OrderTracking order, Element detail, String result) {
        String itemId = null;
        String title = null;
        String sku = null;
        if (detail != null) {
            List<Element> transactions = EbayTradingApiClient.children(
                    EbayTradingApiClient.firstElement(detail, "TransactionArray"), "Transaction");
            if (!transactions.isEmpty()) {
                Element tx = transactions.get(0);
                itemId = EbayTradingApiClient.text(tx, "Item/ItemID");
                title = firstNonBlank(EbayTradingApiClient.text(tx, "Variation/VariationTitle"),
                        EbayTradingApiClient.text(tx, "Item/Title"));
                sku = firstNonBlank(EbayTradingApiClient.text(tx, "Variation/SKU"),
                        EbayTradingApiClient.text(tx, "Item/SKU"));
                if (transactions.size() > 1 && title != null) {
                    title = title + " 等" + transactions.size() + "件";
                }
            }
        }
        for (EbayTradingApiClient.ShipmentTracking tracking : order.trackings()) {
            TaskItemDO item = new TaskItemDO();
            item.setTaskId(taskId);
            item.setRound(0);
            item.setOrderNumber(order.orderId());
            item.setTrackingNumber(tracking.trackingNumber());
            item.setShippingCarrier(tracking.carrier());
            item.setListingId(itemId);
            item.setTitle(truncate(title, 255));
            item.setSku(truncate(sku, 50));
            item.setOperateResult(truncate(result, 255));
            item.setOperateTime(new Date());
            try {
                taskItemMapper.insert(item);
            } catch (RuntimeException e) {
                log.warn("eBay添加追踪号码：写入任务明细失败, taskId:{}, orderId:{}", taskId, order.orderId(), e);
            }
        }
    }

    private void writeAttributes(Long taskId, int total, int succeeded, int skipped, int failed) {
        try {
            taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                    .fluentPut("total", total)
                    .fluentPut("succeeded", succeeded)
                    .fluentPut("skipped", skipped)
                    .fluentPut("failed", failed)
                    .toJSONString());
        } catch (RuntimeException e) {
            log.warn("eBay添加追踪号码：更新任务进度失败, taskId:{}", taskId, e);
        }
    }

    // ==================== 输入校验 ====================

    /** 去掉空行、修整空白、补默认物流公司；任何非法行都会抛出带行号的错误。 */
    static List<EbayTrackingExcel> normalize(List<EbayTrackingExcel> rows) {
        List<EbayTrackingExcel> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int rowNumber = 1;
        for (EbayTrackingExcel row : rows == null ? List.<EbayTrackingExcel>of() : rows) {
            rowNumber++;
            if (row == null) {
                continue;
            }
            String orderId = clean(row.getOrderNumber());
            String trackingNumber = clean(row.getTrackingNumber());
            String carrier = row.getCarrier() == null || row.getCarrier().isBlank()
                    ? null : row.getCarrier().replace('\u00A0', ' ').strip();
            if (orderId == null && trackingNumber == null && carrier == null) {
                continue;
            }
            if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
                errors.add("第" + rowNumber + "行订单编号无效");
                continue;
            }
            if (trackingNumber == null || !TRACKING_NUMBER.matcher(trackingNumber).matches()) {
                errors.add("第" + rowNumber + "行追踪号码无效");
                continue;
            }
            if (carrier != null && !CARRIER.matcher(carrier).matches()) {
                errors.add("第" + rowNumber + "行物流公司无效（仅支持字母、数字、空格和-）");
                continue;
            }
            EbayTrackingExcel normalized = new EbayTrackingExcel();
            normalized.setOrderNumber(orderId);
            normalized.setTrackingNumber(trackingNumber);
            normalized.setCarrier(normalizeCarrier(carrier));
            result.add(normalized);
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors.subList(0, Math.min(10, errors.size())))
                    + (errors.size() > 10 ? " 等" + errors.size() + "处错误" : ""));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Excel中没有有效的订单编号和追踪号码");
        }
        if (result.size() > MAX_ROWS) {
            throw new IllegalArgumentException("单次最多" + MAX_ROWS + "行");
        }
        return result;
    }

    /** 同一订单的多个追踪号码合并成一次提交，重复的追踪号码只保留一次。 */
    static List<OrderTracking> group(List<EbayTrackingExcel> rows) {
        Map<String, LinkedHashMap<String, EbayTradingApiClient.ShipmentTracking>> byOrder = new LinkedHashMap<>();
        for (EbayTrackingExcel row : rows) {
            byOrder.computeIfAbsent(row.getOrderNumber(), key -> new LinkedHashMap<>())
                    .putIfAbsent(key(row.getTrackingNumber()),
                            new EbayTradingApiClient.ShipmentTracking(row.getTrackingNumber(), row.getCarrier()));
        }
        List<OrderTracking> orders = new ArrayList<>();
        byOrder.forEach((orderId, trackings) ->
                orders.add(new OrderTracking(orderId, List.copyOf(trackings.values()))));
        return orders;
    }

    static String normalizeCarrier(String carrier) {
        if (carrier == null) {
            return DEFAULT_CARRIER;
        }
        String alias = CARRIER_ALIASES.get(carrier.toLowerCase(Locale.ROOT));
        return alias != null ? alias : carrier;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        // Excel 中常见：数字被存成 5.243822269E11 或带不可见空白/前导撇号。
        String trimmed = value.replace('\u00A0', ' ').replace("\u200B", "").strip();
        if (trimmed.startsWith("'")) {
            trimmed = trimmed.substring(1).strip();
        }
        if (trimmed.matches("^\\d+(\\.\\d+)?[eE]\\+?\\d+$")) {
            try {
                trimmed = new java.math.BigDecimal(trimmed).toBigIntegerExact().toString();
            } catch (ArithmeticException ignored) {
                // 保留原值，交给格式校验报错
            }
        } else if (trimmed.matches("^\\d+\\.0+$")) {
            trimmed = trimmed.substring(0, trimmed.indexOf('.'));
        }
        trimmed = trimmed.replaceAll("\\s+", "");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String key(String trackingNumber) {
        return trackingNumber == null ? "" : trackingNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
