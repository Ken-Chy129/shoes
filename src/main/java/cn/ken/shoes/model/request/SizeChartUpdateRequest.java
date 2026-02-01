package cn.ken.shoes.model.request;

import lombok.Data;

@Data
public class SizeChartUpdateRequest {

    // 新值
    private String brand;
    private String gender;
    private String euSize;
    private String usSize;
    private String menUSSize;
    private String womenUSSize;
    private String ukSize;
    private String cmSize;
    private String dunkBrand;
    private String stockxBrand;

    // 原始主键值（用于定位记录）
    private String oldBrand;
    private String oldGender;
    private String oldEuSize;
    private String oldUsSize;
}
