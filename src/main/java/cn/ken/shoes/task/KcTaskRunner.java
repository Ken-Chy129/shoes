package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.util.LockHelper;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author Ken-Chy129
 * @date 2025/5/19
 */
@Slf4j
@Component
public class KcTaskRunner extends Thread {

    @Getter
    private boolean isInit = false;

    @Resource
    private KickScrewService kickScrewService;

    @Override
    public void run() {
        isInit = true;
        while (true) {
            try {
                if (TaskSwitch.STOP_KC_TASK) {
                    Thread.sleep(TaskSwitch.STOP_INTERVAL);
                    continue;
                } else {
                    LockHelper.lockKcItem();
                    kickScrewService.refreshItems(true);
                    kickScrewService.refreshPriceV2();
                    LockHelper.unlockKcItem();
                }
                Thread.sleep(TaskSwitch.KC_TASK_INTERVAL);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
        }
    }
}
