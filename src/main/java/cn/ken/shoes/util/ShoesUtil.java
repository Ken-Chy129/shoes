package cn.ken.shoes.util;

import cn.ken.shoes.common.PriceEnum;
import cn.ken.shoes.config.PriceSwitch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShoesUtil {

    private static final Pattern EU_SIZE_PATTEN = Pattern.compile("EU\\s*(\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);

    public static String getEuSizeFromKickScrew(String rawTitle) {
        // 定义正则表达式以匹配 "EU" 后跟随的数字（包括小数）
        Matcher matcher = EU_SIZE_PATTEN.matcher(rawTitle);
        // 查找符合模式的子串，并返回捕获组的内容（即EU后的尺寸）
        if (matcher.find()) {
            return matcher.group(1); // 返回第一个捕获组，即EU后面的尺寸
        } else {
            return null; // 如果没有找到匹配项，则返回null
        }
    }

    public static Integer getPrice(Integer poisonPrice, Integer otherPrice) {
        double poisonPriceYuan = poisonPrice / 100.0;
        // 成本=得物价格+运费
        double cost = poisonPriceYuan + PriceSwitch.FREIGHT;
        // 目标盈利
        double earn = Math.max(PriceSwitch.MIN_PROFIT, cost * PriceSwitch.MIN_PROFIT_RATE);
        // 满足盈利的定价=（成本+目标盈利）➗汇率➗（1-平台抽成）
        int price = (int) Math.ceil(Math.ceil((cost + earn) / PriceSwitch.EXCHANGE_RATE) / (1 - PriceSwitch.PLATFORM_RATE));
        // 三方平台没有该商品出售，直接设置为满足盈利的定价
        if (otherPrice == -1) {
            return price;
        }
        return price < otherPrice - 1 ? otherPrice - 1 : null;
    }

}
