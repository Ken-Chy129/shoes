package cn.ken.shoes.task;

import cn.ken.shoes.config.TaskSwitch;
import cn.ken.shoes.service.StockXService;
import cn.ken.shoes.util.LockHelper;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * StockX压价任务执行器
 * @author Ken-Chy129
 */
@Slf4j
@Component
public class StockXPriceDownTaskRunner extends Thread {

    @Getter
    private boolean isInit = false;

    @Resource
    private StockXService stockXService;

    @Override
    public void run() {
        isInit = true;
        while (true) {
            try {
                if (TaskSwitch.STOP_STOCK_PRICE_DOWN_TASK) {
                    Thread.sleep(TaskSwitch.STOP_INTERVAL);
                    continue;
                } else {
                    LockHelper.lockStockXItem();
                    stockXService.priceDown();
                    LockHelper.unlockStockXItem();
                }
                Thread.sleep(TaskSwitch.STOCK_PRICE_DOWN_TASK_INTERVAL);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
        }
    }
}
