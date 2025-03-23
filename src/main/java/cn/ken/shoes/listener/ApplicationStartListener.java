package cn.ken.shoes.listener;

import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ApplicationStartListener implements ApplicationListener<ApplicationStartedEvent> {

    @Resource
    private PoisonService poisonService;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        while (true) {
            poisonService.refreshAllPrice();
            try {
                Thread.sleep(5 * 60 * 1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
