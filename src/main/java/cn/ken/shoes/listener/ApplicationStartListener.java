package cn.ken.shoes.listener;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.CustomPriceTypeEnum;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
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
public class ApplicationStartListener implements ApplicationListener<ApplicationStartedEvent> {

    @Resource
    private PoisonService poisonService;

    @Resource
    private KickScrewService kickScrewService;

    @Resource
    private SizeChartMapper sizeChartMapper;

    @Resource
    private CustomModelMapper customModelMapper;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (PoisonSwitch.OPEN_IMPORT_DB_DATA) {
            poisonService.importPriceToCache();
        }
        initSizeMap();
        initCustomModel();
        while (true) {
            try {
                Thread.sleep(5 * 60 * 1000);
                if (PoisonSwitch.STOP_QUERY_PRICE) {
                    continue;
                }
                System.out.println("开始刷新得物价格");
                poisonService.refreshAllPrice();
                System.out.println("结束得物价格刷新");
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void initSizeMap() {
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

    private void initCustomModel() {
        List<CustomModelDO> customModelDOS = customModelMapper.selectList(new QueryWrapper<>());
        for (CustomModelDO customModelDO : customModelDOS) {
            CustomPriceTypeEnum customPriceTypeEnum = CustomPriceTypeEnum.from(customModelDO.getType());
            if (customPriceTypeEnum == null) {
                continue;
            }
            customPriceTypeEnum.getCachePutConsumer().accept(customModelDO);
        }
    }
}
