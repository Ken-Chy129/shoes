package cn.ken.shoes.service;

import cn.hutool.core.lang.Pair;
import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.excel.PriceExcel;
import cn.ken.shoes.model.excel.SizeChartExcel;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

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

    public void updatePoisonPriceByExcel(String filename) {
        poisonPriceMapper.delete(new QueryWrapper<>());
        List<PriceExcel> priceExcels = EasyExcel.read(FILE_DIR + filename)
                .head(PriceExcel.class)
                .sheet() // 默认读取第一个工作表
                .doReadSync();
        List<PoisonPriceDO> toInsert = new ArrayList<>();
        for (PriceExcel priceExcel : priceExcels) {
            if (priceExcel.getPoisonPrice() == 0) {
                continue;
            }
            PoisonPriceDO poisonPriceDO = new PoisonPriceDO();
            poisonPriceDO.setEuSize(priceExcel.getEuSize());
            poisonPriceDO.setModelNo(priceExcel.getModelNo());
            int poisonPrice = priceExcel.getPoisonPrice() * 100;
            poisonPriceDO.setNormalPrice(poisonPrice);
            poisonPriceDO.setLightningPrice(poisonPrice);
            toInsert.add(poisonPriceDO);
        }
        poisonPriceMapper.insert(toInsert);
    }

}
