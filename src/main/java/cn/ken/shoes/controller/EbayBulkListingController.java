package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.model.excel.EbayPoisonListingExcel;
import cn.ken.shoes.model.excel.EbayTrackingExcel;
import cn.ken.shoes.service.EbayBulkListingService;
import cn.ken.shoes.service.EbayPoisonListingService;
import cn.ken.shoes.service.EbayTrackingUploadService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("task/ebay")
public class EbayBulkListingController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private final EbayBulkListingService bulkListingService;
    private final EbayTrackingUploadService trackingUploadService;
    private final EbayPoisonListingService poisonListingService;

    public EbayBulkListingController(EbayBulkListingService bulkListingService) {
        this(bulkListingService, null, null);
    }

    public EbayBulkListingController(EbayBulkListingService bulkListingService,
                                     EbayTrackingUploadService trackingUploadService) {
        this(bulkListingService, trackingUploadService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EbayBulkListingController(EbayBulkListingService bulkListingService,
                                     EbayTrackingUploadService trackingUploadService,
                                     EbayPoisonListingService poisonListingService) {
        this.bulkListingService = bulkListingService;
        this.trackingUploadService = trackingUploadService;
        this.poisonListingService = poisonListingService;
    }

    /**
     * 上传货号 Excel，得物有价的尺码全部上架；上架价(USD) = 得物价 × 1.25 ÷ 6.6 + 50。
     * region: hk(默认，港区) / us(美区)。
     */
    @PostMapping("startPoisonListing")
    public Result<String> startPoisonListing(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "region", required = false) String region) {
        List<EbayPoisonListingExcel> rows;
        EbayListingRegion listingRegion;
        try {
            listingRegion = EbayListingRegion.fromCode(region);
            validateFile(file);
            try (InputStream input = file.getInputStream()) {
                rows = EasyExcel.read(input).head(EbayPoisonListingExcel.class).sheet().doReadSync();
            }
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        } catch (Exception e) {
            return Result.buildError("Excel读取失败，请确认使用了eBay得物价上架模板");
        }
        try {
            return Result.buildSuccess(String.valueOf(poisonListingService.start(rows, listingRegion)));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @GetMapping("poisonListingTemplate")
    public void downloadPoisonListingTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay得物价上架模板", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet inputSheet = EasyExcel.writerSheet(0, "货号")
                    .head(EbayPoisonListingExcel.class).build();
            writer.write(List.of(), inputSheet);
            List<List<String>> guide = List.of(
                    List.of("字段", "说明"),
                    List.of("货号", "必填；一行一个货号，重复货号自动去重，单次最多2000个"),
                    List.of("上架尺码", "得物有价格的尺码全部上架（按EU码），无价尺码不上架"),
                    List.of("上架价格", "USD = 得物价(人民币) × 1.25 ÷ 6.6 + 50，保留两位小数；例如得物500元 → $144.70"),
                    List.of("数量", "每个尺码上架1件"),
                    List.of("商品资料", "标题、品牌、描述、图片自动获取，与批量上架一致"),
                    List.of("发货区域", "创建任务时在页面选择港区或美区"));
            writer.write(guide, EasyExcel.writerSheet(1, "填写说明").build());
        }
    }

    /** 上传「订单编号 + 追踪号码 (+ 物流公司)」Excel，给订单添加追踪号码并标记已发货。 */
    @PostMapping("startAddTracking")
    public Result<String> startAddTracking(@RequestParam("file") MultipartFile file) {
        List<EbayTrackingExcel> rows;
        try {
            validateFile(file);
            try (InputStream input = file.getInputStream()) {
                rows = EasyExcel.read(input).head(EbayTrackingExcel.class).sheet().doReadSync();
            }
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        } catch (Exception e) {
            return Result.buildError("Excel读取失败，请确认使用了eBay添加追踪号码模板");
        }
        try {
            Long taskId = trackingUploadService.start(rows);
            if (taskId == null) {
                return Result.buildError("eBay添加追踪号码任务已在运行，请等待当前任务结束");
            }
            return Result.buildSuccess(String.valueOf(taskId));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @GetMapping("trackingTemplate")
    public void downloadTrackingTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay添加追踪号码模板", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            WriteSheet inputSheet = EasyExcel.writerSheet(0, "追踪号码")
                    .head(EbayTrackingExcel.class).build();
            writer.write(List.of(), inputSheet);
            List<List<String>> guide = List.of(
                    List.of("字段", "说明"),
                    List.of("订单编号", "必填；eBay订单编号，例如 21-15259-42163"),
                    List.of("追踪号码", "必填；物流追踪号码，例如 524382226900。请将单元格设为文本，避免长数字被转成科学计数法"),
                    List.of("物流公司", "选填；默认 FedEx，也可填 UPS、USPS、DHL 等eBay支持的物流公司"),
                    List.of("一单多个追踪号码", "同一订单写多行即可，会一起提交（对应卖家中心的「其他追踪号码」）"),
                    List.of("重复执行", "订单上已经存在的追踪号码会自动跳过；提交后订单标记为已发货"));
            WriteSheet guideSheet = EasyExcel.writerSheet(1, "填写说明").build();
            writer.write(guide, guideSheet);
        }
    }

    public Result<String> startBulkListing(MultipartFile file) {
        return startBulkListing(file, null);
    }

    /** region: hk(默认，港区) / us(美区)。 */
    @PostMapping("startBulkListing")
    public Result<String> startBulkListing(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "region", required = false) String region) {
        try {
            EbayListingRegion listingRegion = EbayListingRegion.fromCode(region);
            validateFile(file);
            List<EbayListingExcel> rows = readRows(file);
            return Result.buildSuccess(String.valueOf(bulkListingService.start(rows, listingRegion)));
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
                    List.of("SKU", "无需填写；系统按货号、尺码和NEW状态稳定生成"),
                    List.of("多尺码", "同一货号的多行尺码会合并成一条eBay商品；每个尺码保留独立价格、库存、SKU和Offer"),
                    List.of("发货区域", "无需填写；创建任务时在页面选择港区或美区，两者使用不同的发货地址和运输政策，SKU互不覆盖"),
                    List.of("处理时间", "无需填写；按所选发货区域的物流政策执行"),
                    List.of("退货", "无需填写；统一设置为不接受退货")
            );
            WriteSheet guideSheet = EasyExcel.writerSheet(1, "填写说明").build();
            writer.write(guide, guideSheet);
        }
    }

    private void validateFile(MultipartFile file) throws IOException {
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
        boolean validXlsx = lowerName.endsWith(".xlsx") && isZip(signature);
        boolean validXls = lowerName.endsWith(".xls") && isOle2(signature);
        if (!validXlsx && !validXls) {
            throw new IllegalArgumentException("文件内容不是有效的Excel格式");
        }
    }

    private List<EbayListingExcel> readRows(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return EasyExcel.read(input).head(EbayListingExcel.class).sheet().doReadSync();
        }
    }

    private boolean isZip(byte[] signature) {
        return signature.length >= 4
                && signature[0] == 0x50 && signature[1] == 0x4B
                && signature[2] == 0x03 && signature[3] == 0x04;
    }

    private boolean isOle2(byte[] signature) {
        byte[] expected = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        return java.util.Arrays.equals(signature, expected);
    }
}
