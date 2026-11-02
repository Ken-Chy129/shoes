package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.mapper.EbayListingMapper;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.entity.EbayListingDO;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EbayTitleBackfillServiceTest {

    private EbayListingMapper listingMapper;
    private ProductCatalogMapper catalogMapper;
    private EbayListingService listingService;
    private EbayTitleBackfillService service;

    @BeforeEach
    void setUp() {
        listingMapper = mock(EbayListingMapper.class);
        catalogMapper = mock(ProductCatalogMapper.class);
        listingService = mock(EbayListingService.class);
        when(catalogMapper.selectList(any())).thenReturn(List.<ProductCatalogDO>of());
        service = new EbayTitleBackfillService(listingMapper, catalogMapper, listingService,
                new EbayTitleService(), new EbayProperties());
    }

    @Test
    void insertsDepartmentGenderIntoLiveGroupTitleAndSnapshot() {
        when(listingMapper.selectActive()).thenReturn(List.of(
                listing("DD1391-100", "EU42"), listing("DD1391-100", "EU43")));
        JSONObject group = group("Nike Dunk Low Retro White Black DD1391-100", "Women");
        String key = EbayListingFactory.inventoryGroupKey("DD1391-100", EbayListingRegion.HK);
        when(listingService.getGroup(key)).thenReturn(Optional.of(group));

        service.run(false, 0, Set.of());

        String expected = "Nike Dunk Low Retro White Black Women's DD1391-100";
        verify(listingService).replaceGroupTitle(eq(key), eq(group), eq(expected), any());
        verify(listingMapper).updateGroupTitle("DD1391-100", "hk", expected);
        assertThat(service.status()).containsEntry("updated", 1).containsEntry("total", 1);
    }

    @Test
    void skipsGroupsWhoseTitleIsAlreadyMigrated() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("DD1391-100", "EU42")));
        when(listingService.getGroup(anyString())).thenReturn(Optional.of(
                group("Nike Dunk Low Men's DD1391-100", "Unisex Adults")));

        service.run(false, 0, Set.of());

        verify(listingService, never()).replaceGroupTitle(any(), any(), any(), any());
        assertThat(service.status()).containsEntry("unchanged", 1);
    }

    @Test
    void kidsTitlesUseAgeGroupFromTitleMarker() {
        assertThat(service.newTitle("BQ7669-161", "EU",
                "Jordan 4 Retro Red Cement (PS) BQ7669-161", "Unisex Baby & Toddler"))
                .isEqualTo("Jordan 4 Retro Red Cement (PS) Preschool BQ7669-161");
        assertThat(service.newTitle("015110", "EU", "adidas Copa Mundial 015110", null))
                .isEqualTo("adidas Copa Mundial Men's 015110");
    }

    private EbayListingDO listing(String styleId, String size) {
        EbayListingDO listing = new EbayListingDO();
        listing.setStyleId(styleId);
        listing.setSize(size);
        listing.setRegion("hk");
        return listing;
    }

    private JSONObject group(String title, String department) {
        JSONObject aspects = new JSONObject();
        aspects.put("Department", List.of(department));
        JSONObject group = new JSONObject();
        group.put("title", title);
        group.put("aspects", aspects);
        group.put("variantSKUs", List.of("SKU1"));
        return group;
    }

    @Test
    void dryRunDoesNotWriteEbayOrDatabase() {
        when(listingMapper.selectActive()).thenReturn(List.of(listing("DD1391-100", "USM9")));
        when(listingService.getGroup(anyString())).thenReturn(Optional.of(
                group("Nike Dunk Low DD1391-100", "Men")));

        service.run(true, 0, Set.of());

        verify(listingService, never()).replaceGroupTitle(any(), any(), any(), any());
        verify(listingMapper, never()).updateGroupTitle(any(), any(), any());
        assertThat(service.status()).containsEntry("updated", 1);
    }
}
