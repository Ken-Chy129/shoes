package cn.ken.shoes.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    public static final DateTimeFormatter YYYY_MM_DD_HH_MM_SS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public static final DateTimeFormatter MM_DD_YYYY = DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneId.systemDefault());

    public static String now() {
        return LocalDateTime.now().format(YYYY_MM_DD_HH_MM_SS);
    }

    public static String milliSecondToMin(long time) {
        time /= 1000;
        long minutes = time / 60; // 计算总共有多少分钟
        long remainingSeconds = time % 60; // 剩余的秒数

        return minutes + "分钟" + remainingSeconds + "秒";
    }

    public static String getCostMin(long startTime) {
        return milliSecondToMin(System.currentTimeMillis() - startTime);
    }

    public static String formatISO(String isoDateTime, DateTimeFormatter formatter) {
        Instant instant = Instant.parse(isoDateTime);
        return formatter.format(instant);
    }

}
