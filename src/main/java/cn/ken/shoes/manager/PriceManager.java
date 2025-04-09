package cn.ken.shoes.manager;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.MustCrawlMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.MustCrawlDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.service.PoisonService;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class PriceManager {

    private static final String EMPTY_DATA = "EMPTY";

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonService poisonService;

    @Resource
    private MustCrawlMapper mustCrawlMapper;

    private final LoadingCache<String, String> CACHE = CacheBuilder.newBuilder()
            .maximumSize(100000) // 设置最大容量
                .expireAfterWrite(10, TimeUnit.HOURS) // 设置写入后过期时间
                .build(new CacheLoader<>() {
        @NonNull
        @Override
        public String load(@NonNull String key) {
            return loadPrice(key);
        }
    }); // 加载逻辑

    @NonNull
    public String loadPrice(String key) {
        String[] split = key.split(":");
        String modelNumber = split[0];
        String euSize = split[1];
        Integer price = poisonPriceMapper.selectPriceByModelNoAndSize(modelNumber, euSize);
        if (price == null) {
            PoisonItemDO poisonItemDO = poisonService.selectItemByModelNo(modelNumber);
            // 查询不到商品
            if (poisonItemDO == null) {
                return EMPTY_DATA;
            }
            // 加入必爬商品，下次跑批自动会更新价格
            MustCrawlDO mustCrawlDO = new MustCrawlDO();
            mustCrawlDO.setPlatform("stockx");
            mustCrawlDO.setModelNo(modelNumber);
            mustCrawlMapper.insertIgnore(mustCrawlDO);
            List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(poisonItemDO.getArticleNumber(), poisonItemDO.getSpuId());
            return poisonPriceDOList.stream().filter(poisonPriceDO -> euSize.equals(poisonPriceDO.getEuSize()))
                    .findFirst()
                    .map(poisonPriceDO -> String.valueOf(poisonPriceDO.getPrice()))
                    .orElse(EMPTY_DATA);
        }
        return String.valueOf(price);
    }

    public String getPrice(String modelNo, String euSize) {
        try {
            return CACHE.get(STR."\{modelNo}:\{euSize}");
        } catch (ExecutionException e) {
            return null;
        }
    }
}
