package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayProfitTargetExcel;
import cn.ken.shoes.service.EbayProfitPriceService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** eBay 盈利改价（含广告费）：默认目标盈利 + 可选的按货号目标盈利 Excel。 */
@RestController
@RequestMapping("task/ebay")
public class EbayProfitPriceController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private final EbayProfitPriceService profitPriceService;

    public EbayProfitPriceController(EbayProfitPriceService profitPriceService) {
        this.profitPriceService = profitPriceService;
    }

    @PostMapping("startProfitPrice")
    public Result<String> startProfitPrice(@RequestParam("intervalHours") long intervalHours,
                                           @RequestParam("defaultProfit") BigDecimal defaultProfit,
                                           @RequestParam("freight") BigDecimal freight,
                                           @RequestParam(value = "file", required = false) MultipartFile file) {
        List<EbayProfitTargetExcel> rows = List.of();
        if (file != null && !file.isEmpty()) {
            try {
                validateFile(file);
                try (InputStream input = file.getInputStream()) {
                    rows = EasyExcel.read(input).head(EbayProfitTargetExcel.class).sheet().doReadSync();
                }
            } catch (IllegalArgumentException e) {
                return Result.buildError(e.getMessage());
            } catch (Exception e) {
                return Result.buildError("Excel读取失败，请确认使用了eBay盈利改价模板");
            }
        }
        try {
            Long taskId = profitPriceService.start(intervalHours, defaultProfit, freight, rows);
            if (taskId == null) {
                return Result.buildError("eBay盈利改价或eBay定时改价任务正在运行，两者互斥，请先停止正在运行的任务");
            }
            return Result.buildSuccess(String.valueOf(taskId));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @GetMapping("profitTargetTemplate")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay盈利改价目标盈利模板", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet inputSheet = EasyExcel.writerSheet(0, "目标盈利").head(EbayProfitTargetExcel.class).build();
            writer.write(List.of(), inputSheet);
            List<List<String>> guide = List.of(
                    List.of("字段", "说明"),
                    List.of("货号", "必填；该货号下所有尺码都使用这一行的目标盈利"),
                    List.of("目标盈利", "必填；人民币，优先级高于任务的默认目标盈利"),
                    List.of("利润公式", "利润 = 到手美元 × 汇率 − 得物价 − 运费；利润 > 目标盈利 才算盈利"),
                    List.of("不在表里的货号", "使用任务的默认目标盈利"));
            writer.write(guide, EasyExcel.writerSheet(1, "填写说明").build());
        }
    }

    private static void validateFile(MultipartFile file) {
        if (file.getSize() > MAX_EXCEL_SIZE) {
            throw new IllegalArgumentException("Excel文件不能超过10MB");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            throw new IllegalArgumentException("仅支持.xlsx或.xls格式的Excel文件");
        }
    }
}
