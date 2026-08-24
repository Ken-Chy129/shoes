package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.excel.EbayListingExcel;
import cn.ken.shoes.service.EbayBulkListingService;
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EbayBulkListingControllerTest {

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

    private EbayListingExcel row() {
        EbayListingExcel row = new EbayListingExcel();
        row.setStyleId("DD1391-100");
        row.setSize("USM10");
        row.setQuantity(1);
        row.setPrice(new BigDecimal("129.99"));
        return row;
    }
}
