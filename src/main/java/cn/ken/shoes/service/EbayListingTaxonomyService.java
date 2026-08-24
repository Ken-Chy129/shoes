package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayTaxonomyApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EbayListingTaxonomyService {

    private final EbayProperties properties;
    private final EbayTaxonomyApiClient client;
    private final Map<String, CategoryChoice> categoryCache = new ConcurrentHashMap<>();
    private final Map<String, List<AspectRule>> aspectCache = new ConcurrentHashMap<>();

    public EbayListingTaxonomyService(EbayProperties properties, EbayTaxonomyApiClient client) {
        this.properties = properties;
        this.client = client;
    }

    public ResolvedTaxonomy resolve(String categoryOverride, String styleCode,
                                    EbayProductMetadata metadata, String sizeSystem,
                                    String sizeValue) {
        if (metadata == null) {
            throw new IllegalArgumentException("商品资料不能为空");
        }
        String department = department(sizeSystem, metadata.getGender());
        CategoryChoice category = categoryOverride == null || categoryOverride.isBlank()
                ? resolveCategory(metadata, department)
                : new CategoryChoice(numericId(categoryOverride, "分类ID"), null);
        List<AspectRule> rules = loadAspectRules(category.id());
        Map<String, List<String>> aspects = rules.isEmpty()
                ? fallbackAspects(metadata, styleCode, sizeSystem, sizeValue, department)
                : buildAspects(rules, metadata, styleCode, sizeSystem, sizeValue, department);
        return new ResolvedTaxonomy(category.id(), category.name(), aspects);
    }

    private CategoryChoice resolveCategory(EbayProductMetadata metadata, String department) {
        String query = String.join(" ", nonBlank(metadata.getTitle(), "商品标题"),
                department == null ? "" : department, "Shoes").replaceAll("\\s+", " ").trim();
        return categoryCache.computeIfAbsent(query, ignored -> {
            try {
                CategoryChoice suggestion = firstShoeSuggestion(
                        client.getCategorySuggestions(properties.getDefaultCategoryTreeId(), query));
                if (suggestion != null) {
                    return suggestion;
                }
            } catch (EbayApiException ignoredError) {
                // Taxonomy is advisory. Standard sneakers retain the existing safe fallback.
            }
            if (isStandardShoe(metadata.getProductType())) {
                boolean women = "Women".equals(department);
                return new CategoryChoice(women
                        ? properties.getDefaultWomensCategoryId()
                        : properties.getDefaultMensCategoryId(),
                        women ? "Women's Athletic Shoes" : "Men's Athletic Shoes");
            }
            throw new IllegalArgumentException("无法自动识别eBay类目，请在Excel填写分类ID");
        });
    }

    private CategoryChoice firstShoeSuggestion(JSONObject response) {
        JSONArray suggestions = response == null ? null : response.getJSONArray("categorySuggestions");
        if (suggestions == null) {
            return null;
        }
        for (Object value : suggestions) {
            if (!(value instanceof JSONObject suggestion)) {
                continue;
            }
            JSONObject category = suggestion.getJSONObject("category");
            if (category == null) {
                continue;
            }
            String categoryId = category.getString("categoryId");
            String categoryName = category.getString("categoryName");
            if (categoryId == null || !categoryId.matches("[0-9]{1,20}")
                    || categoryName == null || categoryName.isBlank()
                    || !isShoePath(suggestion, categoryName)) {
                continue;
            }
            return new CategoryChoice(categoryId, categoryName.trim());
        }
        return null;
    }

    private boolean isShoePath(JSONObject suggestion, String categoryName) {
        StringBuilder path = new StringBuilder(categoryName);
        JSONArray ancestors = suggestion.getJSONArray("categoryTreeNodeAncestors");
        if (ancestors != null) {
            for (Object value : ancestors) {
                if (value instanceof JSONObject ancestor) {
                    path.append(' ').append(ancestor.getString("categoryName"));
                }
            }
        }
        String normalized = path.toString().toLowerCase(Locale.ROOT);
        return normalized.contains("shoe") || normalized.contains("footwear")
                || normalized.contains("sneaker");
    }

    private List<AspectRule> loadAspectRules(String categoryId) {
        return aspectCache.computeIfAbsent(categoryId, ignored -> {
            try {
                return parseAspectRules(client.getItemAspectsForCategory(
                        properties.getDefaultCategoryTreeId(), categoryId));
            } catch (EbayApiException ignoredError) {
                return List.of();
            }
        });
    }

    private List<AspectRule> parseAspectRules(JSONObject response) {
        JSONArray aspects = response == null ? null : response.getJSONArray("aspects");
        if (aspects == null) {
            return List.of();
        }
        List<AspectRule> rules = new ArrayList<>();
        for (Object value : aspects) {
            if (!(value instanceof JSONObject aspect)) {
                continue;
            }
            String name = aspect.getString("localizedAspectName");
            if (name == null || name.isBlank() || name.length() > 65) {
                continue;
            }
            JSONObject constraint = aspect.getJSONObject("aspectConstraint");
            boolean required = constraint != null && constraint.getBooleanValue("aspectRequired");
            String mode = constraint == null ? null : constraint.getString("aspectMode");
            int maxLength = constraint == null || constraint.getIntValue("aspectMaxLength") <= 0
                    ? 65 : Math.min(65, constraint.getIntValue("aspectMaxLength"));
            List<String> allowedValues = Optional.ofNullable(aspect.getJSONArray("aspectValues"))
                    .map(array -> array.toJavaList(JSONObject.class))
                    .orElse(List.of()).stream()
                    .filter(Objects::nonNull)
                    .map(item -> item.getString("localizedValue"))
                    .filter(item -> item != null && !item.isBlank() && item.length() <= 65)
                    .toList();
            rules.add(new AspectRule(name.trim(), required, mode, maxLength, allowedValues));
        }
        return List.copyOf(rules);
    }

    private Map<String, List<String>> buildAspects(List<AspectRule> rules,
                                                    EbayProductMetadata metadata,
                                                    String styleCode, String sizeSystem,
                                                    String sizeValue, String department) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (AspectRule rule : rules) {
            String candidate = aspectValue(rule.name(), metadata, styleCode,
                    sizeSystem, sizeValue, department);
            String normalized = normalizeAllowedValue(rule, candidate);
            if (normalized == null || normalized.isBlank()) {
                if (rule.required()) {
                    missing.add(rule.name());
                }
                continue;
            }
            result.put(rule.name(), List.of(limit(normalized.trim(), rule.maxLength())));
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("当前eBay类目缺少必填属性："
                    + String.join("、", missing) + "；请在商品资料库补充后重试");
        }
        if (result.isEmpty()) {
            return fallbackAspects(metadata, styleCode, sizeSystem, sizeValue, department);
        }
        return result;
    }

    private String aspectValue(String rawName, EbayProductMetadata metadata,
                               String styleCode, String sizeSystem,
                               String sizeValue, String department) {
        String name = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (name) {
            case "brand", "marke" -> metadata.getBrand();
            case "department", "gender", "abteilung" -> department;
            case "usshoesize" -> sizeSystem.startsWith("US") ? sizeValue : null;
            case "eushoesize" -> "EU".equals(sizeSystem) ? sizeValue : null;
            case "color", "colour", "farbe" -> firstPresent(metadata.getColor(), metadata.getColorway());
            case "uppermaterial", "obermaterial" -> metadata.getUpperMaterial();
            case "type", "producttype", "style", "stil", "produktart" -> metadata.getProductType();
            case "stylecode", "mpn" -> styleCode;
            case "model", "modell", "modelname", "modellbezeichnung" -> metadata.getModelName();
            case "productline", "produktlinie" -> metadata.getProductLine();
            case "countryoforigin", "countryregionofmanufacture", "ursprungsland" -> metadata.getCountryOfOrigin();
            default -> null;
        };
    }

    private String normalizeAllowedValue(AspectRule rule, String candidate) {
        if (candidate == null || candidate.isBlank() || rule.allowedValues().isEmpty()
                || !"SELECTION_ONLY".equalsIgnoreCase(rule.mode())) {
            return candidate;
        }
        return rule.allowedValues().stream()
                .filter(value -> value.equalsIgnoreCase(candidate.trim()))
                .findFirst()
                .orElseGet(() -> synonym(rule.allowedValues(), candidate));
    }

    private String synonym(List<String> allowedValues, String candidate) {
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (normalized.contains("sneaker")) {
            return allowedValues.stream()
                    .filter(value -> value.equalsIgnoreCase("Sneaker")
                            || value.equalsIgnoreCase("Athletic"))
                    .findFirst().orElse(null);
        }
        return null;
    }

    private Map<String, List<String>> fallbackAspects(EbayProductMetadata metadata,
                                                       String styleCode, String sizeSystem,
                                                       String sizeValue, String department) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        put(result, "Brand", metadata.getBrand());
        if (sizeSystem.startsWith("US")) {
            put(result, "US Shoe Size", sizeValue);
        } else {
            put(result, "EU Shoe Size", sizeValue);
        }
        put(result, "Department", department);
        put(result, "Color", metadata.getColor());
        put(result, "Upper Material", metadata.getUpperMaterial());
        return result;
    }

    private void put(Map<String, List<String>> target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.put(name, List.of(limit(value.trim(), 65)));
        }
    }

    private String department(String sizeSystem, String metadataGender) {
        if ("USW".equals(sizeSystem)) {
            return "Women";
        }
        if ("USM".equals(sizeSystem)) {
            return "Men";
        }
        String normalized = metadataGender == null ? "" : metadataGender.toLowerCase(Locale.ROOT);
        if (normalized.contains("women") || normalized.contains("female")) {
            return "Women";
        }
        if (normalized.contains("men") || normalized.contains("male")) {
            return "Men";
        }
        return null;
    }

    private boolean isStandardShoe(String productType) {
        if (productType == null) {
            return false;
        }
        String normalized = productType.toLowerCase(Locale.ROOT);
        return Arrays.stream(new String[]{"shoe", "sneaker", "footwear", "trainer"})
                .anyMatch(normalized::contains);
    }

    private String numericId(String value, String label) {
        String normalized = nonBlank(value, label);
        if (!normalized.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException(label + "必须是数字");
        }
        return normalized;
    }

    private String nonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }

    private String firstPresent(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record ResolvedTaxonomy(String categoryId, String categoryName,
                                   Map<String, List<String>> aspects) {
    }

    private record CategoryChoice(String id, String name) {
    }

    private record AspectRule(String name, boolean required, String mode,
                              int maxLength, List<String> allowedValues) {
    }
}
