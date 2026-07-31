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
            String size = normalizeSize(row.getSize());
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
        if (filters == null || filters.isEmpty() || StrUtil.isBlank(modelNo)) {
            return true;
        }
        Set<String> requestedSizes = filters.get(normalizeModelNo(modelNo));
        if (requestedSizes == null || requestedSizes.isEmpty()) {
            return true;
        }
        return requestedSizes.contains(normalizeSize(usSize))
                || requestedSizes.contains(normalizeSize(euSize));
    }

    private static String normalizeModelNo(String modelNo) {
        return modelNo.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSize(String size) {
        if (StrUtil.isBlank(size)) {
            return null;
        }
        String normalized = size.trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^(US|EU)\\s*", "");
        normalized = ShoesUtil.normalizeUnicodeFraction(normalized).replaceAll("\\s+", "");
        if (normalized.matches("\\d+\\.0+")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        return normalized;
    }
}
