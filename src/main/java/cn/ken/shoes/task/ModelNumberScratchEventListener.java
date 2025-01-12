package cn.ken.shoes.task;

import cn.hutool.core.collection.ConcurrentHashSet;
import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.ItemDO;
import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.service.ItemService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ModelNumberScratchEventListener implements ApplicationListener<ModelNumberScratchEvent>, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private PoisonClient poisonClient;

    @Override
    public void onApplicationEvent(ModelNumberScratchEvent event) {
//        Map<String, ItemService> itemServiceMap = applicationContext.getBeansOfType(ItemService.class);
//        Set<String> allBrands = new HashSet<>();
//        // 1.查询品牌
//        for (ItemService service : itemServiceMap.values()) {
//            List<BrandDO> brandDOList = service.scratchBrands();
//            allBrands.addAll(brandDOList.stream().map(BrandDO::getName).collect(Collectors.toSet()));
//        }
//        // 2.调用不同平台爬取货号
//        List<ItemDO> allItems = new CopyOnWriteArrayList<>();
//        for (ItemService service : itemServiceMap.values()) {
//            Thread.ofVirtual().start(() -> {
//                service.scratchItems();
//                allItems.addAll(itemDOS);
//            });
//        }
//        // 2.根据货号查询spuId
//        // 2.1 从db查出已保存的货号->spuId映射关系
//        List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectList(new QueryWrapper<>());
//        Set<String> existItemSet = poisonItemDOS.stream().map(PoisonItemDO::getArticleNumber).collect(Collectors.toSet());
//        // 2.2 查询剩余货号的spuId
//        allItems.removeAll(existItemSet);

//        poisonClient.queryItemByModelNos()
        // 3.根据
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
