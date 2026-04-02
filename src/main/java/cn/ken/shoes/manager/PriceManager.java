package cn.ken.shoes.manager;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SqlHelper;
import cn.ken.shoes.util.TimeUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceManager {

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private PoisonClient poisonClient;

    private final LoadingCache<String, Map<String, PoisonPriceDO>> CACHE = CacheBuilder.newBuilder()
                .maximumSize(100000) // 设置最大容量
                .expireAfterWrite(12, TimeUnit.HOURS) // 设置写入后过期时间
                .build(new CacheLoader<>() {
                    @NonNull
                    @Override
                    public Map<String, PoisonPriceDO> load(@NonNull String key) {
                        return loadPrice(key);
                    }
                }); // 加载逻辑

    @NonNull
    public Map<String, PoisonPriceDO> loadPrice(String modelNo) {
        // 处理形如 xxx/xxxx 的货号，先查前面的，查不到再查后面的
        if (modelNo.contains("/")) {
            String[] parts = modelNo.split("/", 2);
            String firstPart = parts[0].trim();
            String secondPart = parts[1].trim();

            // 先查前半部分
            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceByModelNo(firstPart);
            if (poisonPriceDOList != null && !poisonPriceDOList.isEmpty()) {
                return poisonPriceDOList.stream()
                        .collect(Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                Function.identity(),
                                (existing, replacement) -> existing
                        ));
            }

            // 前半部分查不到，再查后半部分
            poisonPriceDOList = poisonClient.queryPriceByModelNo(secondPart);
            if (poisonPriceDOList != null && !poisonPriceDOList.isEmpty()) {
                return poisonPriceDOList.stream()
                        .collect(Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                Function.identity(),
                                (existing, replacement) -> existing
                        ));
            }
            return Map.of();
        }

        // 普通货号，直接查询
        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceByModelNo(modelNo);
        if (poisonPriceDOList == null || poisonPriceDOList.isEmpty()) {
            return Map.of();
        }
        return poisonPriceDOList.stream()
                .collect(
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                Function.identity(),
                                (existing, replacement) -> existing
                        )
                );
    }

    public Integer getPoisonPrice(String modelNo, String euSize) {
        try {
            if (modelNo == null || euSize == null) {
                return null;
            }
            Integer specialPrice = ShoesContext.getSpecialPrice(modelNo, euSize);
            if (specialPrice != null) {
                return specialPrice;
            }
            Map<String, PoisonPriceDO> sizePriceMap = CACHE.get(modelNo);
            PoisonPriceDO normalPrice = sizePriceMap.get(euSize);
            if (normalPrice == null || ShoesContext.isNotCompareModel(modelNo, euSize) || ShoesContext.isFlawsModel(modelNo, euSize)) {
                return null;
            }
            if (ShoesUtil.isThreeFiveModel(modelNo, euSize)) {
                return ShoesUtil.getThreeFivePrice(normalPrice.getPrice());
            } else {
                return normalPrice.getPrice();
            }
        } catch (ExecutionException e) {
            log.info("getPoisonPrice error", e);
            return null;
        }
    }

    public void putModelNoPrice(String modelNo, List<PoisonPriceDO> poisonPriceDOList) {
        Map<String, PoisonPriceDO> sizePriceMap = poisonPriceDOList.stream()
                .collect(
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                poisonPriceDO -> poisonPriceDO,
                                (existing, replacement) -> existing
                        )
                );
        CACHE.put(modelNo, sizePriceMap);
    }

    public void importPrice(Map<String, Map<String, PoisonPriceDO>> modelNoPriceMap) {
        CACHE.putAll(modelNoPriceMap);
    }

    /**
     * 批量预加载缺失的价格到缓存
     * 1. 遍历货号集合，找出缓存中不存在的货号
     * 2. 批量调用接口查询缺失货号的价格
     * 3. 更新缓存（包括设置空缓存，避免重复查询）
     * 4. 对于形如 xxx/xxxx 的货号，先查前半部分，查不到再查后半部分
     */
    public void preloadMissingPrices(Set<String> modelNos) {
        if (modelNos == null || modelNos.isEmpty()) {
            return;
        }
        // 找出缓存中不存在的货号
        Set<String> missingModelNos = modelNos.stream()
                .filter(modelNo -> CACHE.getIfPresent(modelNo) == null)
                .collect(Collectors.toSet());
        if (missingModelNos.isEmpty()) {
            return;
        }
        log.info("preloadMissingPrices, total:{}, missing:{}", modelNos.size(), missingModelNos.size());

        // 分离普通货号和带 "/" 的货号
        Set<String> normalModelNos = new java.util.HashSet<>();
        // 原始货号 -> 前半部分
        Map<String, String> slashModelFirstPart = new java.util.HashMap<>();
        // 原始货号 -> 后半部分
        Map<String, String> slashModelSecondPart = new java.util.HashMap<>();

        for (String modelNo : missingModelNos) {
            if (modelNo.contains("/")) {
                String[] parts = modelNo.split("/", 2);
                slashModelFirstPart.put(modelNo, parts[0].trim());
                slashModelSecondPart.put(modelNo, parts[1].trim());
            } else {
                normalModelNos.add(modelNo);
            }
        }

        // 合并需要查询的货号：普通货号 + 带"/"货号的前半部分
        Set<String> firstBatchQuery = new java.util.HashSet<>(normalModelNos);
        firstBatchQuery.addAll(slashModelFirstPart.values());

        // 批量查询第一批货号，每200个一批
        List<PoisonPriceDO> poisonPriceDOList = batchQueryPrices(new ArrayList<>(firstBatchQuery));

        // 按货号分组
        Map<String, Map<String, PoisonPriceDO>> modelNoPriceMap = poisonPriceDOList.stream()
                .collect(Collectors.groupingBy(
                        PoisonPriceDO::getModelNo,
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                Function.identity(),
                                (existing, replacement) -> existing
                        )
                ));

        // 处理普通货号的缓存
        for (String modelNo : normalModelNos) {
            Map<String, PoisonPriceDO> priceMap = modelNoPriceMap.get(modelNo);
            CACHE.put(modelNo, priceMap != null ? priceMap : Map.of());
        }

        // 处理带 "/" 的货号
        Set<String> needSecondQuery = new java.util.HashSet<>();
        for (String originalModelNo : slashModelFirstPart.keySet()) {
            String firstPart = slashModelFirstPart.get(originalModelNo);
            Map<String, PoisonPriceDO> priceMap = modelNoPriceMap.get(firstPart);
            if (priceMap != null && !priceMap.isEmpty()) {
                // 前半部分有结果，缓存到原始货号
                CACHE.put(originalModelNo, priceMap);
            } else {
                // 前半部分没有结果，需要查询后半部分
                needSecondQuery.add(originalModelNo);
            }
        }

        // 查询需要用后半部分的货号
        if (!needSecondQuery.isEmpty()) {
            List<String> secondPartList = needSecondQuery.stream()
                    .map(slashModelSecondPart::get)
                    .collect(Collectors.toList());

            List<PoisonPriceDO> secondBatchResult = batchQueryPrices(secondPartList);
            Map<String, Map<String, PoisonPriceDO>> secondPriceMap = secondBatchResult.stream()
                    .collect(Collectors.groupingBy(
                            PoisonPriceDO::getModelNo,
                            Collectors.toMap(
                                    PoisonPriceDO::getEuSize,
                                    Function.identity(),
                                    (existing, replacement) -> existing
                            )
                    ));

            for (String originalModelNo : needSecondQuery) {
                String secondPart = slashModelSecondPart.get(originalModelNo);
                Map<String, PoisonPriceDO> priceMap = secondPriceMap.get(secondPart);
                CACHE.put(originalModelNo, priceMap != null ? priceMap : Map.of());
            }
        }
    }

    /**
     * 批量查询价格，每200个一批
     */
    private List<PoisonPriceDO> batchQueryPrices(List<String> modelNoList) {
        List<PoisonPriceDO> result = new ArrayList<>();
        int batchSize = 200;
        for (int i = 0; i < modelNoList.size(); i += batchSize) {
            List<String> batch = modelNoList.subList(i, Math.min(i + batchSize, modelNoList.size()));
            List<PoisonPriceDO> batchResult = poisonClient.batchQueryPrice(batch);
            if (batchResult != null) {
                result.addAll(batchResult);
            }
        }
        return result;
    }

    public void dumpPrice() {
        synchronized (PriceManager.class) {
            long start = System.currentTimeMillis();
            List<PoisonPriceDO> toInsert = new ArrayList<>();
            ConcurrentMap<String, Map<String, PoisonPriceDO>> map = CACHE.asMap();
            log.info("start dumpPoisonPrice, modelCnt:{}", map.size());
            for (Map<String, PoisonPriceDO> value : map.values()) {
                toInsert.addAll(value.values());
            }
            poisonPriceMapper.delete(new QueryWrapper<>());
            SqlHelper.batch(toInsert, poisonPriceDO -> poisonPriceMapper.insertOverwrite(poisonPriceDO));
            log.info("end dumpPoisonPrice, cnt:{}, cost:{}", toInsert.size(), TimeUtil.getCostMin(start));
        }
    }
}
