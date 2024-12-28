package cn.ken.shoes.task;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.context.KickScrewContext;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.util.TimeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StartedEventListener implements ApplicationListener<ApplicationStartedEvent> {

    @Resource
    private KickScrewClient kickScrewClient;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        log.info("onApplicationEvent: queryCategory start, time: {}", TimeUtil.now());
        KickScrewCategory kickScrewCategory = kickScrewClient.queryCategory();
        if (kickScrewCategory == null || kickScrewCategory.getBrand() == null) {
            log.error("onApplicationEvent: queryCategory no result");
            return;
        }
        Map<String, Integer> brand = kickScrewCategory.getBrand();
        KickScrewContext.brandSizes = brand;
        KickScrewContext.brandSet = brand.keySet();
        log.info("onApplicationEvent: queryCategory end, time: {}, brandSize: {}", TimeUtil.now(), KickScrewContext.brandSet.size());
    }
}
