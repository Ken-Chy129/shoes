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
import java.util.List;
import java.util.Locale;

@Service
@Slf4j
public class EbayProductMetadataService {

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
        RuntimeException kcFailure;
        try {
            String handle = kickScrewItemMapper.selectHandleByModelNo(modelNo);
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("本地没有该货号的KC资料");
            }
            EbayProductMetadata fetched = kickScrewClient.queryProductMetadata(handle);
            fillMissingKickScrewColor(fetched);
            validate(fetched);
            catalogMapper.upsertFromSource(toCatalog(modelNo, fetched));
            return fetched;
        } catch (RuntimeException e) {
            kcFailure = e;
            log.warn("KC商品资料不可用，尝试StockX兜底，modelNo:{}, reason:{}", modelNo, e.getMessage());
        }

        try {
            if (stockXClient == null) {
                throw new IllegalStateException("StockX兜底未配置");
            }
            EbayProductMetadata fallback = stockXClient.queryProductMetadataByModelNo(modelNo);
            validate(fallback);
            ProductCatalogDO catalog = toCatalog(modelNo, fallback);
            catalog.setSource("stockx");
            catalogMapper.upsertFromSource(catalog);
            return fallback;
        } catch (RuntimeException stockxFailure) {
            log.warn("StockX商品资料兜底失败，modelNo:{}, reason:{}", modelNo, stockxFailure.getMessage());
            throw new IllegalArgumentException("KC和StockX均未获取到可用商品资料，请在Excel补充标题和图片链接", kcFailure);
        }
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
        ProductCatalogDO catalog = toCatalog(modelNo, metadata);
        boolean manual = hasManualFields(row);
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
        if ("kickscrew".equalsIgnoreCase(cache.getSource())) {
            fillMissingKickScrewColor(metadata);
        }
        List<String> images = JSON.parseArray(cache.getImageUrls(), String.class);
        metadata.setImageUrls(images != null ? images : List.of());
        validate(metadata);
        return metadata;
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
        cache.setSource("kickscrew");
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
