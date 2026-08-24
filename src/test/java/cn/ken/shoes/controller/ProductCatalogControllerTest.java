package cn.ken.shoes.controller;

import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.catalog.ProductCatalogItem;
import cn.ken.shoes.model.catalog.ProductCatalogUpdateRequest;
import cn.ken.shoes.service.ProductCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductCatalogControllerTest {

    @Test
    void returnsTheUpdatedCatalogEntry() {
        ProductCatalogService service = mock(ProductCatalogService.class);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        request.setTitle("Edited title");
        ProductCatalogItem item = new ProductCatalogItem();
        item.setModelNo("DD1391-100");
        item.setTitle("Edited title");
        item.setImageUrls(List.of("https://cdn.example.com/1.jpg"));
        when(service.update("DD1391-100", request)).thenReturn(item);
        ProductCatalogController controller = new ProductCatalogController(service);

        Result<ProductCatalogItem> result = controller.update("DD1391-100", request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData().getTitle()).isEqualTo("Edited title");
    }

    @Test
    void exposesValidationErrorsAsBusinessErrors() {
        ProductCatalogService service = mock(ProductCatalogService.class);
        ProductCatalogUpdateRequest request = new ProductCatalogUpdateRequest();
        when(service.update("DD1391-100", request))
                .thenThrow(new IllegalArgumentException("标题不能为空"));
        ProductCatalogController controller = new ProductCatalogController(service);

        Result<ProductCatalogItem> result = controller.update("DD1391-100", request);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("标题不能为空");
    }
}
