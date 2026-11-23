package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayProfitRuleExcel;
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
import java.util.function.Function;

/** eBay 盈利改价 / 活动改价（含广告费）：默认目标盈利 + 可选的按货号目标盈利 Excel。 */
@RestController
@RequestMapping("task/ebay")
public class EbayProfitPriceController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private final EbayProfitPriceService profitPriceService;

    public EbayProfitPriceController(EbayProfitPriceService profitPriceService) {
        this.profitPriceService = profitPriceService;
    }

    /**
     * 盈利改价：运费按 AG/非AG 各 5 个尺码段（逗号分隔），目标盈利按得物价阈值分两档；
     * 可选广告费率调整、低价引流，以及「货号或物品编号/尺码/目标盈利或不动」表格。
     */
    @PostMapping("startProfitPrice")
    public Result<String> startProfitPrice(@RequestParam("intervalHours") long intervalHours,
                                           @RequestParam("agFreights") List<BigDecimal> agFreights,
                                           @RequestParam("nonAgFreights") List<BigDecimal> nonAgFreights,
                                           @RequestParam("profitThreshold") BigDecimal profitThreshold,
                                           @RequestParam("profitLow") BigDecimal profitLow,
                                           @RequestParam("profitHigh") BigDecimal profitHigh,
                                           @RequestParam(value = "adRateAdjust", required = false) BigDecimal adRateAdjust,
                                           @RequestParam(value = "leadMinSkuCount", required = false) Integer leadMinSkuCount,
                                           @RequestParam(value = "leadProfit", required = false) BigDecimal leadProfit,
                                           @RequestParam(value = "file", required = false) MultipartFile file) {
        return startWithOverrides(file, EbayProfitRuleExcel.class, rows -> profitPriceService.start(intervalHours,
                EbayProfitPriceService.Rules.of(agFreights, nonAgFreights, profitThreshold, profitLow, profitHigh,
                        adRateAdjust, leadMinSkuCount, leadProfit, rows)));
    }

    /** eBay 活动改价：在盈利改价基础上，到手金额再扣卖家承担的折扣补贴（标价 × 折扣% × 卖家承担%）。 */
    @PostMapping("startPromoProfitPrice")
    public Result<String> startPromoProfitPrice(@RequestParam("intervalHours") long intervalHours,
                                                @RequestParam("defaultProfit") BigDecimal defaultProfit,
                                                @RequestParam("freight") BigDecimal freight,
                                                @RequestParam("discountPercent") BigDecimal discountPercent,
                                                @RequestParam("sellerSharePercent") BigDecimal sellerSharePercent,
                                                @RequestParam(value = "file", required = false) MultipartFile file) {
        return startWithOverrides(file, EbayProfitTargetExcel.class, rows -> profitPriceService.startPromo(intervalHours,
                defaultProfit, freight, discountPercent, sellerSharePercent, rows));
    }

    private <T> Result<String> startWithOverrides(MultipartFile file, Class<T> head, Function<List<T>, Long> starter) {
        List<T> rows = List.of();
        if (file != null && !file.isEmpty()) {
            try {
                validateFile(file);
                try (InputStream input = file.getInputStream()) {
                    rows = EasyExcel.read(input).head(head).sheet().doReadSync();
                }
            } catch (IllegalArgumentException e) {
                return Result.buildError(e.getMessage());
            } catch (Exception e) {
                return Result.buildError("Excel读取失败，请确认使用了eBay盈利改价模板");
            }
        }
        try {
            Long taskId = starter.apply(rows);
            if (taskId == null) {
                return Result.buildError("eBay盈利改价、活动改价、定时改价任务互斥，已有改价任务正在运行，请先停止");
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

    @GetMapping("profitRuleTemplate")
    public void downloadRuleTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay盈利改价表格模板", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            writer.write(List.of(), EasyExcel.writerSheet(0, "目标盈利").head(EbayProfitRuleExcel.class).build());
            List<List<String>> guide = List.of(
                    List.of("字段", "说明"),
                    List.of("货号或物品编号", "必填；可填货号、eBay物品编号或粘贴商品链接"),
                    List.of("尺码", "选填；EU尺码，留空表示该货号/链接的全部尺码"),
                    List.of("目标盈利", "必填；人民币数字，或填「" + EbayProfitPriceService.HOLD
                            + "」：改价时完全不碰这些尺码（价格、库存都不改，得物没价也不清库存）"),
                    List.of("匹配优先级", "物品编号+尺码 > 物品编号 > 货号+尺码 > 货号；同一键以最后一行为准"),
                    List.of("盈利优先级", "表格 > 低价引流 > 按得物价阈值的两档盈利"),
                    List.of("利润公式", "利润 = 到手美元 × 汇率 − 得物价 − 运费；利润 > 目标盈利 才算盈利"));
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
