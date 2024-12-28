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
}
