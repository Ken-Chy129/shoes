package cn.ken.shoes.util;

import cn.ken.shoes.model.entity.SizeChartDO;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 品牌提取工具类
 * 从商品名称中提取品牌名
 */
public class BrandUtil {

    /**
     * 品牌名缓存
     */
    private static final Set<String> STOCKX_BRAND_CACHE = new HashSet<>();

    /**
     * 品牌名映射（特殊处理，如 Jordan -> Nike）
     */
    private static final Map<String, String> BRAND_ALIAS_MAP = Map.of(
            "Jordan", "Nike"
    );

    /**
     * 初始化品牌缓存
     * 应在应用启动时调用
     *
     * @param sizeChartList 尺码对照表数据
     */
    public static void initCache(List<SizeChartDO> sizeChartList) {
        STOCKX_BRAND_CACHE.clear();
        for (SizeChartDO sizeChart : sizeChartList) {
            String brand = sizeChart.getStockxBrand();
            if (brand != null && !brand.isEmpty()) {
                STOCKX_BRAND_CACHE.add(brand);
            }
        }
    }

    /**
     * 从商品名称中提取品牌名
     * 通过前缀匹配方式查找品牌
     *
     * @param productName 商品名称
     * @return 品牌名，找不到返回null
     */
    public static String extractStockXBrand(String productName) {
        if (productName == null || productName.isEmpty()) {
            return null;
        }

        String[] words = productName.split("\\s+");
        if (words.length == 0) {
            return null;
        }

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                prefix.append(" ");
            }
            prefix.append(words[i]);

            String currentPrefix = prefix.toString();
            if (STOCKX_BRAND_CACHE.contains(currentPrefix)) {
                return applyAlias(currentPrefix);
            }
        }

        return null;
    }

    /**
     * 应用品牌别名映射
     *
     * @param brand 原始品牌名
     * @return 映射后的品牌名
     */
    private static String applyAlias(String brand) {
        return BRAND_ALIAS_MAP.getOrDefault(brand, brand);
    }

    /**
     * 获取所有品牌名
     *
     * @return 品牌名集合的副本
     */
    public static Set<String> getAllStockXBrands() {
        return new HashSet<>(STOCKX_BRAND_CACHE);
    }
}
