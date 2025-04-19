package cn.ken.shoes;

import cn.ken.shoes.common.CustomPriceTypeEnum;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShoesContext {

    @Getter
    private static Map<String, Map<String, List<SizeChartDO>>> brandGenderSizeChartMap = new HashMap<>();

    private static Map<String, CustomPriceTypeEnum> customPriceTypeMap = new HashMap<>();

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

    public static SizeChartDO getBrandGenderSizeChartByEuSize(String brand, String gender, String euSize) {
        List<SizeChartDO> brandGenderSizeChart = getBrandGenderSizeChart(brand, gender);
        if (brandGenderSizeChart == null) {
            return null;
        }
        return brandGenderSizeChart.stream().filter(sizeChartDO -> sizeChartDO.getEuSize().equals(euSize)).findFirst().orElse(null);
    }

    public static void putCustomModel(CustomModelDO customModelDO) {
        String modelNo = customModelDO.getModelNo();
        String euSize = customModelDO.getEuSize();
        CustomPriceTypeEnum customPriceTypeEnum = CustomPriceTypeEnum.from(customModelDO.getType());
        customPriceTypeMap.put(STR."\{modelNo}:\{euSize}", customPriceTypeEnum);
    }

    public static CustomPriceTypeEnum getModelType(String modelNo, String euSize) {
        return customPriceTypeMap.get(STR."\{modelNo}:\{euSize}");
    }
}
