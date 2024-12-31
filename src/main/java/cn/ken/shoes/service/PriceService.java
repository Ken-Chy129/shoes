package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.ItemSizePriceDO;
import cn.ken.shoes.model.kickscrew.KickScrewItem;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.model.poinson.Sku;
import cn.ken.shoes.model.price.PriceRequest;
import com.alibaba.fastjson.JSON;
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

    public Result<List<ItemDO>> queryPriceByCondition(PriceRequest priceRequest) {
        List<ItemDO> result = new ArrayList<>();
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
            List<KickScrewItem> brandItems = new ArrayList<>();
            // 2.查询品牌下所有商品
            for (int i = 1; i <= page; i++) {
                brandItems.addAll(kickScrewClient.queryItemByBrand(brand, i));
            }
            List<ItemDO> itemDOS = new ArrayList<>();
            // 3.保存商品+价格信息
            for (KickScrewItem kickScrewItem : brandItems) {
                // 创建ItemDO
                ItemDO itemDO = new ItemDO();
                itemDO.setModelNumber(kickScrewItem.getModelNo());
                itemDO.setImage(kickScrewItem.getImage());
                itemDO.setBrandName(kickScrewItem.getBrand());
                itemDO.setProductType(kickScrewItem.getProductType());
                String modelNumber = kickScrewItem.getModelNo();
                PoisonItem poisonItem = poisonClient.queryItemByModelNumber(modelNumber);
                itemDO.setName(poisonItem.getTitle());

                // 查询商品不同尺码的价格
                List<ItemSizePriceDO> itemSizePriceDOS = new ArrayList<>();
                // 查询kc价格
                String handle = kickScrewItem.getHandle();
                List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(handle);
                kickScrewSizePrices.stream().map(this::toSizePrice).forEach(itemSizePriceDOS::add);
                // 查询得物价格
                for (Sku sku : poisonItem.getSkus()) {
                    Long skuId = sku.getSkuId();
                    String size = JSON.parseObject(sku.getProperties()).getString("尺码");
                    ItemSizePriceDO itemSizePriceDO = itemSizePriceDOS.stream().filter(itemSizePrice -> size.equals(itemSizePrice.getEuSize())).findFirst().orElse(null);
                    if (itemSizePriceDO == null) {
                        continue;
                    }
                    itemSizePriceDO.setSkuId(skuId);
                    itemSizePriceDO.setEuSize(size);
                    Integer fastPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.FAST);
                    Integer normalPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.NORMAL);
                    Integer lightningPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.LIGHTNING);
                    itemSizePriceDO.setPoisonFastPrice(BigDecimal.valueOf(fastPrice));
                    itemSizePriceDO.setPoisonNormalPrice(BigDecimal.valueOf(normalPrice));
                    itemSizePriceDO.setPoisonLightningPrice(BigDecimal.valueOf(lightningPrice));
                    itemSizePriceDOS.add(itemSizePriceDO);
                }

            }
            // 4.入库
        }
    }

    private ItemSizePriceDO toSizePrice(KickScrewSizePrice kickScrewSizePrice) {
        ItemSizePriceDO itemSizePriceDO = new ItemSizePriceDO();
        Map<String, Object> price = kickScrewSizePrice.getPrice();
        itemSizePriceDO.setKickScrewPrice((BigDecimal) price.get("amount"));
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
                case MEN_US -> itemSizePriceDO.setMemUSSize(value);
                case WOMAN_US -> itemSizePriceDO.setWomenUSSize(value);
                case EU -> itemSizePriceDO.setEuSize(value);
                case UK -> itemSizePriceDO.setUkSize(value);
                case JP -> itemSizePriceDO.setJpSize(value);
            }
        }
        return itemSizePriceDO;
    }

    public static void main(String[] args) {
        ItemSizePriceDO itemSizePriceDO = new ItemSizePriceDO();
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
                case MEN_US -> itemSizePriceDO.setMemUSSize(value);
                case WOMAN_US -> itemSizePriceDO.setWomenUSSize(value);
                case EU -> itemSizePriceDO.setEuSize(value);
                case UK -> itemSizePriceDO.setUkSize(value);
                case JP -> itemSizePriceDO.setJpSize(value);
            }
        }
        System.out.println(itemSizePriceDO);
    }
}
