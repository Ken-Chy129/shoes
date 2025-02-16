package cn.ken.shoes.service;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.excel.SizeChartExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileService {

    public static final String FILE_DIR = "file/";

    @Resource
    private SizeChartMapper sizeChartMapper;

    public void doWriteSizeCharExcel() {
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME).build()) {
            Map<String, Map<String, List<SizeChartDO>>> brandGenderSizeChartMap = ShoesContext.getBrandGenderSizeChartMap();
            int i = 0;
            for (var entry : brandGenderSizeChartMap.entrySet()) {
                String brand = entry.getKey().replaceAll("[\\\\/?*$]", "_");
                List<SizeChartExcel> charts =  entry.getValue().values().stream().flatMap(Collection::stream).map(SizeChartExcel::from).collect(Collectors.toList());
                WriteSheet writeSheet = EasyExcel.writerSheet(i++, brand).head(SizeChartExcel.class).build();
                excelWriter.write(charts, writeSheet);
            }
        }
    }

    public List<String> getModelNoFromFile(String filename) {
        List<String> result = new ArrayList<>();
        try {
            // 读取所有行
            List<String> lines = Files.readAllLines(Paths.get(FILE_DIR + filename));

            result.addAll(lines);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return result;
    }

}
