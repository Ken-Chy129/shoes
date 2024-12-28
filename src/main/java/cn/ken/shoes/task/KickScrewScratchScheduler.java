package cn.ken.shoes.task;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class KickScrewScratchScheduler {

    @Resource
    private KickScrewClient kickScrewClient;

    @Scheduled(cron = "0 0 * * * *")
    public void queryCategory() {
        log.info("scheduler task: queryCategory start, time: {}", TimeUtil.now());
        KickScrewCategory kickScrewCategory = kickScrewClient.queryCategory();
        if (kickScrewCategory == null || kickScrewCategory.getBrand() == null) {
            log.error("scheduler task: queryCategory no result");
            return;
        }
        Map<String, Integer> brand = kickScrewCategory.getBrand();
        KickScrewContext.brandSizes = brand;
        KickScrewContext.brandSet = brand.keySet();
        log.info("scheduler task: queryCategory end, time: {}, brandSize: {}", TimeUtil.now(), KickScrewContext.brandSet.size());
    }

}
