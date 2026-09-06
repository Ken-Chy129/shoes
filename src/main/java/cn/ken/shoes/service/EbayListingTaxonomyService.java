package cn.ken.shoes.service;

import cn.ken.shoes.client.EbayApiException;
import cn.ken.shoes.client.EbayTaxonomyApiClient;
import cn.ken.shoes.config.EbayProperties;
import cn.ken.shoes.model.ebay.EbayProductMetadata;
import cn.ken.shoes.util.SizeConvertUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        String department = department(sizeSystem, metadata.getGender(), metadata.getTitle());
        if ((categoryOverride == null || categoryOverride.isBlank())
                && "EU".equals(sizeSystem) && department == null) {
            throw new IllegalArgumentException(
                    "EU尺码无法自动判断男鞋或女鞋，请在商品资料库补充性别，或在Excel填写分类ID");
        }
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
            } catch (EbayApiException | IllegalStateException ignoredError) {
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
            } catch (EbayApiException | IllegalStateException ignoredError) {
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
            List<String> allowedValues = parseAllowedValues(aspect.getJSONArray("aspectValues"));
            rules.add(new AspectRule(name.trim(), required, mode, maxLength, allowedValues));
        }
        return List.copyOf(rules);
    }

    private List<String> parseAllowedValues(JSONArray values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject item)) {
                continue;
            }
            String localizedValue = item.getString("localizedValue");
            if (localizedValue != null && !localizedValue.isBlank()
                    && localizedValue.length() <= 65) {
                result.add(localizedValue);
            }
        }
        return List.copyOf(result);
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
            // eBay uses different localized names for the same shoe-size
            // dimension across categories. Athletic shoes expose "US Shoe
            // Size", while soccer cleats (for example category 109133) use
            // the shorter "US Size".
            case "usshoesize", "ussize" -> usShoeSize(
                    metadata, sizeSystem, sizeValue, department);
            case "eushoesize", "eusize" -> "EU".equals(sizeSystem) ? sizeValue : null;
            case "color", "colour", "farbe" -> color(metadata);
            case "uppermaterial", "obermaterial" -> firstPresent(
                    metadata.getUpperMaterial(), inferredUpperMaterial(metadata.getTitle()));
            case "style", "stil" -> firstPresent(
                    metadata.getProductType(), inferredStyle(metadata.getTitle()));
            case "type", "producttype", "produktart" -> firstPresent(
                    metadata.getProductType(), "Athletic");
            case "stylecode", "mpn" -> styleCode;
            case "model", "modell", "modelname", "modellbezeichnung" -> metadata.getModelName();
            case "productline", "produktlinie" -> metadata.getProductLine();
            case "countryoforigin", "countryregionofmanufacture", "ursprungsland" -> metadata.getCountryOfOrigin();
            default -> null;
        };
    }

    private String normalizeAllowedValue(AspectRule rule, String candidate) {
        if (candidate == null || candidate.isBlank() || rule.allowedValues().isEmpty()
                || !usesStandardValues(rule)) {
            return candidate;
        }
        String exact = rule.allowedValues().stream()
                .filter(value -> value.equalsIgnoreCase(candidate.trim()))
                .findFirst()
                .orElse(null);
        if (exact != null) {
            return exact;
        }
        String normalized = rule.allowedValues().stream()
                .filter(value -> normalizedValue(value)
                        .equals(normalizedValue(candidate)))
                .findFirst()
                .orElse(null);
        return normalized != null ? normalized : synonym(rule, candidate);
    }

    private String synonym(AspectRule rule, String candidate) {
        List<String> allowedValues = rule.allowedValues();
        if (isColorAspect(rule.name())) {
            String extracted = EbayTitleColorExtractor.extract(candidate);
            if (extracted != null) {
                for (String color : extracted.split("/")) {
                    String match = allowedValues.stream()
                            .filter(value -> value.equalsIgnoreCase(color)
                                    || color.equalsIgnoreCase(EbayTitleColorExtractor.extract(value)))
                            .findFirst()
                            .orElse(null);
                    if (match != null) {
                        return match;
                    }
                }
            }
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (isDepartmentAspect(rule.name())) {
            if (normalized.contains("women") || normalized.contains("female")) {
                return allowedValue(allowedValues, "Women");
            }
            if (normalized.contains("kid") || normalized.contains("youth")
                    || normalized.contains("grade school")) {
                return firstAllowedValue(allowedValues,
                        "Unisex Kids", "Girls", "Boys", "Kids");
            }
            if (normalized.contains("unisex")) {
                return firstAllowedValue(allowedValues, "Unisex Adults", "Unisex");
            }
            if (normalized.contains("men") || normalized.contains("male")) {
                return allowedValue(allowedValues, "Men");
            }
        }
        if (isStyleOrTypeAspect(rule.name())
                && (normalized.contains("sneaker") || normalized.contains("shoe"))) {
            return allowedValues.stream()
                    .filter(value -> value.equalsIgnoreCase("Sneaker")
                            || value.equalsIgnoreCase("Athletic"))
                    .findFirst().orElse(null);
        }
        if (isStyleOrTypeAspect(rule.name())
                && (normalized.contains("slide") || normalized.contains("slipper")
                || normalized.contains("sandal") || normalized.contains("mule"))) {
            return firstAllowedValue(allowedValues,
                    "Slide", "Slides", "Sandal", "Slipper", "Mule", "Athletic");
        }
        return null;
    }

    private boolean usesStandardValues(AspectRule rule) {
        return "SELECTION_ONLY".equalsIgnoreCase(rule.mode())
                || isUsSizeAspect(rule.name())
                || isColorAspect(rule.name())
                || isDepartmentAspect(rule.name())
                || isStyleOrTypeAspect(rule.name())
                || isUpperMaterialAspect(rule.name());
    }

    private boolean isUsSizeAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "usshoesize".equals(name) || "ussize".equals(name);
    }

    private boolean isColorAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "color".equals(name) || "colour".equals(name) || "farbe".equals(name);
    }

    private boolean isDepartmentAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "department".equals(name) || "gender".equals(name) || "abteilung".equals(name);
    }

    private boolean isStyleOrTypeAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "style".equals(name) || "stil".equals(name)
                || "type".equals(name) || "producttype".equals(name)
                || "produktart".equals(name);
    }

    private boolean isUpperMaterialAspect(String rawName) {
        String name = normalizedAspectName(rawName);
        return "uppermaterial".equals(name) || "obermaterial".equals(name);
    }

    private String normalizedAspectName(String rawName) {
        return rawName == null ? ""
                : rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String normalizedValue(String value) {
        return value == null ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String usShoeSize(EbayProductMetadata metadata, String sizeSystem,
                              String sizeValue, String department) {
        String targetGender = "Women".equals(department) ? "women" : "men";
        if ("EU".equals(sizeSystem)) {
            return SizeConvertUtil.getKcUsSize(
                    metadata.getBrand(), targetGender, sizeValue);
        }
        boolean inputMatchesDepartment = ("USW".equals(sizeSystem) && "Women".equals(department))
                || ("USM".equals(sizeSystem) && !"Women".equals(department));
        if (inputMatchesDepartment) {
            return sizeValue;
        }
        String euSize = SizeConvertUtil.getKcEuSizeFromUs(
                metadata.getBrand(), sizeSystem, sizeValue);
        String converted = SizeConvertUtil.getKcUsSize(
                metadata.getBrand(), targetGender, euSize);
        return converted == null || converted.isBlank() ? sizeValue : converted;
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

    private String department(String sizeSystem, String metadataGender, String title) {
        String normalized = metadataGender == null ? "" : metadataGender.toLowerCase(Locale.ROOT);
        if (normalized.contains("women") || normalized.contains("female")) {
            return "Women";
        }
        if (normalized.contains("men") || normalized.contains("male")) {
            return "Men";
        }
        if (normalized.contains("unisex")) {
            return "Unisex Adults";
        }
        if (normalized.contains("kid") || normalized.contains("youth")
                || normalized.contains("grade school")) {
            return "Unisex Kids";
        }
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (normalizedTitle.contains("(wmns)") || normalizedTitle.contains("(women")
                || normalizedTitle.contains(" women's") || normalizedTitle.startsWith("women")) {
            return "Women";
        }
        if (normalizedTitle.contains("(gs)") || normalizedTitle.contains("grade school")
                || normalizedTitle.contains("(kids)")) {
            return "Unisex Kids";
        }
        if ("USW".equals(sizeSystem)) {
            return "Women";
        }
        if ("USM".equals(sizeSystem)) {
            return "Men";
        }
        return null;
    }

    private String color(EbayProductMetadata metadata) {
        String value = firstPresent(EbayTitleColorExtractor.extract(metadata.getColor()),
                EbayTitleColorExtractor.extract(metadata.getColorway()));
        value = firstPresent(value, EbayTitleColorExtractor.extract(metadata.getTitle()));
        return firstPresent(value, "Multicolor");
    }

    private String inferredStyle(String title) {
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (normalized.contains("slide") || normalized.contains("slipper")
                || normalized.contains("mule")) {
            return "Slide";
        }
        return "Sneaker";
    }

    private String inferredUpperMaterial(String title) {
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (normalized.contains("slide") || normalized.contains("foam rnr")
                || normalized.contains("foam runner") || normalized.contains("clog")) {
            return "Rubber";
        }
        return "Leather";
    }

    private String allowedValue(List<String> allowedValues, String expected) {
        return allowedValues.stream()
                .filter(value -> value.equalsIgnoreCase(expected))
                .findFirst()
                .orElse(null);
    }

    private String firstAllowedValue(List<String> allowedValues, String... candidates) {
        for (String candidate : candidates) {
            String match = allowedValue(allowedValues, candidate);
            if (match != null) {
                return match;
            }
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
