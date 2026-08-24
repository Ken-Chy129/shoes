package cn.ken.shoes.service;

import cn.ken.shoes.common.PageResult;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.model.catalog.ProductCatalogItem;
import cn.ken.shoes.model.catalog.ProductCatalogPageRequest;
import cn.ken.shoes.model.catalog.ProductCatalogUpdateRequest;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

@Service
public class ProductCatalogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IMAGES = 20;
    private static final int MAX_DESCRIPTION_LENGTH = 16_000;
    private final ProductCatalogMapper mapper;

    public ProductCatalogService(ProductCatalogMapper mapper) {
        this.mapper = mapper;
    }

    public PageResult<List<ProductCatalogItem>> page(ProductCatalogPageRequest request) {
        ProductCatalogPageRequest safeRequest = request == null ? new ProductCatalogPageRequest() : request;
        int pageIndex = Math.max(1, safeRequest.getPageIndex());
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, safeRequest.getPageSize()));
        String modelNo = trimToNull(safeRequest.getModelNo());
        String brand = trimToNull(safeRequest.getBrand());
        String source = trimToNull(safeRequest.getSource());
        long total = mapper.count(modelNo, brand, source);
        if (total == 0) {
            PageResult<List<ProductCatalogItem>> empty = PageResult.buildSuccess();
            empty.setPageIndex(pageIndex);
            empty.setPageSize(pageSize);
            return empty;
        }
        long offset = (long) (pageIndex - 1) * pageSize;
        List<ProductCatalogItem> items = mapper.selectPage(modelNo, brand, source, offset, pageSize)
                .stream().map(this::toItem).toList();
        PageResult<List<ProductCatalogItem>> result = PageResult.buildSuccess(items);
        result.setTotal(total);
        result.setPageIndex(pageIndex);
        result.setPageSize(pageSize);
        result.setPageCount((total + pageSize - 1) / pageSize);
        result.setHasMore(pageIndex < result.getPageCount());
        return result;
    }

    public ProductCatalogItem get(String rawModelNo) {
        String modelNo = normalizeModelNo(rawModelNo);
        ProductCatalogDO product = mapper.selectById(modelNo);
        if (product == null) {
            throw new IllegalArgumentException("商品资料不存在: " + modelNo);
        }
        return toItem(product);
    }

    public ProductCatalogItem update(String rawModelNo, ProductCatalogUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("商品资料不能为空");
        }
        String modelNo = normalizeModelNo(rawModelNo);
        ProductCatalogDO product = mapper.selectById(modelNo);
        if (product == null) {
            throw new IllegalArgumentException("商品资料不存在: " + modelNo);
        }
        product.setTitle(valueOrExisting(request.getTitle(), product.getTitle(), 255, "标题"));
        product.setBrand(optionalValue(request.getBrand(), product.getBrand(), 65, "品牌"));
        product.setDescription(optionalText(request.getDescription(), product.getDescription(),
                MAX_DESCRIPTION_LENGTH, "描述"));
        product.setProductType(optionalValue(request.getProductType(), product.getProductType(), 64, "商品类型"));
        product.setModelName(optionalValue(request.getModelName(), product.getModelName(), 128, "型号"));
        product.setProductLine(optionalValue(request.getProductLine(), product.getProductLine(), 128, "产品线"));
        product.setCountryOfOrigin(optionalValue(request.getCountryOfOrigin(),
                product.getCountryOfOrigin(), 64, "原产国"));
        product.setGender(optionalValue(request.getGender(), product.getGender(), 32, "性别"));
        product.setColor(optionalValue(request.getColor(), product.getColor(), 128, "颜色"));
        product.setColorway(optionalValue(request.getColorway(), product.getColorway(), 255, "配色"));
        product.setUpperMaterial(optionalValue(request.getUpperMaterial(), product.getUpperMaterial(), 128, "鞋面材质"));
        if (request.getImageUrls() != null) {
            List<String> images = sanitizeImages(request.getImageUrls());
            if (images.isEmpty()) {
                throw new IllegalArgumentException("商品图片至少保留一张");
            }
            product.setImageUrls(JSON.toJSONString(images));
        }
        product.setManualOverride(true);
        mapper.updateManual(product);
        return toItem(product);
    }

    private ProductCatalogItem toItem(ProductCatalogDO product) {
        ProductCatalogItem item = new ProductCatalogItem();
        item.setModelNo(product.getModelNo());
        item.setTitle(product.getTitle());
        item.setBrand(product.getBrand());
        item.setDescription(product.getDescription());
        item.setProductType(product.getProductType());
        item.setModelName(product.getModelName());
        item.setProductLine(product.getProductLine());
        item.setCountryOfOrigin(product.getCountryOfOrigin());
        item.setGender(product.getGender());
        item.setColor(product.getColor());
        item.setColorway(product.getColorway());
        item.setUpperMaterial(product.getUpperMaterial());
        List<String> images = parseImages(product.getImageUrls());
        item.setImageUrls(images);
        item.setImageCount(images.size());
        item.setSource(product.getSource());
        item.setSourceUpdatedAt(product.getSourceUpdatedAt());
        item.setManualOverride(Boolean.TRUE.equals(product.getManualOverride()));
        item.setGmtCreate(product.getGmtCreate());
        item.setGmtModified(product.getGmtModified());
        return item;
    }

    private List<String> parseImages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> images = JSON.parseArray(json, String.class);
            return images == null ? List.of() : images;
        } catch (JSONException e) {
            return List.of();
        }
    }

    private List<String> sanitizeImages(List<String> imageUrls) {
        List<String> normalized = imageUrls.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (normalized.stream().anyMatch(value -> !isHttpUrl(value))) {
            throw new IllegalArgumentException("图片链接必须是有效的http/https地址");
        }
        return normalized.stream()
                .distinct()
                .limit(MAX_IMAGES)
                .toList();
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private String normalizeModelNo(String value) {
        String modelNo = valueOrExisting(value, null, 64, "货号");
        return modelNo.toUpperCase(Locale.ROOT);
    }

    private String valueOrExisting(String value, String existing, int maxLength, String label) {
        if (value == null) {
            if (existing == null || existing.isBlank()) {
                throw new IllegalArgumentException(label + "不能为空");
            }
            return existing;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过" + maxLength + "个字符");
        }
        return trimmed;
    }

    private String optionalValue(String value, String existing, int maxLength, String label) {
        if (value == null) {
            return existing;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过" + maxLength + "个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String optionalText(String value, String existing, int maxLength, String label) {
        if (value == null) {
            return existing;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过" + maxLength + "个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
