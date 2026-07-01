package cn.ken.shoes.scheduler;

import cn.ken.shoes.manager.PriceManager;
import cn.ken.shoes.service.StockXService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Slf4j
@Component
public class PriceScheduler {

    @Resource
    private PriceManager priceManager;

    @Resource
    private StockXService stockXService;

    @Scheduled(initialDelay = 60 * 60 * 1000, fixedDelay = 30 * 60 * 1000)
    public void dumpPoisonPrice() {
        priceManager.dumpPrice();
    }

    /** 对账"上架处理中"条目：按 variantID 重查回填最终结果，补掉后台异步校验在重启时丢失的缺口 */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 5 * 60 * 1000)
    public void reconcilePendingListings() {
        try {
            stockXService.reconcilePendingListings();
        } catch (Exception e) {
            log.error("上架对账任务异常", e);
        }
    }

    /** 对账"压价未确认/未同步/已提交"条目：按 listingId 重查回填最终结果，补内联窗口没确认到的成功/失败 */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 5 * 60 * 1000)
    public void reconcilePendingPriceDown() {
        try {
            stockXService.reconcilePendingPriceDown();
        } catch (Exception e) {
            log.error("压价对账任务异常", e);
        }
    }

    /** 对账"下架未确认"条目：按 listingId 重查回填 下架成功/失败(压价链路下架 + Excel 下架都覆盖) */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 5 * 60 * 1000)
    public void reconcilePendingDelist() {
        try {
            stockXService.reconcilePendingDelist();
        } catch (Exception e) {
            log.error("下架对账任务异常", e);
        }
    }

}
