package cn.ken.shoes.service;

import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.util.SizeConvertUtil;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * eBay 后台「重新上架」出来的 listing 里，常有系统早已不再上架、映射表里已经没有的尺码 SKU。
 * SKU 只由货号 + 尺码决定，listing 自身的 SKU 又是系统商品组 key，所以能反推出它属于哪个货号、哪个尺码。
 * 只处理港区。线程不安全，一轮内单线程使用。
 */
final class RelistedSkuResolver {

    static final String DERIVED_STATUS = "relisted_derived";
    private static final String[] SYSTEMS = {"EU", "USM", "USW"};
    /** 尺码按半码枚举：1 到 55。 */
    private static final int MIN_HALF_SIZE = 2;
    private static final int MAX_HALF_SIZE = 110;

    /** 港区商品组 key → 该货号的任一映射（取货号、品牌）。 */
    private final Map<String, EbayListingDO> byGroupKey = new HashMap<>();
    /** 货号 → (尺码 SKU → 规范化尺码)，按需计算。 */
    private final Map<String, Map<String, String>> sizesByStyle = new HashMap<>();

    RelistedSkuResolver(Collection<EbayListingDO> mappings) {
        for (EbayListingDO mapping : mappings) {
            if (mapping.getStyleId() == null || mapping.getStyleId().isBlank()) {
                continue;
            }
            try {
                byGroupKey.putIfAbsent(EbayListingFactory.inventoryGroupKey(mapping.getStyleId().trim(),
                        EbayListingRegion.HK), mapping);
            } catch (IllegalArgumentException ignored) {
                // 货号不合法的映射不参与反推
            }
        }
    }

    static boolean isDerived(EbayListingDO mapping) {
        return mapping != null && DERIVED_STATUS.equals(mapping.getStatus());
    }

    /** 反推不出来（不是系统商品组、尺码不在常见范围、美码换不出欧码）时返回 null。 */
    EbayListingDO resolve(String parentSku, String sku) {
        if (parentSku == null || sku == null) {
            return null;
        }
        EbayListingDO base = byGroupKey.get(parentSku);
        if (base == null) {
            return null;
        }
        String styleId = base.getStyleId().trim();
        String size = sizesByStyle.computeIfAbsent(styleId, RelistedSkuResolver::sizeTable).get(sku);
        if (size == null) {
            return null;
        }
        String euSize = euSize(base.getBrand(), size);
        if (euSize == null) {
            return null;
        }
        EbayListingDO derived = new EbayListingDO();
        derived.setSku(sku);
        derived.setStyleId(styleId);
        derived.setSize(size);
        derived.setEuSize(euSize);
        derived.setTitle(base.getTitle());
        derived.setBrand(base.getBrand());
        derived.setRegion(EbayListingRegion.HK.getCode());
        derived.setStatus(DERIVED_STATUS);
        return derived;
    }

    private static Map<String, String> sizeTable(String styleId) {
        Map<String, String> table = new HashMap<>();
        for (String system : SYSTEMS) {
            for (int half = MIN_HALF_SIZE; half <= MAX_HALF_SIZE; half++) {
                String value = BigDecimal.valueOf(half).divide(BigDecimal.valueOf(2)).stripTrailingZeros()
                        .toPlainString();
                String size = system + value;
                table.putIfAbsent(EbayListingFactory.variantSku(styleId, size, EbayListingRegion.HK), size);
            }
        }
        return table;
    }

    private static String euSize(String brand, String size) {
        if (size.startsWith("EU")) {
            return size.substring(2);
        }
        String system = size.startsWith("USM") ? "USM" : "USW";
        String eu = SizeConvertUtil.getKcEuSizeFromUs(brand, system, size.substring(3));
        return eu == null || eu.isBlank() ? null : eu.trim();
    }
}
