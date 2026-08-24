package cn.ken.shoes.controller;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.common.Result;
import cn.ken.shoes.model.catalog.ProductCatalogItem;
import cn.ken.shoes.model.catalog.ProductCatalogPageRequest;
import cn.ken.shoes.model.catalog.ProductCatalogUpdateRequest;
import cn.ken.shoes.service.ProductCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("productCatalog")
public class ProductCatalogController {

    private final ProductCatalogService service;

    public ProductCatalogController(ProductCatalogService service) {
        this.service = service;
    }

    @GetMapping("page")
    public PageResult<List<ProductCatalogItem>> page(ProductCatalogPageRequest request) {
        return service.page(request);
    }

    @GetMapping("{modelNo}")
    public Result<ProductCatalogItem> get(@PathVariable String modelNo) {
        try {
            return Result.buildSuccess(service.get(modelNo));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }

    @PatchMapping("{modelNo}")
    public Result<ProductCatalogItem> update(@PathVariable String modelNo,
                                             @RequestBody ProductCatalogUpdateRequest request) {
        try {
            return Result.buildSuccess(service.update(modelNo, request));
        } catch (IllegalArgumentException e) {
            return Result.buildError(e.getMessage());
        }
    }
}
