package cn.ken.shoes.task;

import cn.ken.shoes.client.KickScrewClient;
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

    @Scheduled(cron = "0 0 0 * * *")
    public void queryCategory() {
    }

}
