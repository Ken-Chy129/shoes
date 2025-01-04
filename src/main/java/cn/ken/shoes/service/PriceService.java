package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.common.SizeEnum;
import cn.ken.shoes.config.KickScrewConfig;
import cn.ken.shoes.mapper.ItemMapper;
import cn.ken.shoes.mapper.ItemSizePriceMapper;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.ItemSizePriceDO;
import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewSizePrice;
import cn.ken.shoes.model.poinson.PoisonItem;
import cn.ken.shoes.model.poinson.Sku;
import cn.ken.shoes.model.price.PriceRequest;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class PriceService {

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private ItemMapper itemMapper;

    @Resource
    private ItemSizePriceMapper itemSizePriceMapper;

    public Result<List<ItemDO>> queryPriceByCondition(PriceRequest priceRequest) {
        List<ItemDO> result = new ArrayList<>();
        PriceEnum priceType = PriceEnum.from(priceRequest.getPriceType());
        String brand = priceRequest.getBrand();

        return Result.buildSuccess(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public void scratchAndSaveItems() {
//        Map<String, Integer> brandSizes = KickScrewContext.brandSizes;
        Map<String, Integer> brandSizes = Map.of("ANTA", 30);
        // 1.遍历所有品牌
        for (Map.Entry<String, Integer> entry : brandSizes.entrySet()) {
            String brand = entry.getKey();
            Integer total = entry.getValue();
            // 根据品牌的商品数量计算请求的分页次数
            int page = (int) Math.ceil(total / (double) KickScrewConfig.PAGE_SIZE);
            List<KickScrewItemDO> brandItems = new ArrayList<>();
            // 2.查询品牌下所有商品
            for (int i = 1; i <= page; i++) {
                brandItems.addAll(kickScrewClient.queryItemByBrand(brand, i));
            }
            // 3.保存商品+价格信息
//            for (KickScrewItemDO kickScrewItemDO : brandItems) {
//                // 创建ItemDO
//                ItemDO itemDO = new ItemDO();
//                itemDO.setModelNumber(kickScrewItemDO.getModelNo());
//                itemDO.setImage(kickScrewItemDO.getImage());
//                itemDO.setBrandName(kickScrewItemDO.getBrand());
//                itemDO.setProductType(kickScrewItemDO.getProductType());
//                String modelNumber = kickScrewItemDO.getModelNo();
//                PoisonItem poisonItem = poisonClient.queryItemByModelNumber(modelNumber);
//                itemDO.setName(poisonItem.getTitle());
//                itemMapper.insert(itemDO);
//                // 查询商品不同尺码的价格
//                List<ItemSizePriceDO> itemSizePriceDOS = new ArrayList<>();
//                // 查询kc价格
//                String handle = kickScrewItemDO.getHandle();
//                List<KickScrewSizePrice> kickScrewSizePrices = kickScrewClient.queryItemSizePrice(handle);
//                kickScrewSizePrices.stream()
//                        .map(this::toSizePrice)
//                        .forEach(itemSizePriceDO -> {
//                            itemSizePriceDO.setModelNumber(modelNumber);
//                            itemSizePriceDOS.add(itemSizePriceDO);
//                        });
//                // 查询得物价格
//                for (Sku sku : poisonItem.getSkus()) {
//                    Long skuId = sku.getSkuId();
//                    String size = JSON.parseObject(sku.getProperties()).getString("尺码");
//                    ItemSizePriceDO itemSizePriceDO = itemSizePriceDOS.stream().filter(itemSizePrice -> size.equals(itemSizePrice.getEuSize())).findFirst().orElse(null);
//                    if (itemSizePriceDO == null) {
//                        continue;
//                    }
//                    itemSizePriceDO.setModelNumber(modelNumber);
//                    itemSizePriceDO.setSkuId(skuId);
//                    itemSizePriceDO.setEuSize(size);
//                    Integer fastPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.FAST);
//                    Integer normalPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.NORMAL);
//                    Integer lightningPrice = poisonClient.queryLowestPriceBySkuId(skuId, PriceEnum.LIGHTNING);
//                    Optional.ofNullable(fastPrice).ifPresent(price -> itemSizePriceDO.setPoisonFastPrice(BigDecimal.valueOf(price)));
//                    Optional.ofNullable(normalPrice).ifPresent(price -> itemSizePriceDO.setPoisonNormalPrice(BigDecimal.valueOf(price)));
//                    Optional.ofNullable(lightningPrice).ifPresent(price -> itemSizePriceDO.setPoisonLightningPrice(BigDecimal.valueOf(price)));
//                    itemSizePriceDOS.add(itemSizePriceDO);
//                }
//                System.out.println(JSON.toJSONString(itemSizePriceDOS));
//                itemSizePriceMapper.insert(itemSizePriceDOS);
//            }
        }
    }

    private ItemSizePriceDO toSizePrice(KickScrewSizePrice kickScrewSizePrice) {
        ItemSizePriceDO itemSizePriceDO = new ItemSizePriceDO();
        Map<String, String> price = kickScrewSizePrice.getPrice();
        itemSizePriceDO.setKickScrewPrice(BigDecimal.valueOf(Double.parseDouble(price.get("amount"))));
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
                case MEN_US -> itemSizePriceDO.setMenUSSize(value);
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
                case MEN_US -> itemSizePriceDO.setMenUSSize(value);
                case WOMAN_US -> itemSizePriceDO.setWomenUSSize(value);
                case EU -> itemSizePriceDO.setEuSize(value);
                case UK -> itemSizePriceDO.setUkSize(value);
                case JP -> itemSizePriceDO.setJpSize(value);
            }
        }
        System.out.println(itemSizePriceDO);
    }
}
