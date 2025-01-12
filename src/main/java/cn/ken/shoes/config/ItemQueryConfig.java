package cn.ken.shoes.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ItemQueryConfig {

    public final static List<Integer> ALL_RELEASE_YEARS;

    public final static List<String> ALL_GENDER = List.of("BABY", "KIDS", "MENS", "UNISEX", "WOMENS");

    static {
        ALL_RELEASE_YEARS = new ArrayList<Integer>();
        int end = LocalDateTime.now().getYear();
        for (int i = 2015; i < end; i++) {
            ALL_RELEASE_YEARS.add(i);
        }
    }

}
