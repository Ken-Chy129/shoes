package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayItemIdExcel;
import cn.ken.shoes.model.excel.EbaySetPriceExcel;
import cn.ken.shoes.service.EbayListingManageService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** eBay 刊登管理：获取刊登、按编号下架、按编号重新上架、指定尺码改价。 */
@RestController
@RequestMapping("task/ebay")
public class EbayListingManageController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private static final String BUSY = "同类任务已在运行，请等待当前任务结束";

    private final EbayListingManageService service;

    public EbayListingManageController(EbayListingManageService service) {
        this.service = service;
    }

    /** body: scope = active(在售，默认) / ended(最近119天已结束) / all；viewDays = 浏览人数统计天数(1-90，默认30)。 */
    @PostMapping("startFetchListings")
    public Result<String> startFetchListings(@RequestBody(required = false) JSONObject body) {
        try {
            Long taskId = service.startFetch(body == null ? null : body.getString("scope"),
                    body == null ? null : body.getInteger("viewDays"));
            return taskId == null ? Result.buildError(BUSY) : Result.buildSuccess(String.valueOf(taskId));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @PostMapping("startEndListings")
    public Result<String> startEndListings(@RequestParam("file") MultipartFile file) {
        return startWithExcel(file, EbayItemIdExcel.class, "eBay按编号下架模板", null, service::startEnd);
    }

    @PostMapping("startRelistListings")
    public Result<String> startRelistListings(@RequestParam("file") MultipartFile file) {
        return startWithExcel(file, EbayItemIdExcel.class, "eBay按编号重新上架模板", null, service::startRelist);
    }

    @PostMapping("startSetPrice")
    public Result<String> startSetPrice(@RequestParam("file") MultipartFile file) {
        return startWithExcel(file, EbaySetPriceExcel.class, "eBay指定尺码改价模板", "尺码明细", service::startSetPrice);
    }

    @GetMapping("endListingsTemplate")
    public void endListingsTemplate(HttpServletResponse response) throws IOException {
        writeTemplate(response, "eBay按编号下架模板", "物品编号", EbayItemIdExcel.class, List.of(
                List.of("字段", "说明"),
                List.of("物品编号", "必填；eBay物品编号(例如 407333465699)，也可以直接粘贴商品链接"),
                List.of("来源", "可直接上传「eBay获取刊登」导出表的「刊登概要」sheet，只读第一列"),
                List.of("已下架", "已经结束的刊登会标记为「已是下架状态」，不算失败")));
    }

    @GetMapping("relistListingsTemplate")
    public void relistListingsTemplate(HttpServletResponse response) throws IOException {
        writeTemplate(response, "eBay按编号重新上架模板", "物品编号", EbayItemIdExcel.class, List.of(
                List.of("字段", "说明"),
                List.of("物品编号", "必填；已结束刊登的eBay物品编号，也可以直接粘贴商品链接"),
                List.of("来源", "可直接上传「eBay获取刊登」(范围选已结束) 导出表的「刊登概要」sheet，只读第一列"),
                List.of("新编号", "重新上架后eBay会生成新的物品编号，任务明细里会列出"),
                List.of("跳过", "仍在售、或已经重新上架过的刊登会跳过")));
    }

    @GetMapping("setPriceTemplate")
    public void setPriceTemplate(HttpServletResponse response) throws IOException {
        writeTemplate(response, "eBay指定尺码改价模板", "改价", EbaySetPriceExcel.class, List.of(
                List.of("字段", "说明"),
                List.of("物品编号", "必填；eBay物品编号，也可以直接粘贴商品链接"),
                List.of("尺码", "多尺码刊登必填；填eBay页面上显示的尺码，例如 9.5"),
                List.of("新价格(USD)", "必填；美元，最多两位小数。为空的行会忽略"),
                List.of("SKU", "选填；填了优先按SKU匹配尺码"),
                List.of("来源", "可直接上传「eBay获取刊登」导出表的「尺码明细」sheet，只要在「新价格」列填上价格")));
    }

    /** preferredSheet：上传的是「获取刊登」导出表时优先读这个 sheet，没有就读第一个。 */
    private <T> Result<String> startWithExcel(MultipartFile file, Class<T> head, String templateName,
                                              String preferredSheet, Function<List<T>, Long> starter) {
        List<T> rows = List.of();
        try {
            validateFile(file);
            if (preferredSheet != null) {
                try (InputStream input = file.getInputStream()) {
                    rows = EasyExcel.read(input).head(head).sheet(preferredSheet).doReadSync();
                }
            }
            if (rows == null || rows.isEmpty()) {
                try (InputStream input = file.getInputStream()) {
                    rows = EasyExcel.read(input).head(head).sheet().doReadSync();
                }
            }
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        } catch (Exception e) {
            return Result.buildError("Excel读取失败，请确认使用了" + templateName);
        }
        try {
            Long taskId = starter.apply(rows);
            return taskId == null ? Result.buildError(BUSY) : Result.buildSuccess(String.valueOf(taskId));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    private void writeTemplate(HttpServletResponse response, String name, String sheet, Class<?> head,
                               List<List<String>> guide) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            writer.write(List.of(), EasyExcel.writerSheet(0, sheet).head(head).build());
            writer.write(guide, EasyExcel.writerSheet(1, "填写说明").build());
        }
    }

    static void validateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传Excel文件");
        }
        if (file.getSize() > MAX_EXCEL_SIZE) {
            throw new IllegalArgumentException("Excel文件不能超过10MB");
        }
        String fileName = file.getOriginalFilename();
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!(lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls"))) {
            throw new IllegalArgumentException("仅支持.xlsx或.xls格式的Excel文件");
        }
        byte[] signature;
        try (InputStream input = file.getInputStream()) {
            signature = input.readNBytes(8);
        }
        boolean zip = signature.length >= 4 && signature[0] == 0x50 && signature[1] == 0x4B
                && signature[2] == 0x03 && signature[3] == 0x04;
        boolean ole2 = Arrays.equals(signature, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
        if (!(lowerName.endsWith(".xlsx") && zip) && !(lowerName.endsWith(".xls") && ole2)) {
            throw new IllegalArgumentException("文件内容不是有效的Excel格式");
        }
    }
}
