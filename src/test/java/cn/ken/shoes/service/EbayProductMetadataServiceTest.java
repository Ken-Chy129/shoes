package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.EbayProductCacheMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.EbayProductCacheDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EbayProductMetadataServiceTest {

    @Test
    void returnsLocalCacheWithoutCallingKickScrew() {
        EbayProductCacheMapper cacheMapper = mock(EbayProductCacheMapper.class);
        KickScrewItemMapper itemMapper = mock(KickScrewItemMapper.class);
        KickScrewClient kickScrewClient = mock(KickScrewClient.class);
        EbayProductCacheDO cached = new EbayProductCacheDO();
        cached.setModelNo("DD1391-100");
        cached.setTitle("Cached title");
        cached.setDescription("Cached description");
        cached.setImageUrls("[\"https://cdn.example.com/cached.jpg\"]");
        when(cacheMapper.selectById("DD1391-100")).thenReturn(cached);
        EbayProductMetadataService service = new EbayProductMetadataService(
                cacheMapper, itemMapper, kickScrewClient);

        EbayProductMetadata result = service.resolve(" DD1391-100 ");

        assertThat(result.getTitle()).isEqualTo("Cached title");
        assertThat(result.getImageUrls()).containsExactly("https://cdn.example.com/cached.jpg");
        verifyNoInteractions(itemMapper, kickScrewClient);
    }

    @Test
    void loadsColdProductWithOneKickScrewRequestThenCachesIt() {
        EbayProductCacheMapper cacheMapper = mock(EbayProductCacheMapper.class);
        KickScrewItemMapper itemMapper = mock(KickScrewItemMapper.class);
        KickScrewClient kickScrewClient = mock(KickScrewClient.class);
        when(cacheMapper.selectById("DD1391-100")).thenReturn(null);
        when(itemMapper.selectHandleByModelNo("DD1391-100")).thenReturn("nike-dunk-low-retro-white-black");
        EbayProductMetadata fetched = new EbayProductMetadata();
        fetched.setTitle("Nike Dunk Low Retro");
        fetched.setDescription("Product description");
        fetched.setBrand("Nike");
        fetched.setImageUrls(List.of("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg"));
        when(kickScrewClient.queryProductMetadata("nike-dunk-low-retro-white-black"))
                .thenReturn(fetched);
        EbayProductMetadataService service = new EbayProductMetadataService(
                cacheMapper, itemMapper, kickScrewClient);

        EbayProductMetadata result = service.resolve("DD1391-100");

        assertThat(result.getBrand()).isEqualTo("Nike");
        verify(kickScrewClient).queryProductMetadata("nike-dunk-low-retro-white-black");
        ArgumentCaptor<EbayProductCacheDO> cache = ArgumentCaptor.forClass(EbayProductCacheDO.class);
        verify(cacheMapper).upsert(cache.capture());
        assertThat(cache.getValue().getModelNo()).isEqualTo("DD1391-100");
        assertThat(cache.getValue().getImageUrls()).contains("cdn.example.com/1.jpg");
    }

    @Test
    void refusesMissingKcHandleWithoutMakingAnExternalRequest() {
        EbayProductCacheMapper cacheMapper = mock(EbayProductCacheMapper.class);
        KickScrewItemMapper itemMapper = mock(KickScrewItemMapper.class);
        KickScrewClient kickScrewClient = mock(KickScrewClient.class);
        when(cacheMapper.selectById("UNKNOWN-1")).thenReturn(null);
        when(itemMapper.selectHandleByModelNo("UNKNOWN-1")).thenReturn(null);
        EbayProductMetadataService service = new EbayProductMetadataService(
                cacheMapper, itemMapper, kickScrewClient);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolve("UNKNOWN-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请在Excel补充");
        verify(kickScrewClient, never()).queryProductMetadata(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void completeExcelOverridesAvoidCacheAndExternalLookup() {
        EbayProductCacheMapper cacheMapper = mock(EbayProductCacheMapper.class);
        KickScrewItemMapper itemMapper = mock(KickScrewItemMapper.class);
        KickScrewClient kickScrewClient = mock(KickScrewClient.class);
        EbayProductMetadataService service = new EbayProductMetadataService(
                cacheMapper, itemMapper, kickScrewClient);
        EbayListingExcel row = new EbayListingExcel();
        row.setStyleId("MANUAL-1");
        row.setTitle("Manual product");
        row.setDescription("Manual description");
        row.setBrand("Manual brand");
        row.setImageUrls("https://cdn.example.com/1.jpg\nhttps://cdn.example.com/2.jpg");

        EbayProductMetadata result = service.resolve(row);

        assertThat(result.getTitle()).isEqualTo("Manual product");
        assertThat(result.getImageUrls()).containsExactly(
                "https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg");
        verifyNoInteractions(cacheMapper, itemMapper, kickScrewClient);
    }
}
