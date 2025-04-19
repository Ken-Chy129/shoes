package cn.ken.shoes.listener;

import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.service.KickScrewService;
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

    @Resource
    private KickScrewService kickScrewService;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (PoisonSwitch.OPEN_IMPORT_DB_DATA) {
            poisonService.importPriceToCache();
        }
        while (true) {
            try {
                Thread.sleep(5 * 60 * 1000);
                if (PoisonSwitch.STOP_QUERY_PRICE) {
                    continue;
                }
                System.out.println("开始刷新kc商品");
                // 1.刷新kc商品
                kickScrewService.refreshItems(true);
                System.out.println("开始刷新得物价格");
                // 2.更新价格
                poisonService.refreshAllPrice();
                System.out.println("结束得物价格刷新");
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }
}
