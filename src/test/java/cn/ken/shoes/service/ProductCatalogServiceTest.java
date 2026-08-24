package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.model.catalog.ProductCatalogItem;
import cn.ken.shoes.model.catalog.ProductCatalogPageRequest;
import cn.ken.shoes.model.catalog.ProductCatalogUpdateRequest;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCatalogServiceTest {

    @Test
    void pagesCatalogEntriesAndParsesImageUrls() {
        ProductCatalogMapper mapper = mock(ProductCatalogMapper.class);
        ProductCatalogDO product = product();
        when(mapper.count("DD1391", "Nike", "kickscrew")).thenReturn(1L);
        when(mapper.selectPage("DD1391", "Nike", "kickscrew", 0L, 20))
                .thenReturn(List.of(product));
        ProductCatalogService service = new ProductCatalogService(mapper);
        ProductCatalogPageRequest request = new ProductCatalogPageRequest();
        request.setModelNo(" DD1391 ");
        request.setBrand(" Nike ");
        request.setSource(" kickscrew ");
        request.setPageSize(20);

        PageResult<List<ProductCatalogItem>> result = service.page(request);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getData()).singleElement().satisfies(item -> {
            assertThat(item.getModelNo()).isEqualTo("DD1391-100");
            assertThat(item.getImageUrls()).containsExactly(
                    "https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg");
            assertThat(item.getImageCount()).isEqualTo(2);
        });
    }

    @Test
    void manualPatchKeepsUnspecifiedFieldsAndMarksTheEntryAsProtected() {
        ProductCatalogMapper mapper = mock(ProductCatalogMapper.class);
        when(mapper.selectById("DD1391-100")).thenReturn(product());
        ProductCatalogService service = new ProductCatalogService(mapper);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        request.setTitle("Edited title");
        request.setImageUrls(List.of(" https://cdn.example.com/edited.jpg "));

        ProductCatalogItem result = service.update(" dd1391-100 ", request);

        ArgumentCaptor<ProductCatalogDO> saved = ArgumentCaptor.forClass(ProductCatalogDO.class);
        verify(mapper).updateManual(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Edited title");
        assertThat(saved.getValue().getBrand()).isEqualTo("Nike");
        assertThat(saved.getValue().getManualOverride()).isTrue();
        assertThat(result.getImageUrls()).containsExactly("https://cdn.example.com/edited.jpg");
    }

    @Test
    void rejectsAnEmptyManualImageList() {
        ProductCatalogMapper mapper = mock(ProductCatalogMapper.class);
        when(mapper.selectById("DD1391-100")).thenReturn(product());
        ProductCatalogService service = new ProductCatalogService(mapper);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        request.setImageUrls(List.of());

        assertThatThrownBy(() -> service.update("DD1391-100", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片");
    }

    @Test
    void rejectsInvalidImageUrlsInsteadOfSilentlyDiscardingThem() {
        ProductCatalogMapper mapper = mock(ProductCatalogMapper.class);
        when(mapper.selectById("DD1391-100")).thenReturn(product());
        ProductCatalogService service = new ProductCatalogService(mapper);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        request.setImageUrls(List.of("https://cdn.example.com/valid.jpg", "not-a-url"));

        assertThatThrownBy(() -> service.update("DD1391-100", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void rejectsDescriptionsThatCannotFitTheCatalogTextColumn() {
        ProductCatalogMapper mapper = mock(ProductCatalogMapper.class);
        when(mapper.selectById("DD1391-100")).thenReturn(product());
        ProductCatalogService service = new ProductCatalogService(mapper);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        request.setDescription("a".repeat(16_001));

        assertThatThrownBy(() -> service.update("DD1391-100", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("描述不能超过");
    }

    private ProductCatalogDO product() {
        ProductCatalogDO product = new ProductCatalogDO();
        product.setModelNo("DD1391-100");
        product.setTitle("Nike Dunk Low Retro");
        product.setBrand("Nike");
        product.setDescription("Product description");
        product.setImageUrls("[\"https://cdn.example.com/1.jpg\",\"https://cdn.example.com/2.jpg\"]");
        product.setSource("kickscrew");
        product.setManualOverride(false);
        return product;
    }
}
