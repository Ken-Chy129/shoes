package cn.ken.shoes.service;

import cn.ken.shoes.model.ebay.EbayProductMetadata;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class EbayTitleService {

    private static final int MAX_TITLE_LENGTH = 80;

    public String generate(String rawStyleId, String sizeSystem,
                           EbayProductMetadata metadata) {
        if (metadata == null || metadata.getTitle() == null
                || metadata.getTitle().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String sourceTitle = clean(metadata.getTitle());
        if (metadata.isManualTitle()) {
            return limitAtWord(sourceTitle, MAX_TITLE_LENGTH);
        }

        String styleId = clean(rawStyleId).toUpperCase(Locale.ROOT);
        String prefix = sourceTitle;
        if (present(metadata.getBrand()) && !containsPhrase(prefix, metadata.getBrand())) {
            prefix = clean(metadata.getBrand()) + " " + prefix;
        }
        if (present(metadata.getColorway()) && !containsPhrase(prefix, metadata.getColorway())) {
            prefix += " " + clean(metadata.getColorway()).replace('/', ' ');
        }

        List<String> suffix = new ArrayList<>();
        addUnlessPresent(suffix, prefix, styleId);
        addUnlessPresent(suffix, prefix, genderLabel(metadata.getGender(), sizeSystem));
        if (!containsAnyProductType(prefix)) {
            String productType = present(metadata.getProductType())
                    ? clean(metadata.getProductType()) : "Sneakers";
            addUnlessPresent(suffix, prefix, productType);
        }
        addUnlessPresent(suffix, prefix, "New");

        String suffixText = String.join(" ", suffix);
        int prefixLimit = suffixText.isEmpty()
                ? MAX_TITLE_LENGTH : MAX_TITLE_LENGTH - suffixText.length() - 1;
        String fittedPrefix = limitAtWord(prefix, Math.max(1, prefixLimit));
        String result = suffixText.isEmpty()
                ? fittedPrefix : fittedPrefix + " " + suffixText;
        return limitAtWord(result, MAX_TITLE_LENGTH);
    }

    private void addUnlessPresent(List<String> suffix, String existing, String value) {
        if (present(value) && !containsPhrase(existing, value)
                && suffix.stream().noneMatch(item -> containsPhrase(item, value))) {
            suffix.add(clean(value));
        }
    }

    private String genderLabel(String rawGender, String sizeSystem) {
        String normalized = normalize(rawGender);
        if (normalized.contains("women") || normalized.contains("female")
                || normalized.contains("girl")) {
            return "Women's";
        }
        if (normalized.contains("men") || normalized.contains("male")
                || normalized.contains("boy")) {
            return "Men's";
        }
        if (normalized.contains("unisex")) {
            return "Unisex";
        }
        if ("USW".equalsIgnoreCase(sizeSystem)) {
            return "Women's";
        }
        if ("USM".equalsIgnoreCase(sizeSystem)) {
            return "Men's";
        }
        return "Unisex";
    }

    private boolean containsAnyProductType(String value) {
        String normalized = " " + clean(value).toLowerCase(Locale.ROOT) + " ";
        return normalized.matches(".*\\b(shoe|shoes|sneaker|sneakers|trainer|trainers|boot|boots|sandal|sandals)\\b.*");
    }

    private boolean containsPhrase(String existing, String candidate) {
        String normalizedCandidate = normalize(candidate);
        return !normalizedCandidate.isEmpty() && normalize(existing).contains(normalizedCandidate);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String limitAtWord(String value, int maxLength) {
        String cleaned = clean(value);
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        String candidate = cleaned.substring(0, maxLength + 1);
        int lastSpace = candidate.lastIndexOf(' ');
        return lastSpace >= Math.max(1, maxLength / 2)
                ? candidate.substring(0, lastSpace).trim()
                : cleaned.substring(0, maxLength).trim();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
