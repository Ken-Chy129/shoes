package cn.ken.shoes.service;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.EbayProductCacheMapper;
import cn.ken.shoes.mapper.KickScrewItemMapper;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.entity.EbayProductCacheDO;
import cn.ken.shoes.model.excel.EbayListingExcel;
import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class EbayProductMetadataService {

    private final EbayProductCacheMapper cacheMapper;
    private final KickScrewItemMapper kickScrewItemMapper;
    private final KickScrewClient kickScrewClient;

    public EbayProductMetadataService(EbayProductCacheMapper cacheMapper,
                                      KickScrewItemMapper kickScrewItemMapper,
                                      KickScrewClient kickScrewClient) {
        this.cacheMapper = cacheMapper;
        this.kickScrewItemMapper = kickScrewItemMapper;
        this.kickScrewClient = kickScrewClient;
    }

    public EbayProductMetadata resolve(String rawModelNo) {
        String modelNo = required(rawModelNo, "货号").toUpperCase(Locale.ROOT);
        EbayProductCacheDO cached = cacheMapper.selectById(modelNo);
        if (cached != null) {
            return fromCache(cached);
        }
        String handle = kickScrewItemMapper.selectHandleByModelNo(modelNo);
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("本地没有该货号的KC资料，请在Excel补充标题、描述和图片链接");
        }
        EbayProductMetadata fetched = kickScrewClient.queryProductMetadata(handle);
        validate(fetched);
        cacheMapper.upsert(toCache(modelNo, fetched));
        return fetched;
    }

    public EbayProductMetadata resolve(EbayListingExcel row) {
        if (row == null) {
            throw new IllegalArgumentException("Excel行不能为空");
        }
        boolean manualComplete = present(row.getTitle()) && present(row.getImageUrls());
        EbayProductMetadata metadata = manualComplete
                ? new EbayProductMetadata()
                : resolve(row.getStyleId());
        setIfPresent(row.getTitle(), metadata::setTitle);
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
        return metadata;
    }

    private EbayProductMetadata fromCache(EbayProductCacheDO cache) {
        EbayProductMetadata metadata = new EbayProductMetadata();
        metadata.setTitle(cache.getTitle());
        metadata.setBrand(cache.getBrand());
        metadata.setDescription(cache.getDescription());
        metadata.setProductType(cache.getProductType());
        metadata.setGender(cache.getGender());
        metadata.setColor(cache.getColor());
        metadata.setColorway(cache.getColorway());
        metadata.setUpperMaterial(cache.getUpperMaterial());
        List<String> images = JSON.parseArray(cache.getImageUrls(), String.class);
        metadata.setImageUrls(images != null ? images : List.of());
        validate(metadata);
        return metadata;
    }

    private EbayProductCacheDO toCache(String modelNo, EbayProductMetadata metadata) {
        EbayProductCacheDO cache = new EbayProductCacheDO();
        cache.setModelNo(modelNo);
        cache.setTitle(metadata.getTitle());
        cache.setBrand(metadata.getBrand());
        cache.setDescription(metadata.getDescription());
        cache.setProductType(metadata.getProductType());
        cache.setGender(metadata.getGender());
        cache.setColor(metadata.getColor());
        cache.setColorway(metadata.getColorway());
        cache.setUpperMaterial(metadata.getUpperMaterial());
        cache.setImageUrls(JSON.toJSONString(metadata.getImageUrls()));
        cache.setSource("kickscrew");
        cache.setSourceUpdatedAt(new Date());
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
