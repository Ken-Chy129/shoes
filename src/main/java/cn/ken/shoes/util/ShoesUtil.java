package cn.ken.shoes.util;

import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.config.PriceSwitch;
import cn.ken.shoes.model.entity.PoisonPriceDO;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShoesUtil {

    private static final Pattern KC_EU_SIZE_PATTEN = Pattern.compile("EU\\s*(\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern POISON_EU_SIZE_PATTEN = Pattern.compile("\\d+(\\.\\d+)?");;

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

    public static String getEuSizeFromPoison(String rawSize) {
        // 正则表达式匹配整数和小数
        Matcher matcher = POISON_EU_SIZE_PATTEN.matcher(rawSize);

        if (matcher.find()) {
            // 返回找到的数值字符串
            return matcher.group();
        } else {
            // 如果没有找到匹配项，可以根据需求抛出异常或返回特定值
            return null; // 或者可以选择抛出异常等其他处理方式
        }
    }

    /**
     * 比价，获取上架的价格
     * @param poisonPrice 得物价格
     * @param otherPrice 三方平台的价格
     * @return 如果三方平台价格-1有盈利，则修改为三方平台价格-1，否则返回null
     */
    public static boolean canEarn(Integer poisonPrice, Integer otherPrice) {
        double poisonPriceYuan = poisonPrice / 100.0;
        double getFromPlatform = ((otherPrice - 1.0) * 0.88 - 15) * PriceSwitch.EXCHANGE_RATE;
        double earn = getFromPlatform - PriceSwitch.FREIGHT - poisonPriceYuan;
        if (earn < PriceSwitch.MIN_PROFIT) {
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
//        if (otherPrice == -1) {
//            return price;
//        }
//        return price < otherPrice - 1 ? otherPrice - 1 : null;
    }

    public static double getKcEarn(Integer poisonPrice, Integer otherPrice) {
        double poisonPriceYuan = poisonPrice / 100.0;
        double getFromPlatform = ((otherPrice - 1.0) * 0.88 - 15) * PriceSwitch.EXCHANGE_RATE;
        return getFromPlatform - PriceSwitch.FREIGHT - poisonPriceYuan;
    }

}
