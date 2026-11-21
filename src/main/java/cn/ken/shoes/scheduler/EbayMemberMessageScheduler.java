package cn.ken.shoes.scheduler;

import cn.ken.shoes.service.EbayMemberMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 买家提问兜底对账：推送为主，这里低频补漏。每分钟检查一次，间隔由网页配置（默认 240 分钟），启动 5 分钟后首次执行。 */
@Slf4j
@Component
public class EbayMemberMessageScheduler {

    private final EbayMemberMessageService service;

    public EbayMemberMessageScheduler(EbayMemberMessageService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${ebay.messages.reconcile-initial-delay-ms:300000}", fixedDelay = 60000)
    public void reconcile() {
        if (!service.isEnabled()) {
            return;
        }
        try {
            service.reconcileIfDue();
        } catch (Exception e) {
            log.error("eBay买家提问对账异常", e);
        }
    }
}
