package cn.ken.shoes.service;

import cn.ken.shoes.mapper.EbaySpecialFreightMapper;
import cn.ken.shoes.model.entity.EbaySpecialFreightDO;
import cn.ken.shoes.model.excel.EbaySpecialFreightExcel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class EbaySpecialFreightServiceTest {

    @Test
    void importNormalizesTargetAndSizeSoLoadMatchesRepricingKeys() {
        EbaySpecialFreightMapper mapper = mock(EbaySpecialFreightMapper.class);
        EbaySpecialFreightService service = new EbaySpecialFreightService(mapper);

        assertThat(service.importRows(List.of(excel(" dd1391-100 ", "42⅔", "400"),
                excel("https://www.ebay.com/itm/123456789012", "", "300")))).isEqualTo(2);
        verify(mapper).upsert(argThat(r -> "DD1391-100".equals(r.getTarget()) && "42.5".equals(r.getEuSize())));
        verify(mapper).upsert(argThat(r -> "123456789012".equals(r.getTarget()) && "".equals(r.getEuSize())));

        EbaySpecialFreightDO row = new EbaySpecialFreightDO();
        row.setTarget("DD1391-100");
        row.setEuSize("42.5");
        row.setFreight(new BigDecimal("400"));
        when(mapper.selectAll()).thenReturn(List.of(row));
        assertThat(EbayProfitPriceService.matchKey(service.load(), "999", "dd1391-100", "42.5"))
                .isEqualTo("DD1391-100|42.5");
    }

    @Test
    void importWritesNothingWhenAnyRowIsInvalid() {
        EbaySpecialFreightMapper mapper = mock(EbaySpecialFreightMapper.class);
        EbaySpecialFreightService service = new EbaySpecialFreightService(mapper);

        assertThatThrownBy(() -> service.importRows(List.of(excel("AAA-1", "", "400"), excel("", "42", "300"),
                excel("BBB-2", "", "-1")))).hasMessageContaining("第3行").hasMessageContaining("第4行");
        verify(mapper, never()).upsert(any());
    }

    private static EbaySpecialFreightExcel excel(String target, String size, String freight) {
        EbaySpecialFreightExcel excel = new EbaySpecialFreightExcel();
        excel.setTarget(target);
        excel.setSize(size);
        excel.setFreight(new BigDecimal(freight));
        return excel;
    }
}
