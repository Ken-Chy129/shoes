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
}
