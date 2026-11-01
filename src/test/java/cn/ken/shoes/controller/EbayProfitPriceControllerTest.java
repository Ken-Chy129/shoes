package cn.ken.shoes.controller;

import cn.ken.shoes.service.EbayProfitPriceService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EbayProfitPriceControllerTest {
    @Test
    void acceptsTaskFreightWithoutExcel() {
        EbayProfitPriceService service = mock(EbayProfitPriceService.class);
        BigDecimal target = new BigDecimal("50");
        BigDecimal freight = new BigDecimal("35.50");
        when(service.start(1, target, freight, List.of())).thenReturn(99L);
        var result = new EbayProfitPriceController(service).startProfitPrice(1, target, freight, null);
        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("99");
        verify(service).start(1, target, freight, List.of());
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
