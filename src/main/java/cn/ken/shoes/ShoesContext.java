package cn.ken.shoes;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.entity.SpecialPriceDO;
import lombok.Getter;

import java.util.*;

public class ShoesContext {

    @Getter
    private static Map<String, Map<String, List<SizeChartDO>>> brandGenderSizeChartMap = new HashMap<>();

    private final static Set<String> THREE_FIVE_MODEL_SET = new HashSet<>();

    private final static Set<String> NOT_COMPARE_MODEL_SET = new HashSet<>();

    private final static Set<String> NO_PRICE_MODEL_SET = new HashSet<>();

    private final static Set<String> FLAWS_MODEL_SET = new HashSet<>();

    private final static Map<String, Integer> SPECIAL_PRICE_MAP = new HashMap<>();

    public static void setBrandGenderSizeChartMap(Map<String, Map<String, List<SizeChartDO>>> brandGenderSizeChartMap) {
        ShoesContext.brandGenderSizeChartMap = brandGenderSizeChartMap;
    }

    public static Map<String, List<SizeChartDO>> getBrandSizeChart(String brand) {
        return brandGenderSizeChartMap.get(brand);
    }

    public static List<SizeChartDO> getBrandGenderSizeChart(String brand, String gender) {
        Map<String, List<SizeChartDO>> brandMap = brandGenderSizeChartMap.get(brand);
        if (brandMap == null) {
            return null;
        }
        return brandMap.get(gender);
    }

    // 3.5
    public static void clearThreeFiveModelSet() {
        THREE_FIVE_MODEL_SET.clear();
    }

    public static Set<String> getThreeFiveModelSet() {
        return THREE_FIVE_MODEL_SET;
    }

    public static void addThreeFiveModel(CustomModelDO customModelDO) {
        String key = customModelDO.getModelNo();
        if (StrUtil.isNotBlank(customModelDO.getEuSize())) {
            key = STR."\{customModelDO.getModelNo()}:\{customModelDO.getEuSize()}";
        }
        THREE_FIVE_MODEL_SET.add(key);
    }

    public static boolean isThreeFiveModel(String modelNo, String euSize) {
        return THREE_FIVE_MODEL_SET.contains(modelNo) || THREE_FIVE_MODEL_SET.contains(STR."\{modelNo}:\{euSize}");
    }

    // 不比价
    public static void clearNotCompareModelSet() {
        NOT_COMPARE_MODEL_SET.clear();
    }

    public static Set<String> getNotCompareModelSet() {
        return NOT_COMPARE_MODEL_SET;
    }

    public static void addNotCompareModel(CustomModelDO customModelDO) {
        String modelNo = customModelDO.getModelNo();
        String euSize = customModelDO.getEuSize();
        NOT_COMPARE_MODEL_SET.add(STR."\{modelNo}:\{euSize}");
    }

    public static boolean isNotCompareModel(String modelNo, String euSize) {
        return NOT_COMPARE_MODEL_SET.contains(STR."\{modelNo}:\{euSize}");
    }

    // 无价
    public static void clearNoPriceModelSet() {
        NO_PRICE_MODEL_SET.clear();
    }

    public static Set<String> getNoPriceModelSet() {
        return NO_PRICE_MODEL_SET;
    }

    public static void addNoPrice(CustomModelDO customModelDO) {
        String modelNo = customModelDO.getModelNo();
        NO_PRICE_MODEL_SET.add(modelNo);
    }

    public static void addNoPrice(String modelNo) {
        NO_PRICE_MODEL_SET.add(modelNo);
    }

    public static boolean isNoPrice(String modelNo) {
        if (!PoisonSwitch.OPEN_NO_PRICE_CACHE) {
            return true;
        }
        return NO_PRICE_MODEL_SET.contains(modelNo);
    }

    // 瑕疵

    public static void clearFlawsModelSet() {
        FLAWS_MODEL_SET.clear();
    }

    public static Set<String> getFlawsModelSet() {
        return FLAWS_MODEL_SET;
    }

    public static void addFlawsModel(CustomModelDO customModelDO) {
        String key = customModelDO.getModelNo();
        if (StrUtil.isNotBlank(customModelDO.getEuSize())) {
            key = STR."\{customModelDO.getModelNo()}:\{customModelDO.getEuSize()}";
        }
        FLAWS_MODEL_SET.add(key);
    }

    public static boolean isFlawsModel(String modelNo) {
        return FLAWS_MODEL_SET.contains(modelNo);
    }

    public static boolean isFlawsModel(String modelNo, String euSize) {
        return FLAWS_MODEL_SET.contains(modelNo) || FLAWS_MODEL_SET.contains(STR."\{modelNo}:\{euSize}");
    }

    // 指定特殊价格
    public static void clearSpecialPrice() {
        SPECIAL_PRICE_MAP.clear();
    }

    public static Map<String, Integer> getSpecialPriceMap() {
        return SPECIAL_PRICE_MAP;
    }

    public static void addSpecialPrice(SpecialPriceDO specialPriceDO) {
        String key = STR."\{specialPriceDO.getModelNo()}:\{specialPriceDO.getEuSize()}";
        SPECIAL_PRICE_MAP.put(key, specialPriceDO.getPrice());
    }

    public static Integer getSpecialPrice(String modelNo, String euSize) {
        String key = STR."\{modelNo}:\{euSize}";
        return SPECIAL_PRICE_MAP.get(key);
    }
}
