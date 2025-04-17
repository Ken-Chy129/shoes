package cn.ken.shoes.manager;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.MustCrawlDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PriceManager {

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    private final LoadingCache<String, Map<String, Integer>> CACHE = CacheBuilder.newBuilder()
            .maximumSize(100000) // 设置最大容量
                .expireAfterWrite(10, TimeUnit.HOURS) // 设置写入后过期时间
                .build(new CacheLoader<>() {
                    @NonNull
                    @Override
                    public Map<String, Integer> load(@NonNull String key) {
                        return loadPrice(key);
                    }
                }); // 加载逻辑

    @NonNull
    public Map<String, Integer> loadPrice(String modelNo) {
        List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectListByModelNos(Set.of(modelNo));
        if (CollectionUtils.isEmpty(poisonPriceDOList)) {
            // 加入必爬商品，下次跑批自动会更新价格
            MustCrawlDO mustCrawlDO = new MustCrawlDO();
            mustCrawlDO.setPlatform("stockx");
            mustCrawlDO.setModelNo(modelNo);
            mustCrawlMapper.insertIgnore(mustCrawlDO);
            poisonPriceDOList = poisonClient.queryPriceByModelNo(modelNo);
        }
        return poisonPriceDOList.stream()
                .collect(
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                PoisonPriceDO::getPrice
                        )
                );
    }

    public Integer getPoisonPrice(String modelNo, String euSize) {
        try {
            Map<String, Integer> sizePriceMap = CACHE.get(modelNo);
            return sizePriceMap.get(euSize);
        } catch (ExecutionException e) {
            return null;
        }
    }

    public void putModelPrice(String modelNo, List<PoisonPriceDO> poisonPriceDOList) {
        Map<String, Integer> sizePriceMap = poisonPriceDOList.stream()
                .collect(
                        Collectors.toMap(
                                PoisonPriceDO::getEuSize,
                                PoisonPriceDO::getPrice
                        )
                );
        CACHE.put(modelNo, sizePriceMap);
    }
}
