package cn.ken.shoes.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.DunkClient;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.config.StockXConfig;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.common.Gender;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.PlatformEnum;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.dunk.DunkItem;
import cn.ken.shoes.model.dunk.DunkSalesHistory;
import cn.ken.shoes.model.dunk.DunkSearchRequest;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.model.excel.DunkPriceExcel;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.search.SearchTaskRequest;
import cn.ken.shoes.model.search.SearchTaskVO;
import cn.ken.shoes.util.GenderUtil;
import cn.ken.shoes.util.SizeConvertUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Slf4j
@Service
public class SearchService {

    private final Set<Long> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    /** 执行中增量落盘的最小间隔，避免大任务（多排序×多页）频繁全量重写 Excel。 */
    private static final long PARTIAL_FLUSH_INTERVAL_MS = 30_000L;

    /** taskId -> 最近一次增量落盘时间 */
    private final Map<Long, Long> partialFlushAt = new ConcurrentHashMap<>();

    @Resource
    private SearchTaskMapper searchTaskMapper;

    @Resource
    private StockXClient stockXClient;

    @Resource
    private DunkClient dunkClient;

    @Resource
    private PriceManager priceManager;

    /**
     * 创建搜索任务
     */
    public Long createSearchTask(SearchTaskRequest request) {
        // 创建任务记录
        SearchTaskDO searchTask = new SearchTaskDO();
        searchTask.setPlatform(request.getPlatform());
        searchTask.setQuery(request.getQuery());
        searchTask.setSorts(request.getSorts());
        searchTask.setPageCount(request.getPageCount());
        searchTask.setType(request.getType());
        searchTask.setSearchType(request.getSearchType());
        searchTask.setAccountName(request.getAccountName());
        searchTask.setProgress(0);
        searchTask.setStatus(SearchTaskDO.StatusEnum.PENDING.getCode());

        // 保存到数据库
        searchTaskMapper.insert(searchTask);
        Long taskId = searchTask.getId();

        // 异步执行搜索任务
        Thread.startVirtualThread(() -> executeSearchTask(taskId));

        return taskId;
    }

    /**
     * 异步执行搜索任务
     */
    public void executeSearchTask(Long taskId) {
        SearchTaskDO searchTask = searchTaskMapper.selectById(taskId);
        if (searchTask == null) {
            log.error("executeSearchTask task not found, taskId:{}", taskId);
            return;
        }

        try {
            // 更新任务状态为RUNNING
            searchTaskMapper.updateStartStatus(taskId, SearchTaskDO.StatusEnum.RUNNING.getCode(), new Date());

            String type = searchTask.getType();

            // 根据 type 执行不同逻辑
            if ("modelNo".equals(type)) {
                executeModelNoSearch(taskId, searchTask);
            } else {
                executeKeywordSearch(taskId, searchTask);
            }
        } catch (Exception e) {
            log.error("executeSearchTask error, taskId:{}, msg:{}", taskId, e.getMessage(), e);
            // 更新任务状态为FAILED（已增量落盘的 file_path 保留，失败任务同样可下载部分结果）
            searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.FAILED.getCode(), new Date(), null);
        } finally {
            partialFlushAt.remove(taskId);
        }
    }

    public void cancelSearchTask(Long taskId) {
        cancelledTaskIds.add(taskId);
        searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.CANCELLED.getCode(), new Date(), null);
        log.info("cancelSearchTask, taskId:{}", taskId);
    }

    private boolean isCancelled(Long taskId) {
        return cancelledTaskIds.contains(taskId);
    }

    /**
     * 执行关键词搜索任务
     */
    private void executeKeywordSearch(Long taskId, SearchTaskDO searchTask) {
        String platform = searchTask.getPlatform();
        String query = searchTask.getQuery();
        String sortsStr = searchTask.getSorts();
        Integer pageCount = searchTask.getPageCount();
        String type = searchTask.getType();
        String searchType = searchTask.getSearchType();

            // 分割sorts字符串为列表
            List<String> sortsList = Arrays.asList(sortsStr.split(","));

            // 使用LinkedHashMap保证去重后保持顺序
            Map<String, JSONObject> resultMap = new LinkedHashMap<>();

            // 结果文件在首次拿到数据时就落盘，并随进度覆盖写入，
            // 这样任务被取消或因重启置为 shelved 时，已爬到的数据仍可下载。
            String filePath = buildFilePath(platform, type, query);

            // 计算总的查询次数用于进度计算
            int totalQueries = sortsList.size() * pageCount;
            int completedQueries = 0;

            // 遍历每个sort进行查询
            for (String sort : sortsList) {
                if (isCancelled(taskId)) {
                    cancelledTaskIds.remove(taskId);
                    flushPartialResult(taskId, platform, filePath, resultMap, true);
                    return;
                }
                Pair<Integer, JSONArray> firstPair = doSearch(taskId, platform, query, sort.trim(), searchType, 1, searchTask.getAccountName());
                Integer totalPage = firstPair.getKey();
                // 修正totalQueries：用实际页数替代预估页数
                int actualPages = Math.min(pageCount, totalPage);
                totalQueries -= (pageCount - Math.max(actualPages, 1));
                if (totalPage == 0 || CollectionUtils.isEmpty(firstPair.getValue())) {
                    log.error("executeSearchTask no result, taskId:{}, query:{}, sort:{}, page:{}", taskId, query, sort, 1);
                    completedQueries++;
                    int progress = (int) ((completedQueries * 100.0) / totalQueries);
                    searchTaskMapper.updateProgress(taskId, progress);
                    continue;
                }

                // 处理第一页数据
                for (Object item : firstPair.getValue()) {
                    JSONObject jsonObject = enhanceItem(item);
                    String key = getItemKey(platform, item);
                    if (!resultMap.containsKey(key)) {
                        resultMap.put(key, jsonObject);
                    }
                }

                // 更新进度
                completedQueries++;
                int progress = (int) ((completedQueries * 100.0) / totalQueries);
                searchTaskMapper.updateProgress(taskId, progress);
                flushPartialResult(taskId, platform, filePath, resultMap);

                // 处理后续页
                for (int i = 2; i <= Math.min(pageCount, totalPage); i++) {
                    if (isCancelled(taskId)) {
                        cancelledTaskIds.remove(taskId);
                        flushPartialResult(taskId, platform, filePath, resultMap, true);
                        return;
                    }
                    Pair<Integer, JSONArray> pair = doSearch(taskId, platform, query, sort.trim(), searchType, i, searchTask.getAccountName());
                    if (pair.getKey() == 0 || CollectionUtils.isEmpty(pair.getValue())) {
                        log.error("executeSearchTask no result, taskId:{}, query:{}, sort:{}, page:{}", taskId, query, sort, i);
                        completedQueries++;
                        progress = (int) ((completedQueries * 100.0) / totalQueries);
                        searchTaskMapper.updateProgress(taskId, progress);
                        continue;
                    }

                    for (Object item : pair.getValue()) {
                        JSONObject jsonObject = enhanceItem(item);
                        String key = getItemKey(platform, item);
                        if (!resultMap.containsKey(key)) {
                            resultMap.put(key, jsonObject);
                        }
                    }

                    // 更新进度
                    completedQueries++;
                    progress = (int) ((completedQueries * 100.0) / totalQueries);
                    searchTaskMapper.updateProgress(taskId, progress);
                    flushPartialResult(taskId, platform, filePath, resultMap);
                }
            }

        // 保存到Excel
        List<JSONObject> resultList = new ArrayList<>(resultMap.values());
        saveItemsToExcel(platform, filePath, resultList);

        // 更新任务状态为SUCCESS
        searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.SUCCESS.getCode(), new Date(), filePath);
        log.info("executeKeywordSearch success, taskId:{}, query:{}, resultCount:{}", taskId, query, resultList.size());
    }

    /**
     * 执行货号搜索任务
     */
    private void executeModelNoSearch(Long taskId, SearchTaskDO searchTask) {
        String platform = searchTask.getPlatform();
        String queryStr = searchTask.getQuery();
        String type = searchTask.getType();
        String sort = searchTask.getSorts(); // 固定为 "featured"

        // 根据换行符拆分货号
        List<String> modelNoList = Arrays.stream(queryStr.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (modelNoList.isEmpty()) {
            log.error("executeModelNoSearch no modelNo found, taskId:{}", taskId);
            searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.FAILED.getCode(), new Date(), null);
            return;
        }

        // 使用LinkedHashMap保证去重后保持顺序
        Map<String, JSONObject> resultMap = new LinkedHashMap<>();

        // 与关键词搜索一致：边爬边落盘，中断也能下载已获取的货号数据
        String filePath = buildFilePath(platform, type, null);

        // 计算总任务数:按货号数量计算
        int totalModelNos = modelNoList.size();
        int completedModelNos = 0;

        // 遍历每个货号进行查询(只查询第一页)
        for (String modelNo : modelNoList) {
            if (isCancelled(taskId)) {
                cancelledTaskIds.remove(taskId);
                flushPartialResult(taskId, platform, filePath, resultMap, true);
                return;
            }
            // 只查询第一页,pageIndex=1,searchType默认为"shoes"
            Pair<Integer, JSONArray> pair = doSearch(taskId, platform, modelNo, sort.trim(), "shoes", 1, searchTask.getAccountName());

            if (pair.getKey() == 0 || CollectionUtils.isEmpty(pair.getValue())) {
                log.warn("executeModelNoSearch no result for modelNo:{}, taskId:{}", modelNo, taskId);
            } else {
                // 处理查询结果,只保留与目标货号匹配的数据
                for (Object item : pair.getValue()) {
                    JSONObject jsonObject = (JSONObject) item;
                    String itemModelNo = jsonObject.getString("modelNo");

                    // 过滤:只保留货号匹配的商品
                    if (itemModelNo != null && itemModelNo.equalsIgnoreCase(modelNo)) {
                        JSONObject enhancedItem = enhanceItem(item);
                        String key = getItemKey(platform, item);
                        if (!resultMap.containsKey(key)) {
                            resultMap.put(key, enhancedItem);
                        }
                    }
                }
            }

            // 更新进度:每完成一个货号查询,进度增加
            completedModelNos++;
            int progress = (int) ((completedModelNos * 100.0) / totalModelNos);
            searchTaskMapper.updateProgress(taskId, progress);
            flushPartialResult(taskId, platform, filePath, resultMap);
        }

        // 保存到Excel
        List<JSONObject> resultList = new ArrayList<>(resultMap.values());
        saveItemsToExcel(platform, filePath, resultList);

        // 更新任务状态为SUCCESS
        searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.SUCCESS.getCode(), new Date(), filePath);
        log.info("executeModelNoSearch success, taskId:{}, modelNoCount:{}, resultCount:{}",
                 taskId, modelNoList.size(), resultList.size());
    }

    private Pair<Integer, JSONArray> doSearch(Long taskId, String platform, String query, String sort, String searchType, Integer pageIndex, String accountName) {
        if ("stockx".equals(platform)) {
            StockXAccount account = accountName != null ? StockXConfig.getAccount(accountName) : null;
            String country = account != null ? account.getCountry() : "HK";
            Pair<Integer, List<StockXPriceExcel>> pair = stockXClient.searchItemWithPrice(query, pageIndex, sort.trim(), searchType, country, account);
            return Pair.of(pair.getKey(), (JSONArray) JSONArray.toJSON(pair.getValue()));
        } else if ("dunk".equals(platform)) {
            DunkSearchRequest dunkSearchRequest = new DunkSearchRequest();
            dunkSearchRequest.setKeyword(query);
            dunkSearchRequest.setSortKey(sort);
            dunkSearchRequest.setPage(pageIndex);
            Pair<Integer, List<DunkItem>> pair = dunkClient.search(dunkSearchRequest);
            List<DunkPriceExcel> dunkPriceExcels = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch priceLatch = new CountDownLatch(pair.getValue().size());
            for (DunkItem dunkItem : pair.getValue()) {
                Thread.startVirtualThread(() -> {
                    try {
                        if (isCancelled(taskId)) {
                            return;
                        }
                        String modelNo = dunkItem.getModelNo();
                        List<DunkPriceExcel> priceList = dunkClient.queryPrice(dunkItem.getCategory(), modelNo);
                        if (CollectionUtils.isEmpty(priceList)) {
                            log.error("queryPrice error, item:{}", dunkItem);
                            return;
                        }
                        String title = dunkItem.getTitle();
                        Gender gender = GenderUtil.extractGender(PlatformEnum.DUNK, title);
                        CountDownLatch salesLatch = new CountDownLatch(priceList.size());
                        for (DunkPriceExcel price : priceList) {
                            Thread.startVirtualThread(() -> {
                                try {
                                    if (isCancelled(taskId)) {
                                        return;
                                    }
                                    DunkPriceExcel dunkPriceExcel = new DunkPriceExcel();
                                    dunkPriceExcel.setModelNo(modelNo);
                                    dunkPriceExcel.setCategory(dunkItem.getCategory());
                                    dunkPriceExcel.setBrand(dunkItem.getBrandId());
                                    dunkPriceExcel.setTitle(dunkItem.getTitle());
                                    dunkPriceExcel.setSize(price.getSize());
                                    dunkPriceExcel.setSizeText(price.getSizeText());
                                    dunkPriceExcel.setEuSize(SizeConvertUtil.getDunkEuSize(dunkItem.getBrandId(), gender, price.getSizeText()));
                                    dunkPriceExcel.setLowPrice(price.getLowPrice());
                                    dunkPriceExcel.setHighPrice(price.getHighPrice());
                                    dunkPriceExcel.setInventory(price.getInventory());
                                    dunkPriceExcel.setBuyCount(price.getBuyCount());
                                    List<DunkSalesHistory> dunkSalesHistories = dunkClient.querySalesHistory(dunkItem.getCategory(), modelNo, price.getSize());
                                    StringBuilder sb = new StringBuilder();
                                    for (DunkSalesHistory salesHistory : dunkSalesHistories) {
                                        sb.append("¥");
                                        sb.append(salesHistory.getPrice());
                                        sb.append("@");
                                        sb.append(salesHistory.getDate());
                                    }
                                    dunkPriceExcel.setLastSales(sb.toString());
                                    dunkPriceExcels.add(dunkPriceExcel);
                                } finally {
                                    salesLatch.countDown();
                                }
                            });
                        }
                        try {
                            salesLatch.await();
                        } catch (InterruptedException e) {
                            log.error(e.getMessage(), e);
                        }
                    } catch (Exception e) {
                        log.error("queryPrice error, modelNo:{}, category:{}, title:{}", dunkItem.getModelNo(), dunkItem.getCategory(), dunkItem.getTitle(), e);
                    } finally {
                        priceLatch.countDown();
                    }
                });
            }
            try {
                priceLatch.await();
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
            return Pair.of(pair.getKey(), (JSONArray) JSONArray.toJSON(dunkPriceExcels));
        }
        return Pair.of(0, new JSONArray());
    }

    private JSONObject enhanceItem(Object item) {
        JSONObject jsonObject = (JSONObject) item;
        String modelNo = jsonObject.getString("modelNo");
        String euSize = jsonObject.getString("euSize");
        jsonObject.put("poisonPrice", priceManager.getPoisonPrice(modelNo, euSize));
        return jsonObject;
    }

    private String getItemKey(String platform, Object item) {
        if ("stockx".equals(platform)) {
            JSONObject jsonObject = (JSONObject) item;
            String modelNo = jsonObject.getString("modelNo");
            String euSize = jsonObject.getString("euSize");
            return STR."\{modelNo}:\{euSize}";
        } else {
            JSONObject jsonObject = (JSONObject) item;
            String modelNo = jsonObject.getString("modelNo");
            String sizeText = jsonObject.getString("sizeText");
            return STR."\{modelNo}:\{sizeText}";
        }
    }

    private void saveItemsToExcel(String platform, String filepath, List<JSONObject> items) {
        File file = new File(filepath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean isSuccess = parentDir.mkdirs();
            if (!isSuccess) {
                return;
            }
        }

        try (ExcelWriter excelWriter = EasyExcel.write(filepath).build()) {
            if ("stockx".equals(platform)) {
                List<StockXPriceExcel> excels = new ArrayList<>();
                for (JSONObject jsonObject : items) {
                    excels.add(jsonObject.toJavaObject(StockXPriceExcel.class));
                }
                WriteSheet writeSheet = EasyExcel.writerSheet(0, "结果").head(StockXPriceExcel.class).build();
                excelWriter.write(excels, writeSheet);
            } else {
                List<DunkPriceExcel> excels = new ArrayList<>();
                for (JSONObject jsonObject : items) {
                    excels.add(jsonObject.toJavaObject(DunkPriceExcel.class));
                }
                WriteSheet writeSheet = EasyExcel.writerSheet(0, "结果").head(DunkPriceExcel.class).build();
                excelWriter.write(excels, writeSheet);
            }
        }
    }

    /** 结果文件路径：任务开始即确定，便于执行过程中反复覆盖写入同一个文件。 */
    private String buildFilePath(String platform, String type, String query) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String filename = query == null || query.isBlank()
                ? STR."\{platform}_\{type}_\{timestamp}"
                : STR."\{platform}_\{type}_\{query}_\{timestamp}";
        return STR."files/search/\{platform}/\{sanitizeFileName(filename)}.xlsx";
    }

    /** 关键词可能含换行、斜杠等非法文件名字符，统一替换避免落盘失败。 */
    private String sanitizeFileName(String filename) {
        return filename.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }

    /**
     * 把当前已抓取到的结果写入文件并回写 file_path。
     * 任务仍在执行时也保证磁盘上有一份可下载的快照，避免中断后长时间抓取的数据全部作废。
     */
    private void flushPartialResult(Long taskId, String platform, String filePath,
                                    Map<String, JSONObject> resultMap) {
        flushPartialResult(taskId, platform, filePath, resultMap, false);
    }

    /**
     * @param force true 表示必须落盘（任务取消/结束时），false 时按 {@link #PARTIAL_FLUSH_INTERVAL_MS} 节流，
     *              避免多排序多页任务频繁全量重写 Excel。
     */
    private void flushPartialResult(Long taskId, String platform, String filePath,
                                    Map<String, JSONObject> resultMap, boolean force) {
        if (resultMap.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            Long lastFlushAt = partialFlushAt.get(taskId);
            if (lastFlushAt != null && now - lastFlushAt < PARTIAL_FLUSH_INTERVAL_MS) {
                return;
            }
        }
        try {
            saveItemsToExcel(platform, filePath, new ArrayList<>(resultMap.values()));
            searchTaskMapper.updateFilePath(taskId, filePath);
            partialFlushAt.put(taskId, now);
        } catch (Exception e) {
            // 落盘失败不能影响主搜索流程，下一次进度更新会再试
            log.error("flushPartialResult error, taskId:{}, filePath:{}", taskId, filePath, e);
        }
    }

    public PageResult<List<SearchTaskVO>> getSearchTasks(String platform, String status, String type, Integer pageIndex, Integer pageSize) {
        Long count = searchTaskMapper.count(platform, status, type);
        if (count <= 0) {
            return PageResult.buildSuccess();
        }
        if (pageIndex == null) {
            pageIndex = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        int startIndex = (pageIndex - 1) * pageSize;

        List<SearchTaskDO> taskList = searchTaskMapper.selectByCondition(platform, status, type, startIndex, pageSize);
        List<SearchTaskVO> voList = BeanUtil.copyToList(taskList, SearchTaskVO.class);

        PageResult<List<SearchTaskVO>> result = PageResult.buildSuccess(voList);
        result.setTotal(count);
        return result;
    }

}
