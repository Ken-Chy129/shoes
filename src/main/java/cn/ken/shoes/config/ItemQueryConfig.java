package cn.ken.shoes.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ItemQueryConfig {

    public final static List<Integer> ALL_RELEASE_YEARS;

    public final static List<String> ALL_GENDER = List.of("BABY", "KIDS", "MENS", "UNISEX", "WOMENS");

    public final static List<String> START_PRICES;

    public final static List<String> END_PRICES;

    static {
        ALL_RELEASE_YEARS = new ArrayList<>();
        int end = LocalDateTime.now().getYear();
        for (int i = end; i >= 2015; i--) {
            ALL_RELEASE_YEARS.add(i);
        }
        START_PRICES = new ArrayList<>();
        START_PRICES.add(null);
        START_PRICES.add("100");
        START_PRICES.add("200");
        START_PRICES.add("300");
        END_PRICES = new ArrayList<>();
        END_PRICES.add("100");
        END_PRICES.add("200");
        END_PRICES.add("300");
        END_PRICES.add(null);
    }

}
