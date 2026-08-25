package cn.ken.shoes.service;

import cn.ken.shoes.model.ebay.EbayProductMetadata;
import org.springframework.stereotype.Service;

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
        if (!present(styleId) || containsPhrase(sourceTitle, styleId)) {
            return limitAtWord(sourceTitle, MAX_TITLE_LENGTH);
        }
        int titleLimit = MAX_TITLE_LENGTH - styleId.length() - 1;
        return limitAtWord(sourceTitle, Math.max(1, titleLimit)) + " " + styleId;
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
