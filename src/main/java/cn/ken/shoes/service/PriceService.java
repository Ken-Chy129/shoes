package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.entity.Item;
import cn.ken.shoes.model.entity.SizePrice;
import cn.ken.shoes.model.kickscrew.KickScrewItem;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.price.PriceRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
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
        // 1.遍历所有品牌
        for (Map.Entry<String, Integer> entry : brandSizes.entrySet()) {
            String brand = entry.getKey();
            Integer total = entry.getValue();
            // 根据品牌的商品数量计算请求的分页次数
            int page = (int) Math.ceil(total / (double) KickScrewConfig.PAGE_SIZE);
            for (int i = 1; i <= page; i++) {
                List<KickScrewItem> kickScrewItems = kickScrewClient.queryItemByBrand(brand, i);
            }
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
        List<String> sizeList = Arrays.stream(title.split("/")).map(String::trim).toList();
        for (String size : sizeList) {
            String[] split = size.split(" ");
            if (split.length < 2) {
                continue;
            }
            String sizeType = split[0];
            String value = split.length > 2 ? split[2] : split[1];
            switch (SizeEnum.from(sizeType)) {
                case MEN_US -> sizePrice.setMemUSSize(value);
                case WOMAN_US -> sizePrice.setWomenUSSize(value);
                case EU -> sizePrice.setEuSize(value);
                case UK -> sizePrice.setUkSize(value);
                case JP -> sizePrice.setJpSize(value);
            }
        }

        return sizePrice;
    }

    public static void main(String[] args) {
        SizePrice sizePrice = new SizePrice();
        List<String> sizeList = Arrays.stream("MENS / Men's US 6.5 / Women's US 8 / UK 6 / EU 39 / JP 24.5".split("/")).map(String::trim).toList();
        for (String size : sizeList) {
            System.out.println(size);
            String[] split = size.split(" ");
            System.out.println(Arrays.toString(split));
            if (split.length < 2) {
                continue;
            }
            String sizeType = split[0];
            String value = split.length > 2 ? split[2] : split[1];
            switch (SizeEnum.from(sizeType)) {
                case MEN_US -> sizePrice.setMemUSSize(value);
                case WOMAN_US -> sizePrice.setWomenUSSize(value);
                case EU -> sizePrice.setEuSize(value);
                case UK -> sizePrice.setUkSize(value);
                case JP -> sizePrice.setJpSize(value);
            }
        }
        System.out.println(sizePrice);
    }
}
