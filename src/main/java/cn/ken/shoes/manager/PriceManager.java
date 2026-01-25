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
                .expireAfterWrite(20, TimeUnit.HOURS) // 设置写入后过期时间
                .build(new CacheLoader<>() {
                    @NonNull
                    @Override
                    public Map<String, PoisonPriceDO> load(@NonNull String key) {
                        return loadPrice(key);
                    }
                }); // 加载逻辑

    @NonNull
    public Map<String, PoisonPriceDO> loadPrice(String modelNo) {
        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceByModelNo(modelNo);
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
        // 批量查询缺失货号的价格，每200个一批
        List<String> missingList = new ArrayList<>(missingModelNos);
        List<PoisonPriceDO> poisonPriceDOList = new ArrayList<>();
        int batchSize = 200;
        for (int i = 0; i < missingList.size(); i += batchSize) {
            List<String> batch = missingList.subList(i, Math.min(i + batchSize, missingList.size()));
            List<PoisonPriceDO> batchResult = poisonClient.batchQueryPrice(batch);
            if (batchResult != null) {
                poisonPriceDOList.addAll(batchResult);
            }
        }
        // 按货号分组并更新缓存
        Map<String, Map<String, PoisonPriceDO>> modelNoPriceMap = poisonPriceDOList.stream()
                .collect(Collectors.groupingBy(
                        PoisonPriceDO::getModelNo,
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                Function.identity(),
                                (existing, replacement) -> existing
                        )
                ));
        CACHE.putAll(modelNoPriceMap);
        // 对于查询后仍然没有价格的货号，设置空缓存，避免每次重新查询
        Set<String> foundModelNos = modelNoPriceMap.keySet();
        for (String modelNo : missingModelNos) {
            if (!foundModelNos.contains(modelNo)) {
                CACHE.put(modelNo, Map.of());
            }
        }
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
