package cn.ken.shoes.listener;

import cn.ken.shoes.ShoesContext;
import cn.ken.shoes.common.CustomPriceTypeEnum;
import cn.ken.shoes.config.PoisonSwitch;
import cn.ken.shoes.mapper.CustomModelMapper;
import cn.ken.shoes.mapper.SizeChartMapper;
import cn.ken.shoes.mapper.SpecialPriceMapper;
import cn.ken.shoes.model.entity.CustomModelDO;
import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.entity.SpecialPriceDO;
import cn.ken.shoes.service.KickScrewService;
import cn.ken.shoes.service.PoisonService;
import cn.ken.shoes.util.BrandUtil;
import cn.ken.shoes.util.SizeConvertUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

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

    @Resource
    private SpecialPriceMapper specialPriceMapper;

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (PoisonSwitch.OPEN_IMPORT_DB_DATA) {
            poisonService.importPriceToCache();
        }
        initSizeMap();
        initCustomModel();
        initSpecialPrice();
        // 异步不断更新kc商品
        asyncUpdateKcItems();
        while (true) {
            try {
                Thread.sleep(5 * 60 * 1000);
                if (PoisonSwitch.STOP_QUERY_PRICE) {
                    continue;
                }
                poisonService.refreshAllPrice();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void asyncUpdateKcItems() {
        Thread.startVirtualThread(() -> {
            while (true) {
                try {
                    kickScrewService.updateItems();
                    Thread.sleep(5 * 60 * 1000);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        });
    }

    private void initSizeMap() {
        List<SizeChartDO> sizeChartDOS = sizeChartMapper.selectList(new QueryWrapper<>());
        SizeConvertUtil.initCache(sizeChartDOS);
        BrandUtil.initCache(sizeChartDOS);
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

    private void initSpecialPrice() {
        List<SpecialPriceDO> specialPriceDOS = specialPriceMapper.selectList(new QueryWrapper<>());
        specialPriceDOS.forEach(ShoesContext::addSpecialPrice);
    }
}
