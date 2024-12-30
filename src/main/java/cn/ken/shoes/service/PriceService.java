package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.entity.Item;
import cn.ken.shoes.model.entity.SizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewItem;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.poinson.ItemPrice;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.model.poinson.Sku;
import cn.ken.shoes.model.price.PriceRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PriceService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private KickScrewClient kickScrewClient;

    public Result<List<Item>> queryPriceByCondition(PriceRequest priceRequest) {
        List<Item> result = new ArrayList<>();
        PriceEnum priceType = PriceEnum.from(priceRequest.getPriceType());
        String brand = priceRequest.getBrand();

        return Result.buildSuccess(result);
    }

    public void updateItems() {
        Map<String, Integer> brandSizes = KickScrewContext.brandSizes;
        for (Map.Entry<String, Integer> entry : brandSizes.entrySet()) {
            String brand = entry.getKey();
            Integer total = entry.getValue();
            int page = (int) Math.ceil(total / (double) KickScrewConfig.PAGE_SIZE);
//            for (int i = 1; i <= page; i++) {
//                List<KickScrewItem> kickScrewItems = kickScrewClient.queryItemByBrand(brand, i);
//            }
//            for (KickScrewItem kickScrewItem : kickScrewItems) {
//                String modelNumber = kickScrewItem.getModelNumber();
//                Result<List<PoisonItem>> poisonResult = poisonClient.queryItemByModelNumber(modelNumber);
//                if (poisonResult == null || CollectionUtils.isEmpty(poisonResult.getData())) {
//                    continue;
//                }
//                PoisonItem item = poisonResult.getData().getFirst();
//                for (Sku sku : item.getSkus()) {
//                    Long skuId = sku.getSkuId();
//                    Result<List<ItemPrice>> itemResult = poisonClient.queryLowestPriceBySkuId(skuId, priceType);
//                    if (itemResult == null || CollectionUtils.isEmpty(itemResult.getData())) {
//                        continue;
//                    }
//                    for (ItemPrice price : itemResult.getData()) {
//
//                    }
//                }
//            }

        }
    }

    private SizePrice toSizePrice(KickScrewSizePrice kickScrewSizePrice) {
        SizePrice sizePrice = new SizePrice();
        Map<String, Object> price = kickScrewSizePrice.getPrice();
        sizePrice.setKickScrewPrice((BigDecimal) price.get("amount"));
        String title = kickScrewSizePrice.getTitle();
        return sizePrice;
    }
}
