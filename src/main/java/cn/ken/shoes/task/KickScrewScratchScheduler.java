package cn.ken.shoes.task;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Component
public class KickScrewScratchScheduler {

    @Resource
    private KickScrewClient kickScrewClient;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Scheduled(cron = "0 0 * * * *")
    public void queryCategory() {
        log.info("queryCategory start, time: {}", LocalDateTime.now().format(FORMATTER));
        KickScrewCategory kickScrewCategory = kickScrewClient.queryCategory();
        if (kickScrewCategory == null) {
            return;
        }
        Map<String, Integer> brand = kickScrewCategory.getBrand();
        if (brand == null) {
            return;
        }
        KickScrewContext.brandSizes = brand;
        KickScrewContext.brandSet = brand.keySet();
        log.info("queryCategory end, time: {}", LocalDateTime.now().format(FORMATTER));
    }

}
