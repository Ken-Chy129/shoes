package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.EbayListingDO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayCountryOfOriginBackfillServiceTest {

    private EbayListingMapper listingMapper;
    private EbayListingService listingService;
    private EbayCountryOfOriginBackfillService service;

    @BeforeEach
    void setUp() {
        listingMapper = mock(EbayListingMapper.class);
        listingService = mock(EbayListingService.class);
        service = new EbayCountryOfOriginBackfillService(listingMapper, listingService, new EbayProperties());
    }

    @Test
    void writesVietnamOncePerGroupAndKeepsOtherAspects() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("DD1391-100", "EU42"), listing("DD1391-100", "EU43")));
        String key = EbayListingFactory.inventoryGroupKey("DD1391-100", EbayListingRegion.HK);
        JSONObject group = JSON.parseObject("""
                {"title":"t","variantSKUs":["A","B"],
                 "aspects":{"Brand":["Nike"],"Country/Region of Manufacture":["China"]}}""");
        when(listingService.getGroup(key)).thenReturn(Optional.of(group));

        service.run(false, 0, Set.of(), 1);

        ArgumentCaptor<JSONObject> aspects = ArgumentCaptor.forClass(JSONObject.class);
        verify(listingService).replaceGroupAspects(eq(key), eq(group), aspects.capture(), any());
        assertThat(aspects.getValue().keySet()).containsExactly("Brand", "Country of Origin");
        assertThat(aspects.getValue().getJSONArray("Country of Origin")).containsExactly("Vietnam");
        assertThat(service.status()).containsEntry("updated", 1).containsEntry("total", 1);
    }

    @Test
    void skipsGroupsAlreadyVietnamAndDoesNotWriteInDryRun() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("AAA", "EU42"), listing("BBB", "EU42")));
        when(listingService.getGroup(EbayListingFactory.inventoryGroupKey("AAA", EbayListingRegion.HK)))
                .thenReturn(Optional.of(JSON.parseObject("""
                        {"variantSKUs":["A"],"aspects":{"Country of Origin":["Vietnam"]}}""")));
        when(listingService.getGroup(EbayListingFactory.inventoryGroupKey("BBB", EbayListingRegion.HK)))
                .thenReturn(Optional.of(JSON.parseObject("""
                        {"variantSKUs":["B"],"aspects":{"Brand":["Nike"]}}""")));

        service.run(true, 0, Set.of(), 1);

        verify(listingService, never()).replaceGroupAspects(any(), any(), any(), any());
        assertThat(service.status()).containsEntry("unchanged", 1).containsEntry("updated", 1);
    }

    private EbayListingDO listing(String styleId, String size) {
        EbayListingDO listing = new EbayListingDO();
        listing.setStyleId(styleId);
        listing.setSize(size);
        listing.setRegion("hk");
        return listing;
    }
}
