package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayListingRegion;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.excel.EbayListingExcel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EbayListingFactory {

    private static final Pattern SIZE_PATTERN = Pattern.compile("^(USM|USW|EU)([0-9]{1,2}(?:\\.5)?)$");
    private static final int MAX_SKU_LENGTH = 50;
    private final EbayProperties properties;
    private final EbayListingTaxonomyService taxonomyService;
    private final EbayTitleService titleService;

    public EbayListingFactory(EbayProperties properties,
                              EbayListingTaxonomyService taxonomyService) {
        this(properties, taxonomyService, new EbayTitleService());
    }

    @Autowired
    public EbayListingFactory(EbayProperties properties,
                              EbayListingTaxonomyService taxonomyService,
                              EbayTitleService titleService) {
        this.properties = properties;
        this.taxonomyService = taxonomyService;
        this.titleService = titleService;
    }

    public EbayListingRequest create(EbayListingExcel row, EbayProductMetadata metadata) {
        return create(row, metadata, EbayListingRegion.HK);
    }

    public EbayListingRequest create(EbayListingExcel row, EbayProductMetadata metadata,
                                     EbayListingRegion region) {
        EbayListingRegion effectiveRegion = region == null ? EbayListingRegion.HK : region;
        if (row == null) {
            throw new IllegalArgumentException("Excel行不能为空");
        }
        String styleId = required(row.getStyleId(), "货号").toUpperCase(Locale.ROOT);
        ParsedSize size = parseSize(row.getSize());
        if (row.getQuantity() == null || row.getQuantity() < 1 || row.getQuantity() > 999_999) {
            throw new IllegalArgumentException("数量必须是1到999999之间的整数");
        }
        if (row.getPrice() == null || row.getPrice().signum() <= 0 || row.getPrice().scale() > 2) {
            throw new IllegalArgumentException("上架价格必须是大于0且最多两位小数的USD金额");
        }
        if (metadata == null || metadata.getImageUrls() == null || metadata.getImageUrls().isEmpty()) {
            throw new IllegalArgumentException("商品图片不能为空");
        }

        EbayListingRequest request = new EbayListingRequest();
        request.setSku(sku(styleId, size.normalized(), effectiveRegion));
        request.setTitle(titleService.generate(styleId, size.system(), metadata));
        request.setDescription(required(metadata.getDescription(), "描述"));
        request.setImageUrls(metadata.getImageUrls().stream().limit(12).toList());
        request.setQuantity(row.getQuantity());
        request.setCondition("NEW");
        EbayListingTaxonomyService.ResolvedTaxonomy taxonomy = taxonomyService.resolve(
                row.getCategoryId(), styleId, metadata, size.system(), size.value());
        request.setCategoryId(taxonomy.categoryId());
        request.setMarketplaceId(properties.getDefaultMarketplaceId());
        request.setCurrency(properties.getDefaultCurrency());
        request.setPrice(row.getPrice());
        EbayProperties.ListingSettings settings = properties.listingSettings(effectiveRegion);
        String label = effectiveRegion.getLabel();
        request.setMerchantLocationKey(required(settings.merchantLocationKey(), label + "发货地点"));
        request.setFulfillmentPolicyId(required(settings.fulfillmentPolicyId(), label + "物流政策"));
        request.setPaymentPolicyId(required(settings.paymentPolicyId(), label + "付款政策"));
        request.setReturnPolicyId(required(settings.returnPolicyId(), label + "退货政策"));
        request.setBrand(metadata.getBrand());
        request.setMpn(styleId);
        request.setAspects(taxonomy.aspects());
        request.setContentLanguage(properties.getDefaultContentLanguage());
        return request;
    }

    ParsedSize parseSize(String rawSize) {
        String normalized = required(rawSize, "尺码")
                .toUpperCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        Matcher matcher = SIZE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("尺码格式错误，请填写USM10、USW10或EU42.5");
        }
        return new ParsedSize(matcher.group(1), matcher.group(2), normalized);
    }

    String sku(String styleId, String normalizedSize) {
        return sku(styleId, normalizedSize, EbayListingRegion.HK);
    }

    /**
     * 港区沿用历史 SKU，保证已上架商品继续被更新；美区单独一套 SKU，
     * 因为同一 listing 的所有尺码只能共用一个发货地点和物流政策。
     */
    String sku(String styleId, String normalizedSize, EbayListingRegion region) {
        String source = region == EbayListingRegion.US
                ? String.join("|", "EBAY", "US", styleId, normalizedSize, "NEW")
                : String.join("|", "EBAY", styleId, normalizedSize, "NEW");
        return stableKey(source.toUpperCase(Locale.ROOT));
    }

    String groupKey(String styleId) {
        return groupKey(styleId, EbayListingRegion.HK);
    }

    String groupKey(String styleId, EbayListingRegion region) {
        String style = required(styleId, "货号");
        String source = region == EbayListingRegion.US
                ? String.join("|", "EBAYGROUP", "US", style, "NEW")
                : String.join("|", "EBAYGROUP", style, "NEW");
        return stableKey(source.toUpperCase(Locale.ROOT));
    }

    private String stableKey(String source) {
        String readable = source.replaceAll("[^A-Z0-9]", "");
        String hash = sha256(source).substring(0, 8).toUpperCase(Locale.ROOT);
        int readableLength = Math.min(readable.length(), MAX_SKU_LENGTH - hash.length());
        return readable.substring(0, readableLength) + hash;
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record ParsedSize(String system, String value, String normalized) {
    }
}
