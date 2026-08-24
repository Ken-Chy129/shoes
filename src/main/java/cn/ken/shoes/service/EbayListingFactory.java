package cn.ken.shoes.service;

import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayListingRequest;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.model.excel.EbayListingExcel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EbayListingFactory {

    private static final Pattern SIZE_PATTERN = Pattern.compile("^(USM|USW|EU)([0-9]{1,2}(?:\\.5)?)$");
    private static final int MAX_SKU_LENGTH = 50;
    private final EbayProperties properties;

    public EbayListingFactory(EbayProperties properties) {
        this.properties = properties;
    }

    public EbayListingRequest create(EbayListingExcel row, EbayProductMetadata metadata) {
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
        request.setSku(sku(styleId, size.normalized()));
        request.setTitle(limit(required(metadata.getTitle(), "标题"), 80));
        request.setDescription(required(metadata.getDescription(), "描述"));
        request.setImageUrls(metadata.getImageUrls().stream().limit(12).toList());
        request.setQuantity(row.getQuantity());
        request.setCondition("NEW");
        request.setCategoryId(categoryId(row, metadata, size));
        request.setMarketplaceId(properties.getDefaultMarketplaceId());
        request.setCurrency(properties.getDefaultCurrency());
        request.setPrice(row.getPrice());
        request.setMerchantLocationKey(required(properties.getDefaultMerchantLocationKey(), "默认仓库"));
        request.setFulfillmentPolicyId(required(properties.getDefaultFulfillmentPolicyId(), "物流政策"));
        request.setPaymentPolicyId(required(properties.getDefaultPaymentPolicyId(), "付款政策"));
        request.setReturnPolicyId(required(properties.getDefaultReturnPolicyId(), "退货政策"));
        request.setBrand(metadata.getBrand());
        request.setMpn(styleId);
        request.setAspects(aspects(metadata, size));
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
        String source = String.join("|", "EBAY", styleId, normalizedSize, "NEW")
                .toUpperCase(Locale.ROOT);
        String readable = source.replaceAll("[^A-Z0-9]", "");
        String hash = sha256(source).substring(0, 8).toUpperCase(Locale.ROOT);
        int readableLength = Math.min(readable.length(), MAX_SKU_LENGTH - hash.length());
        return readable.substring(0, readableLength) + hash;
    }

    private String categoryId(EbayListingExcel row, EbayProductMetadata metadata, ParsedSize size) {
        if (row.getCategoryId() != null && !row.getCategoryId().isBlank()) {
            if (!row.getCategoryId().trim().matches("[0-9]{1,20}")) {
                throw new IllegalArgumentException("分类ID必须是数字");
            }
            return row.getCategoryId().trim();
        }
        boolean women = "USW".equals(size.system())
                || (metadata.getGender() != null
                && metadata.getGender().toLowerCase(Locale.ROOT).contains("women"));
        return women ? properties.getDefaultWomensCategoryId() : properties.getDefaultMensCategoryId();
    }

    private Map<String, List<String>> aspects(EbayProductMetadata metadata, ParsedSize size) {
        Map<String, List<String>> aspects = new LinkedHashMap<>();
        switch (size.system()) {
            case "USM" -> {
                aspects.put("US Shoe Size", List.of(size.value()));
                aspects.put("Department", List.of("Men"));
            }
            case "USW" -> {
                aspects.put("US Shoe Size", List.of(size.value()));
                aspects.put("Department", List.of("Women"));
            }
            case "EU" -> aspects.put("EU Shoe Size", List.of(size.value()));
            default -> throw new IllegalStateException("unsupported size system");
        }
        putAspect(aspects, "Color", metadata.getColor());
        putAspect(aspects, "Upper Material", metadata.getUpperMaterial());
        return aspects;
    }

    private void putAspect(Map<String, List<String>> aspects, String name, String value) {
        if (value != null && !value.isBlank()) {
            aspects.put(name, List.of(limit(value.trim(), 65)));
        }
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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
