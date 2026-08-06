package cn.ken.shoes.util;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.model.stockx.StockXAccount;
import cn.ken.shoes.model.stockx.StockXFeeConfig;
import cn.ken.shoes.common.PriceDownType;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShoesUtil {

    /**
     * StockX 报价的合理上限（美元）。StockX 偶发会返回远超真实市场价的天价挂单，
     * 这类报价一是不可能成交，二是超出 task_item 价格列 DECIMAL(10,2) 的范围，
     * 直接写库会抛 Data truncation 并让整个任务失败，因此统一按无效报价处理。
     */
    private static final int MAX_STOCKX_PRICE = 1_000_000;

    private static final Pattern KC_EU_SIZE_PATTEN = Pattern.compile("EU\\s*(\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SHOES_SIZE_PATTEN = Pattern.compile("\\d+(\\.\\d+)?");;

    private static final Pattern CLOTHES_SIZE_PATTEN = Pattern.compile("US\\s+([A-Za-z0-9]+)");;

    public static String getEuSizeFromKickScrew(String rawTitle) {
        // 定义正则表达式以匹配 "EU" 后跟随的数字（包括小数）
        Matcher matcher = KC_EU_SIZE_PATTEN.matcher(rawTitle);
        // 查找符合模式的子串，并返回捕获组的内容（即EU后的尺寸）
        if (matcher.find()) {
            return matcher.group(1); // 返回第一个捕获组，即EU后面的尺寸
        } else {
            return null; // 如果没有找到匹配项，则返回null
        }
    }

    public static String getShoesSizeFrom(String rawSize) {
        if (rawSize == null) {
            return null;
        }
        rawSize = normalizeUnicodeFraction(rawSize);
        Matcher matcher = SHOES_SIZE_PATTEN.matcher(rawSize);
        if (matcher.find()) {
            return matcher.group();
        } else {
            return null;
        }
    }

    /**
     * 将Unicode分数字符转为数值：⅓ → 取整，⅔ → .5
     * 例：39⅓ → 39，42⅔ → 42.5
     */
    public static String normalizeUnicodeFraction(String size) {
        if (size == null) return null;
        // ASCII fractions: "42 2/3" → "42.5", "39 1/3" → "39"
        size = size.replaceAll("\\s*2/3", ".5");
        size = size.replaceAll("\\s*1/3", "");
        // Unicode fractions: 42⅔ → 42.5, 39⅓ → 39
        if (size.contains("⅓")) {
            return size.replace("⅓", "").trim();
        }
        if (size.contains("⅔")) {
            return size.replace("⅔", ".5").trim();
        }
        return size.trim();
    }

    public static String getClothesSize(String rawSize) {
        if (rawSize == null) {
            return null;
        }
        // 正则表达式匹配整数和小数
        Matcher matcher = CLOTHES_SIZE_PATTEN.matcher(rawSize);

        if (matcher.find()) {
            // 返回找到的数值字符串
            return matcher.group(1);
        } else {
            // 如果没有找到匹配项，可以根据需求抛出异常或返回特定值
            return null; // 或者可以选择抛出异常等其他处理方式
        }
    }

    /**
     * 判断当前kc价格是否盈利
     * @param poisonPrice 得物价格
     * @param kcPrice kc的价格
     * @param minExpectProfit 预期最小盈利
     * @return 当前kc价格是否盈利
     */
    public static boolean canKcEarn(Integer poisonPrice, Integer kcPrice, Integer minExpectProfit) {
        double getFromPlatform = (kcPrice * PriceSwitch.KC_GET_RATE - PriceSwitch.KC_SERVICE_FEE) * PriceSwitch.EXCHANGE_RATE;
        double earn = getFromPlatform - PriceSwitch.FREIGHT - poisonPrice;
        if (earn < minExpectProfit) {
            return false;
        }
        return true;
//        return earn / poisonPriceYuan > PriceSwitch.MIN_PROFIT_RATE;
//        // 得物价格数据库中保存为分，转换为元
//        double poisonPriceYuan = poisonPrice / 100.0;
//        // 成本=得物价格+运费
//        double cost = poisonPriceYuan + PriceSwitch.FREIGHT;
//        // 最低目标盈利
//        double earn = Math.max(PriceSwitch.MIN_PROFIT, cost * PriceSwitch.MIN_PROFIT_RATE);
//        // 满足盈利的定价=（成本+目标盈利）➗汇率➗（1-平台抽成）
//        int price = (int) Math.ceil(Math.ceil((cost + earn) / PriceSwitch.EXCHANGE_RATE) / (1 - PriceSwitch.PLATFORM_RATE));
//        // 三方平台没有该商品出售，直接设置为满足盈利的定价
//        if (kcPrice == -1) {
//            return price;
//        }
//        return price < kcPrice - 1 ? kcPrice - 1 : null;
    }

    public static double getKcEarn(Integer poisonPrice, Integer otherPrice) {
        double getFromPlatform = ((otherPrice - 1.0) * PriceSwitch.KC_GET_RATE - PriceSwitch.KC_SERVICE_FEE) * PriceSwitch.EXCHANGE_RATE;
        return getFromPlatform - PriceSwitch.FREIGHT - poisonPrice;
    }

    public static boolean canStockxEarn(Integer poisonPrice, Integer stockXPrice, Integer minExpectProfit, StockXAccount account) {
        return canStockxEarn(poisonPrice, stockXPrice, minExpectProfit,
                account.resolveFeeConfig(PriceDownType.DEFAULT));
    }

    public static boolean canStockxEarn(Integer poisonPrice, Integer stockXPrice, Integer minExpectProfit, StockXFeeConfig fees) {
        double earn = getStockxEarn(poisonPrice, stockXPrice, fees);
        return earn >= minExpectProfit;
    }

    public static double getStockxEarn(Integer poisonPrice, Integer stockXPrice, StockXAccount account) {
        return getStockxEarn(poisonPrice, stockXPrice, account.resolveFeeConfig(PriceDownType.DEFAULT));
    }

    public static double getStockxEarn(Integer poisonPrice, Integer stockXPrice, StockXFeeConfig fees) {
        double transferFee = fees.getTransferFeeRate() == 0 ? 0 : stockXPrice * fees.getTransferFeeRate();
        double merchantFee = (fees.getMerchantFeeRate() == 0 || fees.getMinMerchantFee() == 0)
                ? 0 : Math.max(stockXPrice * fees.getMerchantFeeRate(), fees.getMinMerchantFee());
        double getFromPlatform = (stockXPrice - transferFee - merchantFee - fees.getPlatformShippingFee())
                * PriceSwitch.EXCHANGE_RATE;
        return getFromPlatform - fees.getFreight() - poisonPrice;
    }

    /**
     * 按库存类型解析 StockX 最低价口径：
     * - STANDARD(卖家自发货)：取 standardLowest 与 expressStandardLowest 的较小值(任一为空则取非空者)
     * - CUSTODIAL(寄存/StockX仓发货)：只看 expressStandardLowest
     * 与压价决策口径保持一致，避免导出展示的最低价与压价所用最低价不一致。
     */
    public static Integer resolveStockxLowest(String inventoryType, Integer standardLowest, Integer expressStandardLowest) {
        standardLowest = normalizeStockxPrice(standardLowest);
        expressStandardLowest = normalizeStockxPrice(expressStandardLowest);
        if ("STANDARD".equals(inventoryType)) {
            if (standardLowest != null && expressStandardLowest != null) {
                return Math.min(standardLowest, expressStandardLowest);
            }
            return standardLowest != null ? standardLowest : expressStandardLowest;
        }
        return expressStandardLowest;
    }

    /**
     * 过滤 StockX 的异常报价：非正数或超出 {@link #MAX_STOCKX_PRICE} 的价格一律视为无价(null)，
     * 避免天价挂单既污染上架决策，又撑爆数据库价格列导致任务整体失败。
     */
    public static Integer normalizeStockxPrice(Integer price) {
        if (price == null || price <= 0 || price > MAX_STOCKX_PRICE) {
            return null;
        }
        return price;
    }

    /** 价格列写库前的统一转换：异常报价落库为 null，而非超范围数值。 */
    public static BigDecimal toStockxPriceColumn(Integer price) {
        Integer normalized = normalizeStockxPrice(price);
        return normalized != null ? BigDecimal.valueOf(normalized) : null;
    }

    public static Integer getThreeFivePrice(Integer normalPrice) {
        return (int) (normalPrice * 0.95 - 48);
    }

    public static boolean isThreeFiveModel(String model, String euSize) {
        return PoisonSwitch.OPEN_ALL_THREE_FIVE || ShoesContext.isThreeFiveModel(model, euSize);
    }
}
