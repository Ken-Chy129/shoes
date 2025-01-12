package cn.ken.shoes.task;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.client.KickScrewClient;
import cn.ken.shoes.mapper.BrandMapper;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.SizeChartDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Map<String, List<SizeChartDO>>> brandGenderMap = new HashMap<>();
        for (SizeChartDO sizeChartDO : sizeChartDOS) {
            String brand = sizeChartDO.getBrand();
            String gender = sizeChartDO.getGender();
            Map<String, List<SizeChartDO>> genderMap = brandGenderMap.getOrDefault(brand, new HashMap<>());
            genderMap.computeIfAbsent(gender, k -> new ArrayList<>()).add(sizeChartDO);
            brandGenderMap.put(brand, genderMap);
        }
        ShoesContext.setBrandGenderSizeChartMap(brandGenderMap);
    }

}
