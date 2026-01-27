package cn.ken.shoes.service;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.common.CustomPriceTypeEnum;
import cn.ken.shoes.config.CommonConfig;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.mapper.PoisonPriceMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.excel.ModelExcel;
import cn.ken.shoes.model.excel.PriceExcel;
import cn.ken.shoes.model.excel.SizeChartExcel;
import cn.ken.shoes.util.ShoesUtil;
import cn.ken.shoes.util.SizeConvertUtil;
import cn.ken.shoes.util.SqlHelper;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.builder.ExcelWriterSheetBuilder;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FileService {

    public static final String FILE_DIR = "file/";

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private CustomModelMapper customModelMapper;

    @Resource
    private PoisonPriceMapper poisonPriceMapper;

    public void doWriteSizeCharExcel() {
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.SIZE_CHART_NAME).build()) {
            Map<String, Map<String, List<SizeChartDO>>> brandGenderSizeChartMap = SizeConvertUtil.getBrandGenderSizeChartMap();
            int i = 0;
            for (var entry : brandGenderSizeChartMap.entrySet()) {
                String brand = entry.getKey().replaceAll("[\\\\/?*$]", "_");
                List<SizeChartExcel> charts =  entry.getValue().values().stream().flatMap(Collection::stream).map(SizeChartExcel::from).collect(Collectors.toList());
                WriteSheet writeSheet = EasyExcel.writerSheet(i++, brand).head(SizeChartExcel.class).build();
                excelWriter.write(charts, writeSheet);
            }
        }
    }

    public void doWritePoisonPriceExcel() {
        Long count = poisonPriceMapper.count();
        final int pageSize = 10000;
        long startIndex = 0;
        try (ExcelWriter excelWriter = EasyExcel.write(CommonConfig.DOWNLOAD_PATH + CommonConfig.POISON_PRICE_NAME).build()) {
            ExcelWriterSheetBuilder sheetBuilder = EasyExcel.writerSheet(0, "得物价格").head(PoisonPriceDO.class);
            while (startIndex < count) {
                List<PoisonPriceDO> poisonPriceDOList = poisonPriceMapper.selectPage(startIndex, pageSize);
                List<PoisonPriceDO> toInsert = new ArrayList<>();
                for (PoisonPriceDO poisonPriceDO : poisonPriceDOList) {
                    String size = ShoesUtil.getShoesSizeFrom(poisonPriceDO.getEuSize());
                    if (size == null) {
                        continue;
                    }
                    poisonPriceDO.setEuSize(size);
                    toInsert.add(poisonPriceDO);
                }
                WriteSheet writeSheet = sheetBuilder.build();
                excelWriter.write(toInsert, writeSheet);
                startIndex += pageSize;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println(ShoesUtil.getShoesSizeFrom("D宽"));
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
            poisonPriceDO.setPrice(poisonPrice);
            toInsert.add(poisonPriceDO);
        }
        poisonPriceMapper.insert(toInsert);
    }

    public void queryModelNoPriceByExcel(String filename) throws InterruptedException {
        poisonPriceMapper.delete(new QueryWrapper<>());
        List<ModelExcel> priceExcels = EasyExcel.read(FILE_DIR + "1.xlsx")
                .head(ModelExcel.class)
                .sheet() // 默认读取第一个工作表
                .doReadSync();
        List<String> modelNos = priceExcels.stream().map(ModelExcel::getModelNo).toList();
        List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectSpuIdByModelNos(modelNos);
        RateLimiter rateLimiter = RateLimiter.create(6);
        for (List<PoisonItemDO> itemDOS : Lists.partition(poisonItemDOS, 20)) {
            CopyOnWriteArrayList<PoisonPriceDO> toInsert = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(itemDOS.size());
            for (PoisonItemDO itemDO : itemDOS) {
                Thread.startVirtualThread(() -> {
                    try {
                        rateLimiter.acquire();
                        List<PoisonPriceDO> poisonPriceDOList = poisonClient.queryPriceBySpuV2(itemDO.getArticleNumber(), itemDO.getSpuId());
                        Optional.ofNullable(poisonPriceDOList).ifPresent(toInsert::addAll);
                    } catch (Exception e) {
                        log.error(e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            Thread.startVirtualThread(() -> poisonPriceMapper.insert(toInsert));
        }
    }

    public void importFlawsModel(String filename) {
        List<CustomModelDO> toInsert = new ArrayList<>();
        try {
            // 读取所有行
            List<String> modelNos = Files.readAllLines(Paths.get(FILE_DIR + filename));
            for (String modelNo : modelNos) {
                CustomModelDO customModelDO = new CustomModelDO();
                customModelDO.setModelNo(modelNo);
                customModelDO.setType(CustomPriceTypeEnum.FLAWS.getCode());
                toInsert.add(customModelDO);
            }
            SqlHelper.batch(toInsert, customModelDO -> customModelMapper.insertIgnore(customModelDO));
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }
}
