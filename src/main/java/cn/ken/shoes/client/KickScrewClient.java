package cn.ken.shoes.client;

import cn.ken.shoes.model.kickscrew.KickScrewItem;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class KickScrewClient {

    public List<KickScrewItem> queryItemByBrand(String brand) {
        return Collections.emptyList();
    }
}
