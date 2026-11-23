package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.entity.EbaySpecialFreightDO;
import cn.ken.shoes.model.excel.EbaySpecialFreightExcel;
import cn.ken.shoes.service.EbaySpecialFreightService;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** eBay 盈利改价特殊运费管理：列表、新增/修改、删除、Excel 导入。 */
@Slf4j
@RestController
@RequestMapping("task/ebay/specialFreight")
public class EbaySpecialFreightController {

    private static final long MAX_EXCEL_SIZE = 10 * 1024 * 1024L;
    private final EbaySpecialFreightService service;

    public EbaySpecialFreightController(EbaySpecialFreightService service) {
        this.service = service;
    }

    @GetMapping("list")
    public Result<List<EbaySpecialFreightDO>> list() {
        return Result.buildSuccess(service.list());
    }

    @PostMapping("save")
    public Result<Void> save(@RequestParam("target") String target,
                             @RequestParam(value = "size", required = false) String size,
                             @RequestParam("freight") BigDecimal freight) {
        try {
            service.save(target, size, freight);
            return Result.buildSuccess(null);
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @DeleteMapping("delete")
    public Result<Void> delete(@RequestParam("id") Long id) {
        service.delete(id);
        return Result.buildSuccess(null);
    }

    @PostMapping("import")
    public Result<Integer> importExcel(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file.getSize() > MAX_EXCEL_SIZE || !(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            return Result.buildError("仅支持10MB以内的.xlsx或.xls文件");
        }
        List<EbaySpecialFreightExcel> rows;
        try (InputStream input = file.getInputStream()) {
            rows = EasyExcel.read(input).head(EbaySpecialFreightExcel.class).sheet().doReadSync();
        } catch (Exception e) {
            log.warn("特殊运费Excel读取失败", e);
            return Result.buildError("Excel读取失败，请确认使用了特殊运费模板");
        }
        try {
            return Result.buildSuccess(service.importRows(rows));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @GetMapping("template")
    public void template(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String fileName = URLEncoder.encode("eBay特殊运费模板", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");
        try (ExcelWriter writer = EasyExcel.write(response.getOutputStream()).build()) {
            writer.write(List.of(), EasyExcel.writerSheet(0, "特殊运费").head(EbaySpecialFreightExcel.class).build());
            writer.write(List.of(
                    List.of("字段", "说明"),
                    List.of("货号或物品编号", "必填；填货号对该货号所有链接生效，填eBay物品编号(或商品链接)只对该链接生效"),
                    List.of("尺码", "选填；EU码，不填则全尺码生效"),
                    List.of("运费", "必填；人民币，填AG链接的运费，非AG链接自动按×1.2计算（如400→480）"),
                    List.of("优先级", "物品编号+尺码 > 物品编号 > 货号+尺码 > 货号；命中的尺码不用盈利改价任务里的尺码段运费"),
                    List.of("导入规则", "同一货号/编号+尺码覆盖原值，表里没有的新增，已有记录不会被删除")),
                    EasyExcel.writerSheet(1, "填写说明").build());
        }
    }
}
