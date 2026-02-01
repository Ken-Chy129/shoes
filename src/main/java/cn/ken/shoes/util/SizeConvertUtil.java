package cn.ken.shoes.util;

import cn.ken.shoes.common.Gender;
import cn.ken.shoes.common.PlatformEnum;
import cn.ken.shoes.model.entity.SizeChartDO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 尺码转换工具类
 * 根据品牌名和尺码（美码）转换为欧码
 */
@Slf4j
public class SizeConvertUtil {

    /**
     * 缓存Map，key: stockxBrand:gender:usSize，value: euSize
     */
    private static final Map<String, String> STOCKX_SIZE_CACHE = new HashMap<>();

    /**
     * Dunk品牌尺码缓存，key: dunkBrand:gender:cmSize，value: euSize
     */
    private static final Map<String, String> DUNK_SIZE_CACHE = new HashMap<>();

    /**
     * 品牌-性别-尺码对照表缓存
     */
    @Getter
    private static final Map<String, Map<String, List<SizeChartDO>>> KC_SIZE_CACHE = new HashMap<>();

    /**
     * 初始化尺码转换缓存
     * 应在应用启动时调用
     *
     * @param sizeChartList 尺码对照表数据
     */
    public static void initCache(List<SizeChartDO> sizeChartList) {
        STOCKX_SIZE_CACHE.clear();
        DUNK_SIZE_CACHE.clear();
        KC_SIZE_CACHE.clear();

        for (SizeChartDO sizeChart : sizeChartList) {
            String kcBrand = sizeChart.getBrand();
            String stockxBrand = sizeChart.getStockxBrand();
            String dunkBrand = sizeChart.getDunkBrand();
            String gender = sizeChart.getGender();
            String euSize = sizeChart.getEuSize();
            String usSize = sizeChart.getUsSize();
            String cmSize = sizeChart.getCmSize();

            // 构建 brandGenderSizeChartMap
            KC_SIZE_CACHE
                    .computeIfAbsent(kcBrand, k -> new HashMap<>())
                    .computeIfAbsent(gender, k -> new ArrayList<>())
                    .add(sizeChart);

            // 通用 usSize
            if (usSize != null && !usSize.isEmpty()) {
                String key = buildKey(stockxBrand, gender, usSize);
                STOCKX_SIZE_CACHE.put(key, euSize);
            }

            // 男款 usSize
            String menUSSize = sizeChart.getMenUSSize();
            if (menUSSize != null && !menUSSize.isEmpty()) {
                String key = buildKey(stockxBrand, Gender.MENS.name(), menUSSize);
                STOCKX_SIZE_CACHE.putIfAbsent(key, euSize);
            }

            // 女款 usSize
            String womenUSSize = sizeChart.getWomenUSSize();
            if (womenUSSize != null && !womenUSSize.isEmpty()) {
                String key = buildKey(stockxBrand, Gender.WOMENS.name(), womenUSSize);
                STOCKX_SIZE_CACHE.putIfAbsent(key, euSize);
            }

            // Dunk品牌 cmSize -> euSize
            if (dunkBrand != null && !dunkBrand.isEmpty() && cmSize != null && !cmSize.isEmpty()) {
                String dunkKey = buildKey(dunkBrand, gender, cmSize);
                DUNK_SIZE_CACHE.putIfAbsent(dunkKey, euSize);
            }
        }
    }

    /**
     * 根据品牌和尺码获取欧码
     *
     * @param brand 品牌名
     * @param size  尺码（美码），支持格式：10、10.5、10W、10Y、10C
     * @return 欧码，找不到返回null
     */
    public static String getStockXEuSize(String brand, String size) {
        if (brand == null || size == null || size.isEmpty()) {
            return null;
        }

        // 提取性别
        Gender gender = GenderUtil.extractGender(PlatformEnum.STOCKX, size);


        String key = buildKey(brand, gender.name(), size);
        String euSize = STOCKX_SIZE_CACHE.get(key);

        if (euSize == null && gender == Gender.BABY) {
            String kidsKey = buildKey(brand, Gender.KIDS.name(), size);
            return STOCKX_SIZE_CACHE.get(kidsKey);
        }

        return euSize;
    }

    /**
     * 根据Dunk品牌、性别和CM尺码获取欧码
     *
     * @param dunkBrand Dunk品牌名
     * @param gender    性别
     * @param cmSize    CM尺码（如 "27cm" 或 "27"）
     * @return 欧码，找不到返回null
     */
    public static String getDunkEuSize(String dunkBrand, Gender gender, String cmSize) {
        if (dunkBrand == null || gender == null || cmSize == null || cmSize.isEmpty()) {
            return null;
        }
        String finalCmSize = cmSize.replace("cm", "").trim();
        String key = buildKey(dunkBrand, gender.name(), finalCmSize);
        return DUNK_SIZE_CACHE.get(key);
    }

    /**
     * 构建缓存key
     */
    private static String buildKey(String brand, String gender, String usSize) {
        return STR."\{brand}:\{gender}:\{usSize}";
    }

}
