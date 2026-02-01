package cn.ken.shoes.util;

import cn.ken.shoes.common.Gender;
import cn.ken.shoes.common.PlatformEnum;

/**
 * @author Ken-Chy129
 * @date 2026/1/28
 */
public class GenderUtil {

    public static Gender extractGender(PlatformEnum platform, String content) {
        if (platform == PlatformEnum.DUNK) {
            if (content.contains("Women's")) {
                return Gender.WOMENS;
            } else if (content.contains("GS")) {
                return Gender.KIDS;
            } else if (content.contains("PS") || content.contains("TD")) {
                return Gender.BABY;
            } else {
                return Gender.MENS;
            }
        } else if (platform == PlatformEnum.STOCKX) {
            if (content.contains("Y")) {
                return Gender.KIDS;
            } else if (content.contains("C") || content.contains("K")) {
                return Gender.BABY;
            } else if (content.contains("W")) {
                return Gender.WOMENS;
            } else {
                return Gender.MENS;
            }
        }
        return Gender.MENS;
    }
}
