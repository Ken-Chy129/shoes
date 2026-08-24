package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.manager.TaskInputSnapshotStore;
import cn.ken.shoes.mapper.TaskItemMapper;
import cn.ken.shoes.mapper.TaskMapper;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayListingResult;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.TaskDO;
import cn.ken.shoes.model.entity.TaskItemDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class EbayBulkListingService {

    public static final String TASK_TYPE = "ebay_bulk_listing";
    private static final int MAX_ROWS = 1_000;

    private final TaskMapper taskMapper;
    private final TaskItemMapper taskItemMapper;
    private final TaskInputSnapshotStore snapshotStore;
    private final EbayProductMetadataService metadataService;
    private final EbayListingFactory listingFactory;
    private final EbayListingService listingService;
    private final Executor executor;
    private final EbayProperties properties;

    @Autowired
    public EbayBulkListingService(TaskMapper taskMapper,
                                  TaskItemMapper taskItemMapper,
                                  TaskInputSnapshotStore snapshotStore,
                                  EbayProductMetadataService metadataService,
                                  EbayListingFactory listingFactory,
                                  EbayListingService listingService,
                                  EbayProperties properties) {
        this(taskMapper, taskItemMapper, snapshotStore, metadataService, listingFactory, listingService,
                command -> Thread.ofVirtual().name("Ebay-Bulk-Listing").start(command), properties);
    }

    EbayBulkListingService(TaskMapper taskMapper,
                           TaskItemMapper taskItemMapper,
                           TaskInputSnapshotStore snapshotStore,
                           EbayProductMetadataService metadataService,
                           EbayListingFactory listingFactory,
                           EbayListingService listingService,
                           Executor executor) {
        this(taskMapper, taskItemMapper, snapshotStore, metadataService, listingFactory, listingService,
                executor, new EbayProperties());
    }

    private EbayBulkListingService(TaskMapper taskMapper,
                                   TaskItemMapper taskItemMapper,
                                   TaskInputSnapshotStore snapshotStore,
                                   EbayProductMetadataService metadataService,
                                   EbayListingFactory listingFactory,
                                   EbayListingService listingService,
                                   Executor executor,
                                   EbayProperties properties) {
        this.taskMapper = taskMapper;
        this.taskItemMapper = taskItemMapper;
        this.snapshotStore = snapshotStore;
        this.metadataService = metadataService;
        this.listingFactory = listingFactory;
        this.listingService = listingService;
        this.executor = executor;
        this.properties = properties;
    }

    public Long start(List<EbayListingExcel> rows) {
        List<EbayListingExcel> input = rows == null ? List.of() : List.copyOf(rows);
        validateInput(input);
        TaskDO task = new TaskDO();
        task.setPlatform("ebay");
        task.setTaskType(TASK_TYPE);
        task.setAccountName(properties.getEnvironment());
        task.setStatus(TaskDO.TaskStatusEnum.RUNNING.getCode());
        task.setStartTime(new Date());
        task.setRound(0);
        task.setParams(new JSONObject(true)
                .fluentPut("inputCount", input.size())
                .fluentPut("marketplaceId", properties.getDefaultMarketplaceId())
                .fluentPut("currency", properties.getDefaultCurrency())
                .fluentPut("merchantLocationKey", properties.getDefaultMerchantLocationKey())
                .toJSONString());
        taskMapper.insert(task);
        try {
            snapshotStore.saveEbayBulkListingInput(task.getId(), input);
            executor.execute(() -> run(task.getId(), input));
            return task.getId();
        } catch (RuntimeException e) {
            taskMapper.updateTaskFailed(task.getId(), "任务输入保存或启动失败");
            throw e;
        }
    }

    void run(Long taskId, List<EbayListingExcel> rows) {
        int succeeded = 0;
        int failed = 0;
        for (EbayListingExcel row : rows) {
            TaskItemDO item = initialTaskItem(taskId, row);
            taskItemMapper.insert(item);
            try {
                EbayProductMetadata metadata = metadataService.resolve(row);
                EbayListingRequest request = listingFactory.create(row, metadata);
                EbayListingResult result = listingService.publish(request);
                item.setTitle(request.getTitle());
                item.setBrand(request.getBrand());
                item.setSku(result.getSku());
                item.setOfferId(result.getOfferId());
                item.setListingId(result.getListingId());
                item.setOperateResult("上架成功");
                succeeded++;
            } catch (Exception e) {
                item.setOperateResult("上架失败(" + safeError(e) + ")");
                failed++;
            }
            item.setOperateTime(new Date());
            taskItemMapper.updateById(item);
        }
        taskMapper.updateTaskAttributes(taskId, new JSONObject(true)
                .fluentPut("total", rows.size())
                .fluentPut("succeeded", succeeded)
                .fluentPut("failed", failed)
                .toJSONString());
        if (failed == 0) {
            taskMapper.updateTaskStatus(taskId, TaskDO.TaskStatusEnum.SUCCESS.getCode());
        } else {
            taskMapper.updateTaskFailed(taskId,
                    "批量上架完成：成功 " + succeeded + "，失败 " + failed + "，请查看任务明细");
        }
    }

    private TaskItemDO initialTaskItem(Long taskId, EbayListingExcel row) {
        TaskItemDO item = new TaskItemDO();
        item.setTaskId(taskId);
        item.setRound(0);
        item.setStyleId(trim(row.getStyleId()));
        item.setSize(trim(row.getSize()));
        item.setCurrentPrice(row.getPrice());
        item.setCurrencyCode(properties.getDefaultCurrency());
        item.setListingQuantity(row.getQuantity());
        item.setOperateResult("待上架");
        item.setOperateTime(new Date());
        return item;
    }

    private void validateInput(List<EbayListingExcel> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Excel中没有可上架的数据");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("单次最多上架1000行商品");
        }
        Set<String> skuKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            EbayListingExcel row = rows.get(i);
            try {
                if (row == null || trim(row.getStyleId()) == null) {
                    throw new IllegalArgumentException("货号不能为空");
                }
                EbayListingFactory.ParsedSize size = listingFactory.parseSize(row.getSize());
                if (row.getQuantity() == null || row.getQuantity() < 1 || row.getQuantity() > 999_999) {
                    throw new IllegalArgumentException("数量必须是1到999999之间的整数");
                }
                if (row.getPrice() == null || row.getPrice().signum() <= 0 || row.getPrice().scale() > 2) {
                    throw new IllegalArgumentException("上架价格必须是大于0且最多两位小数的USD金额");
                }
                String key = trim(row.getStyleId()).toUpperCase(Locale.ROOT) + ":" + size.normalized();
                if (!skuKeys.add(key)) {
                    throw new IllegalArgumentException("货号和尺码重复");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("第" + (i + 2) + "行：" + e.getMessage());
            }
        }
    }

    private String safeError(Exception error) {
        if (error instanceof IllegalArgumentException || error instanceof EbayApiException) {
            String message = error.getMessage();
            if (message != null && !message.isBlank()) {
                return message.length() <= 180 ? message : message.substring(0, 180);
            }
        }
        return "系统异常，请稍后重试";
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
