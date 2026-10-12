package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.client.StockXClient;
import cn.ken.shoes.mapper.ProductCatalogMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.ProductCatalogDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class EbayProductMetadataService {

    private static final String SOURCE_KICKSCREW = "kickscrew";
    private static final String SOURCE_STOCKX = "stockx";
    static final int MIN_PREFERRED_IMAGES = 5;
    static final int MAX_MERGED_IMAGES = 8;

    private final ProductCatalogMapper catalogMapper;
    private final KickScrewItemMapper kickScrewItemMapper;
    private final KickScrewClient kickScrewClient;
    private final StockXClient stockXClient;

    public EbayProductMetadataService(ProductCatalogMapper catalogMapper,
                                      KickScrewItemMapper kickScrewItemMapper,
                                      KickScrewClient kickScrewClient) {
        this(catalogMapper, kickScrewItemMapper, kickScrewClient, null);
    }

    @Autowired
    public EbayProductMetadataService(ProductCatalogMapper catalogMapper,
                                      KickScrewItemMapper kickScrewItemMapper,
                                      KickScrewClient kickScrewClient,
                                      StockXClient stockXClient) {
        this.catalogMapper = catalogMapper;
        this.kickScrewItemMapper = kickScrewItemMapper;
        this.kickScrewClient = kickScrewClient;
        this.stockXClient = stockXClient;
    }

    public EbayProductMetadata resolve(String rawModelNo) {
        String modelNo = required(rawModelNo, "货号").toUpperCase(Locale.ROOT);
        ProductCatalogDO cached = catalogMapper.selectById(modelNo);
        if (cached != null) {
            return fromCache(cached);
        }
        // 图片优先用 StockX（官方图库，背景统一），文字资料优先用 KC（颜色、材质等字段更全）。
        EbayProductMetadata stockx = null;
        boolean stockxTransientFailure = false;
        try {
            if (stockXClient != null) {
                stockx = stockXClient.queryProductMetadataByModelNo(modelNo);
            }
        } catch (RuntimeException e) {
            stockxTransientFailure = true;
            log.warn("StockX商品资料查询失败，本次改用KC图片且不写入资料库，modelNo:{}, reason:{}",
                    modelNo, e.getMessage());
        }
        List<String> stockxImages = stockx != null && stockx.getImageUrls() != null
                ? stockx.getImageUrls() : List.of();

        EbayProductMetadata kc = null;
        RuntimeException kcFailure = null;
        try {
            String handle = kickScrewItemMapper.selectHandleByModelNo(modelNo);
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("本地没有该货号的KC资料");
            }
            kc = kickScrewClient.queryProductMetadata(handle);
            if (kc == null || !present(kc.getTitle())) {
                throw new IllegalArgumentException("KC未返回可用商品资料");
            }
            fillMissingKickScrewColor(kc);
            fillMissingKickScrewGender(modelNo, kc);
        } catch (RuntimeException e) {
            kcFailure = e;
            kc = null;
            log.warn("KC商品资料不可用，modelNo:{}, reason:{}", modelNo, e.getMessage());
        }

        EbayProductMetadata result;
        if (kc != null) {
            result = kc;
            List<String> kcImages = kc.getImageUrls() != null ? kc.getImageUrls() : List.of();
            result.setImageUrls(mergeImages(stockxImages, kcImages));
            result.setSource(stockxImages.isEmpty() ? SOURCE_KICKSCREW : SOURCE_STOCKX);
        } else if (stockx != null && present(stockx.getTitle()) && !stockxImages.isEmpty()) {
            result = stockx;
            result.setImageUrls(mergeImages(stockxImages, List.of()));
            result.setSource(SOURCE_STOCKX);
        } else {
            throw new IllegalArgumentException("KC和StockX均未获取到可用商品资料，请在Excel补充标题和图片链接", kcFailure);
        }
        validate(result);
        // StockX 临时失败(限流/账号池耗尽)时不缓存，避免把只有 KC 图片的资料永久固化下来。
        result.setCacheable(!stockxTransientFailure);
        if (result.isCacheable()) {
            catalogMapper.upsertFromSource(toCatalog(modelNo, result));
        }
        return result;
    }

    /**
     * StockX 图片够 {@value #MIN_PREFERRED_IMAGES} 张就只用 StockX；不够时先放 StockX，再用 KC 补齐。
     */
    static List<String> mergeImages(List<String> stockxImages, List<String> kcImages) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        stockxImages.stream().filter(url -> url != null && !url.isBlank()).map(String::trim)
                .limit(MAX_MERGED_IMAGES).forEach(merged::add);
        if (merged.size() < MIN_PREFERRED_IMAGES) {
            for (String url : kcImages) {
                if (merged.size() >= MAX_MERGED_IMAGES) {
                    break;
                }
                if (url != null && !url.isBlank()) {
                    merged.add(url.trim());
                }
            }
        }
        return List.copyOf(merged);
    }

    public EbayProductMetadata resolve(EbayListingExcel row) {
        if (row == null) {
            throw new IllegalArgumentException("Excel行不能为空");
        }
        String modelNo = required(row.getStyleId(), "货号").toUpperCase(Locale.ROOT);
        ProductCatalogDO cached = catalogMapper.selectById(modelNo);
        boolean manualComplete = present(row.getTitle()) && present(row.getImageUrls());
        EbayProductMetadata metadata;
        if (cached != null) {
            metadata = fromCache(cached);
        } else if (manualComplete) {
            metadata = new EbayProductMetadata();
        } else {
            metadata = resolve(modelNo);
        }
        setIfPresent(row.getTitle(), metadata::setTitle);
        if (present(row.getTitle())) {
            metadata.setManualTitle(true);
        }
        setIfPresent(row.getBrand(), metadata::setBrand);
        setIfPresent(row.getDescription(), metadata::setDescription);
        setIfPresent(row.getGender(), metadata::setGender);
        setIfPresent(row.getColor(), metadata::setColor);
        setIfPresent(row.getColorway(), metadata::setColorway);
        setIfPresent(row.getUpperMaterial(), metadata::setUpperMaterial);
        if (present(row.getImageUrls())) {
            metadata.setImageUrls(Arrays.stream(row.getImageUrls().split("[\\r\\n;,]+"))
                    .map(String::trim).filter(value -> !value.isBlank()).distinct().limit(12).toList());
        }
        validate(metadata);
        boolean manual = hasManualFields(row);
        if (!metadata.isCacheable() && !present(row.getImageUrls())) {
            // StockX 临时查询失败时只用于本次上架，不落库，下次还会优先尝试 StockX 图片。
            return metadata;
        }
        ProductCatalogDO catalog = toCatalog(modelNo, metadata);
        catalog.setManualOverride(manual);
        if (manual) {
            catalog.setSource("manual");
        }
        catalogMapper.upsertFromListing(catalog);
        return metadata;
    }

    private boolean hasManualFields(EbayListingExcel row) {
        return present(row.getTitle()) || present(row.getBrand()) || present(row.getDescription())
                || present(row.getImageUrls()) || present(row.getGender()) || present(row.getColor())
                || present(row.getColorway()) || present(row.getUpperMaterial());
    }

    private EbayProductMetadata fromCache(ProductCatalogDO cache) {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle(cache.getTitle());
        metadata.setBrand(cache.getBrand());
        metadata.setDescription(cache.getDescription());
        metadata.setProductType(cache.getProductType());
        metadata.setModelName(cache.getModelName());
        metadata.setProductLine(cache.getProductLine());
        metadata.setCountryOfOrigin(cache.getCountryOfOrigin());
        metadata.setGender(cache.getGender());
        metadata.setColor(cache.getColor());
        metadata.setColorway(cache.getColorway());
        metadata.setUpperMaterial(cache.getUpperMaterial());
        metadata.setManualTitle(Boolean.TRUE.equals(cache.getManualOverride()));
        metadata.setSource(cache.getSource());
        if ("kickscrew".equalsIgnoreCase(cache.getSource())) {
            fillMissingKickScrewColor(metadata);
        }
        List<String> images = JSON.parseArray(cache.getImageUrls(), String.class);
        metadata.setImageUrls(images != null ? images : List.of());
        validate(metadata);
        return metadata;
    }

    /**
     * KC storefront 商品详情接口不返回性别，但本地爬下来的 KC 商品列表
     * （kick_screw_item.gender: MENS/WOMENS/KIDS/BABY/UNISEX）有，补进资料里，
     * 这样类目、Department 和尺码换算就不用再靠标题猜。
     */
    private void fillMissingKickScrewGender(String modelNo, EbayProductMetadata metadata) {
        if (metadata == null || present(metadata.getGender())) {
            return;
        }
        String gender = kickScrewItemMapper.selectGenderByModelNo(modelNo);
        if (present(gender)) {
            metadata.setGender(gender.trim());
        }
    }

    private void fillMissingKickScrewColor(EbayProductMetadata metadata) {
        if (metadata == null || present(metadata.getColor())) {
            return;
        }
        String resolved = EbayTitleColorExtractor.extract(metadata.getColorway());
        if (!present(resolved)) {
            resolved = EbayTitleColorExtractor.extract(metadata.getTitle());
        }
        metadata.setColor(present(resolved) ? resolved : "White");
    }

    private ProductCatalogDO toCatalog(String modelNo, EbayProductMetadata metadata) {
        ProductCatalogDO cache = new ProductCatalogDO();
        cache.setModelNo(modelNo);
        cache.setTitle(metadata.getTitle());
        cache.setBrand(metadata.getBrand());
        cache.setDescription(metadata.getDescription());
        cache.setProductType(metadata.getProductType());
        cache.setModelName(metadata.getModelName());
        cache.setProductLine(metadata.getProductLine());
        cache.setCountryOfOrigin(metadata.getCountryOfOrigin());
        cache.setGender(metadata.getGender());
        cache.setColor(metadata.getColor());
        cache.setColorway(metadata.getColorway());
        cache.setUpperMaterial(metadata.getUpperMaterial());
        cache.setImageUrls(JSON.toJSONString(metadata.getImageUrls()));
        cache.setSource(present(metadata.getSource()) ? metadata.getSource() : SOURCE_KICKSCREW);
        cache.setSourceUpdatedAt(new Date());
        cache.setManualOverride(false);
        return cache;
    }

    private void validate(EbayProductMetadata metadata) {
        if (metadata == null || metadata.getTitle() == null || metadata.getTitle().isBlank()) {
            throw new IllegalArgumentException("KC商品资料缺少标题，请在Excel补充");
        }
        if (metadata.getDescription() == null || metadata.getDescription().isBlank()) {
            metadata.setDescription(metadata.getTitle());
        }
        if (metadata.getImageUrls() == null || metadata.getImageUrls().isEmpty()) {
            throw new IllegalArgumentException("KC商品资料缺少图片，请在Excel补充图片链接");
        }
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (present(value)) {
            setter.accept(value.trim());
        }
    }
}
