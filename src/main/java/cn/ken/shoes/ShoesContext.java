package cn.ken.shoes;

import cn.ken.shoes.model.entity.SizeChartDO;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShoesContext {

    @Getter
    private static Map<String, List<SizeChartDO>> brandSizeChartMap = new HashMap<>();

    public static void setBrandSizeChartMap(Map<String, List<SizeChartDO>> brandSizeChartMap) {
        ShoesContext.brandSizeChartMap = brandSizeChartMap;
    }
}
