package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.excel.SizeChartExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ExcelService {

    @Resource
    private SizeChartMapper sizeChartMapper;

    public void doWriteSizeCharExcel() {
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME).build()) {
            Map<String, List<SizeChartDO>> brandSizeChartMap = ShoesContext.getBrandSizeChartMap();
            int i = 0;
            for (Map.Entry<String, List<SizeChartDO>> entry : brandSizeChartMap.entrySet()) {
                String brand = entry.getKey().replaceAll("[\\\\/?*$]", "_");
                List<SizeChartExcel> charts = entry.getValue().stream().map(SizeChartExcel::from).collect(Collectors.toList());
                WriteSheet writeSheet = EasyExcel.writerSheet(i++, brand).head(SizeChartExcel.class).build();
                excelWriter.write(charts, writeSheet);
            }
        }
    }

}
