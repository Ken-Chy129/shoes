package cn.ken.shoes.model.excel;

import cn.ken.shoes.model.entity.SizeChartDO;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class SizeChartExcel {

    @ExcelProperty("性别")
    private String gender;

    @ExcelProperty("欧码")
    private String euSize;

    @ExcelProperty("美码")
    private String usSize;

    @ExcelProperty("美码(男)")
    private String menUSSize;

    @ExcelProperty("美码(女)")
    private String womenUSSize;

    @ExcelProperty("英码")
    private String ukSize;

    @ExcelProperty("厘米")
    private String cmSize;

    public static SizeChartExcel from(SizeChartDO sizeChartDO) {
        SizeChartExcel sizeChartExcel = new SizeChartExcel();
        sizeChartExcel.setGender(sizeChartDO.getGender());
        sizeChartExcel.setEuSize(sizeChartDO.getEuSize());
        sizeChartExcel.setUsSize(sizeChartDO.getUsSize());
        sizeChartExcel.setMenUSSize(sizeChartDO.getMenUSSize());
        sizeChartExcel.setWomenUSSize(sizeChartDO.getWomenUSSize());
        sizeChartExcel.setUkSize(sizeChartDO.getUkSize());
        sizeChartExcel.setCmSize(sizeChartDO.getCmSize());
        return sizeChartExcel;
    }
}
