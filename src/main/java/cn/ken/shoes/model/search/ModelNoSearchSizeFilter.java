package cn.ken.shoes.model.search;

import cn.hutool.core.util.StrUtil;
import cn.ken.shoes.model.excel.ModelNoSearchExcel;
import cn.ken.shoes.util.ShoesUtil;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ModelNoSearchSizeFilter {

    private static final String US_W_PREFIX = "USW:";
    private static final String US_M_PREFIX = "USM:";
    private static final String EU_PREFIX = "EU:";

    private ModelNoSearchSizeFilter() {
    }

    public static Map<String, Set<String>> build(List<ModelNoSearchExcel> rows) {
        Map<String, Set<String>> filters = new LinkedHashMap<>();
        Set<String> unrestrictedModels = new LinkedHashSet<>();
        if (rows == null) {
            return filters;
        }
        for (ModelNoSearchExcel row : rows) {
            if (row == null || StrUtil.isBlank(row.getModelNo())) {
                continue;
            }
            String modelNo = normalizeModelNo(row.getModelNo());
            String size = normalizeRequestedSize(row.getSize());
            if (size == null) {
                unrestrictedModels.add(modelNo);
                filters.remove(modelNo);
            } else if (!unrestrictedModels.contains(modelNo)) {
                filters.computeIfAbsent(modelNo, ignored -> new LinkedHashSet<>()).add(size);
            }
        }
        return filters;
    }

    public static boolean matches(Map<String, Set<String>> filters, String modelNo,
                                  String usSize, String euSize) {
        return matches(filters, modelNo, usSize, null, euSize);
    }

    public static boolean matches(Map<String, Set<String>> filters, String modelNo,
                                  String usmSize, String uswSize, String euSize) {
        if (filters == null || filters.isEmpty() || StrUtil.isBlank(modelNo)) {
            return true;
        }
        Set<String> requestedSizes = filters.get(normalizeModelNo(modelNo));
        if (requestedSizes == null || requestedSizes.isEmpty()) {
            return true;
        }
        String normalizedUsmSize = normalizeCandidateSize(usmSize);
        String normalizedUswSize = normalizeCandidateSize(uswSize);
        String normalizedEuSize = normalizeCandidateSize(euSize);
        return requestedSizes.stream().anyMatch(requestedSize -> {
            if (requestedSize.startsWith(US_W_PREFIX)) {
                return requestedSize.substring(US_W_PREFIX.length()).equals(normalizedUswSize);
            }
            if (requestedSize.startsWith(US_M_PREFIX)) {
                return requestedSize.substring(US_M_PREFIX.length()).equals(normalizedUsmSize);
            }
            if (requestedSize.startsWith(EU_PREFIX)) {
                return requestedSize.substring(EU_PREFIX.length()).equals(normalizedEuSize);
            }
            // 兼容无前缀输入和旧任务快照：仍按原逻辑同时尝试 US 默认码与 EU 码。
            return requestedSize.equals(normalizedUsmSize) || requestedSize.equals(normalizedEuSize);
        });
    }

    public static boolean isWomenSize(String size) {
        String normalized = normalizeRequestedSize(size);
        return normalized != null && normalized.startsWith(US_W_PREFIX);
    }

    private static String normalizeModelNo(String modelNo) {
        return modelNo.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRequestedSize(String size) {
        if (StrUtil.isBlank(size)) {
            return null;
        }
        String normalized = ShoesUtil.normalizeUnicodeFraction(size.trim().toUpperCase(Locale.ROOT))
                .replaceAll("\\s+", "");
        if (normalized.startsWith("USW")) {
            return US_W_PREFIX + normalizeCoreSize(normalized.substring(3));
        }
        if (normalized.startsWith("W")) {
            return US_W_PREFIX + normalizeCoreSize(normalized.substring(1));
        }
        if (normalized.endsWith("W")) {
            return US_W_PREFIX + normalizeCoreSize(normalized.substring(0, normalized.length() - 1));
        }
        if (normalized.startsWith("USM")) {
            return US_M_PREFIX + normalizeCoreSize(normalized.substring(3));
        }
        if (normalized.startsWith("EU")) {
            return EU_PREFIX + normalizeCoreSize(normalized.substring(2));
        }
        if (normalized.startsWith("US")) {
            return US_M_PREFIX + normalizeCoreSize(normalized.substring(2));
        }
        return normalizeCoreSize(normalized);
    }

    private static String normalizeCandidateSize(String size) {
        if (StrUtil.isBlank(size)) {
            return null;
        }
        String normalized = ShoesUtil.normalizeUnicodeFraction(size.trim().toUpperCase(Locale.ROOT))
                .replaceAll("\\s+", "")
                .replaceFirst("^(USW|USM|US|EU|W)", "");
        if (normalized.endsWith("W")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalizeCoreSize(normalized);
    }

    private static String normalizeCoreSize(String normalized) {
        if (normalized.matches("\\d+\\.0+")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        return normalized;
    }
}
