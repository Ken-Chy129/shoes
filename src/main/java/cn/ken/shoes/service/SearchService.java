package cn.ken.shoes.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Pair;
import cn.ken.shoes.client.DunkClient;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.mapper.SearchTaskMapper;
import cn.ken.shoes.model.entity.SearchTaskDO;
import cn.ken.shoes.model.excel.StockXPriceExcel;
import cn.ken.shoes.model.search.SearchTaskVO;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * @author Ken-Chy129
 * @date 2025/11/16
 */
@Slf4j
@Service
public class SearchService {

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
    public Long createSearchTask(String query, String sorts, Integer pageCount, String searchType) {
        // 创建任务记录
        SearchTaskDO searchTask = new SearchTaskDO();
        searchTask.setQuery(query);
        searchTask.setSorts(sorts);
        searchTask.setPageCount(pageCount);
        searchTask.setSearchType(searchType);
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

            String query = searchTask.getQuery();
            String sortsStr = searchTask.getSorts();
            Integer pageCount = searchTask.getPageCount();
            String searchType = searchTask.getSearchType();

            // 分割sorts字符串为列表
            List<String> sortsList = Arrays.asList(sortsStr.split(","));

            // 使用LinkedHashMap保证去重后保持顺序
            Map<String, StockXPriceExcel> resultMap = new LinkedHashMap<>();

            // 计算总的查询次数用于进度计算
            int totalQueries = sortsList.size() * pageCount;
            int completedQueries = 0;

            // 遍历每个sort进行查询
            for (String sort : sortsList) {
                Pair<Integer, List<StockXPriceExcel>> firstPair = stockXClient.searchItemWithPrice(query, 1, sort.trim(), searchType);
                if (firstPair == null) {
                    log.error("executeSearchTask no result, taskId:{}, query:{}, sort:{}, page:{}", taskId, query, sort, 1);
                    completedQueries++;
                    int progress = (int) ((completedQueries * 100.0) / totalQueries);
                    searchTaskMapper.updateProgress(taskId, progress);
                    continue;
                }

                Integer totalPage = firstPair.getKey();

                // 处理第一页数据
                for (StockXPriceExcel stockXPriceExcel : firstPair.getValue()) {
                    String modelNo = stockXPriceExcel.getModelNo();
                    String euSize = stockXPriceExcel.getEuSize();
                    String key = STR."\{modelNo}:\{euSize}";

                    if (!resultMap.containsKey(key)) {
                        stockXPriceExcel.setPoisonPrice(priceManager.getPoisonPrice(modelNo, euSize));
                        resultMap.put(key, stockXPriceExcel);
                    }
                }

                // 更新进度
                completedQueries++;
                int progress = (int) ((completedQueries * 100.0) / totalQueries);
                searchTaskMapper.updateProgress(taskId, progress);

                // 处理后续页
                for (int i = 2; i <= Math.min(pageCount, totalPage); i++) {
                    Pair<Integer, List<StockXPriceExcel>> pair = stockXClient.searchItemWithPrice(query, i, sort.trim(), searchType);
                    if (pair == null) {
                        log.error("executeSearchTask no result, taskId:{}, query:{}, sort:{}, page:{}", taskId, query, sort, i);
                        completedQueries++;
                        progress = (int) ((completedQueries * 100.0) / totalQueries);
                        searchTaskMapper.updateProgress(taskId, progress);
                        continue;
                    }

                    for (StockXPriceExcel stockXPriceExcel : pair.getValue()) {
                        String modelNo = stockXPriceExcel.getModelNo();
                        String euSize = stockXPriceExcel.getEuSize();
                        String key = STR."\{modelNo}:\{euSize}";

                        if (!resultMap.containsKey(key)) {
                            stockXPriceExcel.setPoisonPrice(priceManager.getPoisonPrice(modelNo, euSize));
                            resultMap.put(key, stockXPriceExcel);
                        }
                    }

                    // 更新进度
                    completedQueries++;
                    progress = (int) ((completedQueries * 100.0) / totalQueries);
                    searchTaskMapper.updateProgress(taskId, progress);
                }
            }

            // 生成文件名（使用时间戳避免重复）
            String timestamp = String.valueOf(System.currentTimeMillis());
            String filename = STR."\{searchType}_\{query}_\{timestamp}";
            String filePath = STR."file/stockx/search/\{filename}.xlsx";

            // 保存到Excel
            List<StockXPriceExcel> resultList = new ArrayList<>(resultMap.values());
            saveItemsToExcel(filePath, resultList);

            // 更新任务状态为SUCCESS
            searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.SUCCESS.getCode(), new Date(), filePath);
            log.info("executeSearchTask success, taskId:{}, query:{}, resultCount:{}", taskId, query, resultList.size());

        } catch (Exception e) {
            log.error("executeSearchTask error, taskId:{}, msg:{}", taskId, e.getMessage(), e);
            // 更新任务状态为FAILED
            searchTaskMapper.updateStatus(taskId, SearchTaskDO.StatusEnum.FAILED.getCode(), new Date(), null);
        }
    }

    public void saveItemsToExcel(String filepath, List<StockXPriceExcel> items) {
        File file = new File(filepath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean isSuccess = parentDir.mkdirs();
            if (!isSuccess) {
                return;
            }
        }
        try (ExcelWriter excelWriter = EasyExcel.write(filepath).build()) {
            WriteSheet writeSheet = EasyExcel.writerSheet(0, "结果").head(StockXPriceExcel.class).build();
            excelWriter.write(items, writeSheet);
        }
    }

    public PageResult<List<SearchTaskVO>> getSearchTasks(String status, Integer pageIndex, Integer pageSize) {
        Long count = searchTaskMapper.count(status);
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

        List<SearchTaskDO> taskList = searchTaskMapper.selectByCondition(status, startIndex, pageSize);
        List<SearchTaskVO> voList = BeanUtil.copyToList(taskList, SearchTaskVO.class);

        PageResult<List<SearchTaskVO>> result = PageResult.buildSuccess(voList);
        result.setTotal(count);
        return result;
    }
}
