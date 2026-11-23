package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayProfitPriceService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EbayProfitPriceControllerTest {
    @Test
    void startsProfitTaskWithBucketFreightsWithoutExcel() {
        EbayProfitPriceService service = mock(EbayProfitPriceService.class);
        when(service.start(eq(1L), any(EbayProfitPriceService.Rules.class))).thenReturn(99L);
        List<BigDecimal> ag = List.of(bd("30"), bd("35"), bd("40"), bd("45"), bd("50"));
        List<BigDecimal> nonAg = List.of(bd("36"), bd("42"), bd("48"), bd("54"), bd("60"));
        var result = new EbayProfitPriceController(service).startProfitPrice(1, ag, nonAg, bd("500"),
                bd("30"), bd("80"), bd("-2"), 6, bd("-20"), null);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("99");
        verify(service).start(eq(1L), argThat((EbayProfitPriceService.Rules r) -> r.agFreights().equals(ag)
                && r.leadMinSkuCount() == 6 && r.adRateAdjust().compareTo(bd("-2")) == 0 && r.targets().isEmpty()));
    }

    @Test
    void rejectsWrongFreightCount() {
        EbayProfitPriceService service = mock(EbayProfitPriceService.class);
        var result = new EbayProfitPriceController(service).startProfitPrice(1, List.of(bd("30")),
                List.of(bd("30")), bd("500"), bd("30"), bd("80"), null, null, null, null);
        assertThat(result.getSuccess()).isFalse();
        verify(service, never()).start(anyLong(), any(EbayProfitPriceService.Rules.class));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void startsPromoTaskWithDiscountParams() {
        EbayProfitPriceService service = mock(EbayProfitPriceService.class);
        BigDecimal target = new BigDecimal("-30");
        BigDecimal freight = new BigDecimal("25");
        BigDecimal discount = new BigDecimal("15");
        BigDecimal share = new BigDecimal("70");
        when(service.startPromo(1, target, freight, discount, share, List.of())).thenReturn(77L);
        var result = new EbayProfitPriceController(service)
                .startPromoProfitPrice(1, target, freight, discount, share, null);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("77");
    }
}
