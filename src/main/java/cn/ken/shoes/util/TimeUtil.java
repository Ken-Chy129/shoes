package cn.ken.shoes.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static DateTimeFormatter getFormatter() {
        return FORMATTER;
    }

    public static String now() {
        return LocalDateTime.now().format(getFormatter());
    }

    public static String milliSecondToMin(long time) {
        long minutes = time / 60; // 计算总共有多少分钟
        long remainingSeconds = time % 60; // 剩余的秒数

        return minutes + "分钟" + remainingSeconds + "秒";
    }

    public static String getCostMin(long startTime) {
        return milliSecondToMin(System.currentTimeMillis() - startTime);
    }
}
