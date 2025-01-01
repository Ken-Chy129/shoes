package cn.ken.shoes.task;

import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.kickscrew.KickScrewCategory;
import cn.ken.shoes.util.TimeUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class StartedEventListener implements ApplicationListener<ApplicationStartedEvent> {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private BrandMapper brandMapper;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {

    }
}
