package cn.ken.shoes.service;

import cn.ken.shoes.model.ebay.EbayProductMetadata;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class EbayTitleService {

    private static final int MAX_TITLE_LENGTH = 80;
    private static final String GRADE_SCHOOL = "Grade School";
    private static final String PRESCHOOL = "Preschool";
    private static final String TODDLER = "Toddler";

    public String generate(String rawStyleId, String sizeSystem,
                           EbayProductMetadata metadata) {
        return generate(rawStyleId, sizeSystem, metadata, null);
    }

    /**
     * 生成 eBay 标题：KC 标题 + 性别 + 货号，例如 "Nike Dunk Low Men's DD1391-100"。
     * 性别取自已解析出的 eBay Department：Men's / Women's，男女同款(Unisex)按 Men's；
     * 童鞋再按标题里的年龄段标记细分为 Grade School(GS 大童) / Preschool(PS 小童) /
     * Toddler(TD 婴幼童)。手工标题保持原样。
     */
    public String generate(String rawStyleId, String sizeSystem,
                           EbayProductMetadata metadata, String department) {
        if (metadata == null || metadata.getTitle() == null
                || metadata.getTitle().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String sourceTitle = clean(metadata.getTitle());
        if (metadata.isManualTitle()) {
            return limitAtWord(sourceTitle, MAX_TITLE_LENGTH);
        }

        String styleId = clean(rawStyleId).toUpperCase(Locale.ROOT);
        String gender = genderLabel(department, sizeSystem, sourceTitle);
        if (!present(styleId)) {
            return limitAtWord(sourceTitle, MAX_TITLE_LENGTH);
        }
        if (containsPhrase(sourceTitle, styleId)) {
            int index = sourceTitle.toUpperCase(Locale.ROOT).lastIndexOf(styleId);
            if (gender == null || index < 0) {
                return limitAtWord(sourceTitle, MAX_TITLE_LENGTH);
            }
            String head = sourceTitle.substring(0, index).trim();
            String tail = sourceTitle.substring(index).trim();
            return limitedWithSuffix(stripTrailingGender(head), gender + " " + tail);
        }
        String suffix = gender == null ? styleId : gender + " " + styleId;
        return limitedWithSuffix(gender == null ? sourceTitle : stripTrailingGender(sourceTitle), suffix);
    }

    private String limitedWithSuffix(String head, String suffix) {
        if (!present(head)) {
            return limitAtWord(suffix, MAX_TITLE_LENGTH);
        }
        int titleLimit = MAX_TITLE_LENGTH - suffix.length() - 1;
        return limitAtWord(head, Math.max(1, titleLimit)) + " " + suffix;
    }

    /** 去掉标题末尾已有的性别词，避免出现 "... Men's Men's DD1391-100"。 */
    private String stripTrailingGender(String title) {
        return title.replaceAll("(?i)\\s*\\b(men's|women's|mens|womens|kids'|grade school|preschool|pre-school|toddler)\\s*$", "").trim();
    }

    String genderLabel(String department, String sizeSystem) {
        return genderLabel(department, sizeSystem, null);
    }

    String genderLabel(String department, String sizeSystem, String title) {
        String normalized = department == null ? "" : department.toLowerCase(Locale.ROOT);
        String normalizedTitle = title == null ? "" : title.toLowerCase(Locale.ROOT);
        boolean baby = normalized.contains("baby") || normalized.contains("toddler")
                || normalized.contains("infant");
        boolean kids = baby || normalized.contains("kid") || normalized.contains("youth")
                || normalized.contains("grade school");
        if (kids) {
            // eBay Department 只分 Kids / Baby & Toddler，具体年龄段看标题标记。
            if (hasToddlerMarker(normalizedTitle)) {
                return TODDLER;
            }
            if (hasPreschoolMarker(normalizedTitle)) {
                return PRESCHOOL;
            }
            if (hasGradeSchoolMarker(normalizedTitle)) {
                return GRADE_SCHOOL;
            }
            return baby ? TODDLER : GRADE_SCHOOL;
        }
        if (normalized.contains("women") || normalized.contains("female")) {
            return "Women's";
        }
        if (normalized.contains("men") || normalized.contains("male")
                || normalized.contains("unisex")) {
            return "Men's";
        }
        if ("USW".equals(sizeSystem)) {
            return "Women's";
        }
        if ("USM".equals(sizeSystem)) {
            return "Men's";
        }
        return null;
    }

    private boolean hasToddlerMarker(String title) {
        return title.contains("(td)") || title.contains("toddler") || title.contains("infant")
                || title.contains("(baby)") || title.contains("crib");
    }

    private boolean hasPreschoolMarker(String title) {
        return title.contains("(ps)") || title.contains("preschool")
                || title.contains("pre-school") || title.contains("little kid");
    }

    private boolean hasGradeSchoolMarker(String title) {
        return title.contains("(gs)") || title.contains("grade school")
                || title.contains("big kid") || title.contains("(youth)");
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
