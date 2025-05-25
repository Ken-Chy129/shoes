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
                                Function.identity()
                        )
                );
    }

    public Integer getPoisonPrice(String modelNo, String euSize) {
        try {
            Map<String, PoisonPriceDO> sizePriceMap = CACHE.get(modelNo);
            PoisonPriceDO normalPrice = sizePriceMap.get(euSize);
            if (normalPrice == null || ShoesContext.isNotCompareModel(modelNo, euSize) || ShoesContext.isFlawsModel(modelNo, euSize)) {
                return null;
            }
            if (ShoesContext.isThreeFiveModel(modelNo, euSize)) {
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
                                poisonPriceDO -> poisonPriceDO
                        )
                );
        CACHE.put(modelNo, sizePriceMap);
    }

    public void importPrice(Map<String, Map<String, PoisonPriceDO>> modelNoPriceMap) {
        CACHE.putAll(modelNoPriceMap);
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
