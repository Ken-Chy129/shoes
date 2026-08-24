package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.service.EbayBulkListingService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("task/ebay")
public class EbayBulkListingController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private final EbayBulkListingService bulkListingService;

    public EbayBulkListingController(EbayBulkListingService bulkListingService) {
        this.bulkListingService = bulkListingService;
    }

    @PostMapping("startBulkListing")
    public Result<String> startBulkListing(@RequestParam("file") MultipartFile file) {
        try {
            validateFile(file);
            List<EbayListingExcel> rows = EasyExcel.read(file.getInputStream())
                    .head(EbayListingExcel.class).sheet().doReadSync();
            return Result.buildSuccess(String.valueOf(bulkListingService.start(rows)));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        } catch (Exception e) {
            return Result.buildError("Excel读取失败，请确认使用了eBay批量上架模板");
        }
    }

    @GetMapping("bulkListingTemplate")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay批量上架模板", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet inputSheet = EasyExcel.writerSheet(0, "批量上架")
                    .head(EbayListingExcel.class).build();
            writer.write(List.of(), inputSheet);
            List<List<String>> guide = List.of(
                    List.of("字段", "说明"),
                    List.of("货号", "必填；你的商品货号，例如 DD1391-100"),
                    List.of("尺码", "必填；填写 USM10、USW8.5 或 EU42.5"),
                    List.of("数量", "必填；1 到 999999 的整数"),
                    List.of("上架价格(USD)", "必填；美元金额，最多两位小数"),
                    List.of("选填商品资料", "标题、品牌、描述、图片等；不填时先查本地缓存，未命中仅请求KC一次"),
                    List.of("图片链接(选填)", "多个公开HTTP(S)图片链接用换行、分号或逗号分隔"),
                    List.of("商品状态", "无需填写；第一版统一按全新 NEW 上架"),
                    List.of("SKU", "无需填写；系统按货号、尺码和NEW状态稳定生成")
            );
            WriteSheet guideSheet = EasyExcel.writerSheet(1, "填写说明").build();
            writer.write(guide, guideSheet);
        }
    }

    private void validateFile(MultipartFile file) {
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
    }
}
