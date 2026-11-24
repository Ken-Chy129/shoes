package cn.ken.shoes.util;

import cn.ken.shoes.common.Gender;
import cn.ken.shoes.common.PlatformEnum;
import cn.ken.shoes.model.entity.SizeChartDO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 尺码转换工具类
 * 根据品牌名和尺码（美码）转换为欧码
 */
@Slf4j
public class SizeConvertUtil {

    /**
     * 缓存Map，key: stockxBrand:gender:usSize，value: euSize
     */
    private static final Map<String, String> STOCKX_SIZE_CACHE = new HashMap<>();

    /**
     * Dunk品牌尺码缓存，key: dunkBrand:gender:cmSize，value: euSize
     */
    private static final Map<String, String> DUNK_SIZE_CACHE = new HashMap<>();

    /**
     * 品牌-性别-尺码对照表缓存
     */
    @Getter
    private static final Map<String, Map<String, List<SizeChartDO>>> KC_SIZE_CACHE = new HashMap<>();

    /**
     * 初始化尺码转换缓存
     * 应在应用启动时调用
     *
     * @param sizeChartList 尺码对照表数据
     */
    public static void initCache(List<SizeChartDO> sizeChartList) {
        STOCKX_SIZE_CACHE.clear();
        DUNK_SIZE_CACHE.clear();
        KC_SIZE_CACHE.clear();

        for (SizeChartDO sizeChart : sizeChartList) {
            String kcBrand = sizeChart.getBrand();
            String stockxBrand = sizeChart.getStockxBrand();
            String dunkBrand = sizeChart.getDunkBrand();
            String gender = sizeChart.getGender();
            String euSize = sizeChart.getEuSize();
            String usSize = sizeChart.getUsSize();
            String cmSize = sizeChart.getCmSize();

            // 构建 brandGenderSizeChartMap
            KC_SIZE_CACHE
                    .computeIfAbsent(kcBrand, k -> new HashMap<>())
                    .computeIfAbsent(gender, k -> new ArrayList<>())
                    .add(sizeChart);

            String menUSSize = sizeChart.getMenUSSize();
            String womenUSSize = sizeChart.getWomenUSSize();

            // 本性别尺码：女款行的 us_size 多数填的是男码，女码要取 women_us_size
            String ownUsSize = Gender.WOMENS.name().equals(gender) ? firstNonBlank(womenUSSize, usSize)
                    : Gender.MENS.name().equals(gender) ? firstNonBlank(menUSSize, usSize)
                    : usSize;
            if (isPresent(ownUsSize)) {
                STOCKX_SIZE_CACHE.put(buildKey(stockxBrand, gender, ownUsSize), euSize);
            }

            // 跨性别列只作兜底，不覆盖本性别行
            if (menUSSize != null && !menUSSize.isEmpty()) {
                String key = buildKey(stockxBrand, Gender.MENS.name(), menUSSize);
                STOCKX_SIZE_CACHE.putIfAbsent(key, euSize);
            }
            if (womenUSSize != null && !womenUSSize.isEmpty()) {
                String key = buildKey(stockxBrand, Gender.WOMENS.name(), womenUSSize);
                STOCKX_SIZE_CACHE.putIfAbsent(key, euSize);
            }

            // Dunk品牌 cmSize -> euSize
            if (dunkBrand != null && !dunkBrand.isEmpty() && cmSize != null && !cmSize.isEmpty()) {
                String dunkKey = buildKey(dunkBrand, gender, cmSize);
                DUNK_SIZE_CACHE.putIfAbsent(dunkKey, euSize);
            }
        }
    }

    /**
     * 根据品牌和尺码获取欧码
     *
     * @param brand 品牌名
     * @param size  尺码（美码），支持格式：10、10.5、10W、10Y、10C
     * @return 欧码，找不到返回null
     */
    public static String getStockXEuSize(String brand, String size) {
        if (brand == null || size == null || size.isEmpty()) {
            return null;
        }

        // 提取性别
        Gender gender = GenderUtil.extractGender(PlatformEnum.STOCKX, size);
        // StockX 女码形如 9.5W，尺码表里存的是不带 W 的数字
        String lookupSize = gender == Gender.WOMENS ? size.replace("W", "").trim() : size;

        String key = buildKey(brand, gender.name(), lookupSize);
        String euSize = STOCKX_SIZE_CACHE.get(key);

        // 因为K和C对应Baby和Kids不好区分，这里提取性别时默认返回Baby，然后再兜底从Kids查询
        if (euSize == null && gender == Gender.BABY) {
            String kidsKey = buildKey(brand, Gender.KIDS.name(), size);
            return STOCKX_SIZE_CACHE.get(kidsKey);
        }

        return euSize;
    }

    /**
     * 根据Dunk品牌、性别和CM尺码获取欧码
     *
     * @param dunkBrand Dunk品牌名
     * @param gender    性别
     * @param cmSize    CM尺码（如 "27cm" 或 "27"）
     * @return 欧码，找不到返回null
     */
    public static String getDunkEuSize(String dunkBrand, Gender gender, String cmSize) {
        if (dunkBrand == null || gender == null || cmSize == null || cmSize.isEmpty()) {
            return null;
        }
        String finalCmSize = cmSize.replace("cm", "").trim();
        String key = buildKey(dunkBrand, gender.name(), finalCmSize);
        return DUNK_SIZE_CACHE.get(key);
    }

    /**
     * 根据 KC 品牌尺码表把欧码转换为指定性别的美国码。
     *
     * @param brand   KC 品牌名
     * @param gender  商品性别，支持 men/mens/women/womens 等写法
     * @param euSize  欧码
     * @return 美国码，找不到时返回 null
     */
    public static String getKcUsSize(String brand, String gender, String euSize) {
        if (brand == null || brand.isBlank() || euSize == null || euSize.isBlank()) {
            return null;
        }
        String normalizedGender = normalizeGender(gender);
        if (normalizedGender == null) {
            return null;
        }
        Map<String, List<SizeChartDO>> brandCharts = findBrandCharts(brand);
        if (brandCharts == null) {
            return null;
        }
        List<SizeChartDO> charts = brandCharts.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(normalizedGender))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
        String normalizedEuSize = normalizeSize(euSize);
        for (SizeChartDO chart : charts) {
            if (!normalizedEuSize.equals(normalizeSize(chart.getEuSize()))) {
                continue;
            }
            // 童鞋/婴童尺码表只有通用 usSize 一列（如 5.5Y、10C）。
            String usSize = Gender.MENS.name().equals(normalizedGender) ? chart.getMenUSSize()
                    : Gender.WOMENS.name().equals(normalizedGender) ? chart.getWomenUSSize()
                    : null;
            if (usSize == null || usSize.isBlank()) {
                usSize = chart.getUsSize();
            }
            return usSize == null || usSize.isBlank() ? null : usSize.trim();
        }
        return null;
    }

    /**
     * 根据 KC 尺码表生成 eBay 兼容的男女合并尺码展示值。
     * 当同一欧码同时存在男码和女码时，返回例如 "9 Men/10.5 Women"；
     * 只有单边尺码时返回原始单一美国码，避免改变单性别商品的既有行为。
     */
    public static String getKcUsSizeDisplay(String brand, String gender, String euSize) {
        if (brand == null || brand.isBlank() || euSize == null || euSize.isBlank()) {
            return null;
        }
        Map<String, List<SizeChartDO>> brandCharts = findBrandCharts(brand);
        if (brandCharts == null) {
            return getKcUsSize(brand, gender, euSize);
        }

        String normalizedEuSize = normalizeSize(euSize);
        String menSize = null;
        String womenSize = null;
        for (SizeChartDO chart : preferredFirst(brandCharts, normalizeGender(gender))) {
            if (!normalizedEuSize.equals(normalizeSize(chart.getEuSize()))) {
                continue;
            }
            String chartGender = normalizeGender(chart.getGender());
            menSize = firstNonBlank(menSize, chart.getMenUSSize());
            womenSize = firstNonBlank(womenSize, chart.getWomenUSSize());
            if (Gender.MENS.name().equals(chartGender)) {
                menSize = firstNonBlank(menSize, chart.getUsSize());
            } else if (Gender.WOMENS.name().equals(chartGender)) {
                womenSize = firstNonBlank(womenSize, chart.getUsSize());
            }
        }
        if (isPresent(menSize) && isPresent(womenSize)) {
            return menSize.trim() + " Men/" + womenSize.trim() + " Women";
        }
        return getKcUsSize(brand, gender, euSize);
    }

    /**
     * 根据 KC 尺码表把男码或女码输入转换为 eBay 男女合并尺码展示值。
     * USM/USW 仅表示输入尺码使用哪一列，不用于判断商品本身的性别。
     * productGender 为商品性别（men/women 等），为空时按输入码制的性别。
     */
    public static String getKcUsSizeDisplayFromUs(String brand, String sizeSystem, String usSize,
                                                  String productGender) {
        String euSize = getKcEuSizeFromUs(brand, sizeSystem, usSize, productGender);
        if (euSize == null) {
            return null;
        }
        String normalizedSystem = sizeSystem.trim().toUpperCase(Locale.ROOT);
        String display = getKcUsSizeDisplay(brand, isPresent(productGender) ? productGender
                : "USM".equals(normalizedSystem) ? "men" : "women", euSize);
        return isPresent(display) ? display : usSize.trim();
    }

    /**
     * 根据 KC 尺码表把男码或女码输入反查为对应欧码。
     * 不知道商品性别时，按输入码制的性别（USM 男款、USW 女款）优先查。
     */
    public static String getKcEuSizeFromUs(String brand, String sizeSystem, String usSize) {
        return getKcEuSizeFromUs(brand, sizeSystem, usSize, null);
    }

    /**
     * 同一美码在男款行、女款行对应的欧码不同（如 Puma 男 8.5：男款 41、女款 40.5），
     * 所以优先查商品性别的行；productGender 为空时按输入码制的性别。
     */
    public static String getKcEuSizeFromUs(String brand, String sizeSystem, String usSize,
                                           String productGender) {
        if (brand == null || brand.isBlank() || sizeSystem == null
                || usSize == null || usSize.isBlank()) {
            return null;
        }
        String normalizedSystem = sizeSystem.trim().toUpperCase(Locale.ROOT);
        if (!"USM".equals(normalizedSystem) && !"USW".equals(normalizedSystem)) {
            return null;
        }
        Map<String, List<SizeChartDO>> brandCharts = findBrandCharts(brand);
        if (brandCharts == null) {
            return null;
        }
        String preferredGender = normalizeGender(productGender);
        if (preferredGender == null) {
            preferredGender = "USM".equals(normalizedSystem) ? Gender.MENS.name() : Gender.WOMENS.name();
        }

        String normalizedUsSize = normalizeSize(usSize);
        for (SizeChartDO chart : preferredFirst(brandCharts, preferredGender)) {
            String chartGender = normalizeGender(chart.getGender());
            String chartUsSize = "USM".equals(normalizedSystem)
                    ? firstNonBlank(chart.getMenUSSize(),
                    Gender.MENS.name().equals(chartGender) ? chart.getUsSize() : null)
                    : firstNonBlank(chart.getWomenUSSize(),
                    Gender.WOMENS.name().equals(chartGender) ? chart.getUsSize() : null);
            if (!normalizedUsSize.equals(normalizeSize(chartUsSize))) {
                continue;
            }
            return isPresent(chart.getEuSize()) ? chart.getEuSize().trim() : null;
        }
        return null;
    }

    /** HashMap 遍历顺序不固定（女款行常排在男款行前），所以把指定性别的行显式放到最前。 */
    private static List<SizeChartDO> preferredFirst(Map<String, List<SizeChartDO>> brandCharts,
                                                    String gender) {
        List<SizeChartDO> rows = new ArrayList<>();
        brandCharts.forEach((chartGender, charts) -> {
            if (gender != null && gender.equals(normalizeGender(chartGender))) {
                rows.addAll(0, charts);
            } else {
                rows.addAll(charts);
            }
        });
        return rows;
    }

    private static Map<String, List<SizeChartDO>> findBrandCharts(String brand) {
        String normalizedBrand = brand.trim();
        return KC_SIZE_CACHE.entrySet().stream()
                .filter(entry -> (entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(normalizedBrand))
                        || entry.getValue().values().stream()
                        .flatMap(List::stream)
                        .map(SizeChartDO::getStockxBrand)
                        .anyMatch(stockxBrand -> stockxBrand != null
                                && stockxBrand.equalsIgnoreCase(normalizedBrand)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String firstNonBlank(String first, String second) {
        return isPresent(first) ? first : second;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        String normalized = gender.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("baby") || normalized.contains("toddler")
                || normalized.contains("infant")) {
            return Gender.BABY.name();
        }
        if (normalized.contains("kid") || normalized.contains("youth")) {
            return Gender.KIDS.name();
        }
        if (normalized.contains("women") || normalized.contains("female")) {
            return Gender.WOMENS.name();
        }
        if (normalized.contains("men") || normalized.contains("male")) {
            return Gender.MENS.name();
        }
        return null;
    }

    private static String normalizeSize(String size) {
        if (size == null) {
            return "";
        }
        String normalized = size.trim();
        if (normalized.endsWith(".0")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    /**
     * 构建缓存key
     */
    private static String buildKey(String brand, String gender, String usSize) {
        return STR."\{brand}:\{gender}:\{usSize}";
    }

}
