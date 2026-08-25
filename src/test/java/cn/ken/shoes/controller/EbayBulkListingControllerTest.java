package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.service.EbayBulkListingService;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EbayBulkListingControllerTest {

    @Test
    void putsInputExamplesDirectlyInTheTemplateColumnHeaders() throws Exception {
        EbayBulkListingController controller = new EbayBulkListingController(
                mock(EbayBulkListingService.class));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.downloadTemplate(response);

        List<Map<Integer, String>> rows = EasyExcel.read(
                        new ByteArrayInputStream(response.getContentAsByteArray()))
                .headRowNumber(0)
                .sheet("批量上架")
                .doReadSync();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry(0, "货号（例：DD1391-100）")
                .containsEntry(1, "尺码（USM9/USW9/EU42.5）")
                .containsEntry(2, "数量（整数）")
                .containsEntry(3, "上架价格（USD，如199.99）");
    }

    @Test
    void startsBulkListingFromTheUploadedWorkbook() throws Exception {
        EbayBulkListingService service = mock(EbayBulkListingService.class);
        when(service.start(argThat(rows -> rows.size() == 1
                && "DD1391-100".equals(rows.getFirst().getStyleId())
                && "USM10".equals(rows.getFirst().getSize())
                && rows.getFirst().getPrice().compareTo(new BigDecimal("129.99")) == 0)))
                .thenReturn(2026L);
        EbayBulkListingController controller = new EbayBulkListingController(service);

        Result<String> result = controller.startBulkListing(excelFile(List.of(row())));

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("2026");
    }

    @Test
    void importsThePreviousTemplateHeadersByStableColumnPosition() {
        EbayBulkListingService service = mock(EbayBulkListingService.class);
        when(service.start(argThat(rows -> rows.size() == 1
                && "DD1391-100".equals(rows.getFirst().getStyleId())
                && "USM10".equals(rows.getFirst().getSize())
                && rows.getFirst().getPrice().compareTo(new BigDecimal("129.99")) == 0)))
                .thenReturn(2027L);
        EbayBulkListingController controller = new EbayBulkListingController(service);

        Result<String> result = controller.startBulkListing(previousTemplateFile());

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("2027");
    }

    @Test
    void rejectsNonExcelUploadsBeforeStartingATask() throws Exception {
        EbayBulkListingService service = mock(EbayBulkListingService.class);
        EbayBulkListingController controller = new EbayBulkListingController(service);
        MockMultipartFile file = new MockMultipartFile(
                "file", "input.txt", "text/plain", "not excel".getBytes());

        Result<String> result = controller.startBulkListing(file);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Excel");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsSpoofedExcelExtensionsBeforeParsing() {
        EbayBulkListingService service = mock(EbayBulkListingService.class);
        EbayBulkListingController controller = new EbayBulkListingController(service);
        MockMultipartFile file = new MockMultipartFile(
                "file", "input.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "not really an Excel workbook".getBytes());

        Result<String> result = controller.startBulkListing(file);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("Excel");
        verifyNoInteractions(service);
    }

    @Test
    void stopsReadingAfterTheMaximumRowCount() {
        EbayBulkListingService service = mock(EbayBulkListingService.class);
        EbayBulkListingController controller = new EbayBulkListingController(service);
        List<EbayListingExcel> rows = java.util.stream.IntStream.rangeClosed(1, 1_001)
                .mapToObj(index -> {
                    EbayListingExcel row = row();
                    row.setStyleId("STYLE-" + index);
                    return row;
                }).toList();

        Result<String> result = controller.startBulkListing(excelFile(rows));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).contains("1000");
        verifyNoInteractions(service);
    }

    private MockMultipartFile excelFile(List<EbayListingExcel> rows) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, EbayListingExcel.class).sheet("批量上架").doWrite(rows);
        return new MockMultipartFile("file", "ebay-listings.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private MockMultipartFile previousTemplateFile() {
        List<List<String>> head = List.of(
                List.of("货号"), List.of("尺码"), List.of("数量"), List.of("上架价格(USD)"),
                List.of("标题(选填)"), List.of("品牌(选填)"), List.of("描述(选填)"),
                List.of("图片链接(选填)"), List.of("颜色(选填)"), List.of("配色(选填)"),
                List.of("鞋面材质(选填)"), List.of("性别(选填)"), List.of("分类ID(选填)"));
        List<Object> row = Arrays.asList(
                "DD1391-100", "USM10", 1, new BigDecimal("129.99"),
                null, null, null, null, null, null, null, null, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output).head(head).sheet("批量上架").doWrite(List.of(row));
        return new MockMultipartFile("file", "previous-ebay-listings.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                output.toByteArray());
    }

    private EbayListingExcel row() {
        EbayListingExcel row = new EbayListingExcel();
        row.setStyleId("DD1391-100");
        row.setSize("USM10");
        row.setQuantity(1);
        row.setPrice(new BigDecimal("129.99"));
        return row;
    }
}
