package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.SizeChartDO;
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
import java.util.stream.Collectors;

@Slf4j
@Component
public class StartedEventListener implements ApplicationListener<ApplicationStartedEvent> {

    @Resource
    private KickScrewClient kickScrewClient;

    @Resource
    private BrandMapper brandMapper;

    @Resource
    private SizeChartMapper sizeChartMapper;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        List<SizeChartDO> sizeChartDOS = sizeChartMapper.selectList(new QueryWrapper<>());
        ShoesContext.setBrandSizeChartMap(sizeChartDOS.stream().collect(Collectors.groupingBy(SizeChartDO::getBrand)));
    }

}
