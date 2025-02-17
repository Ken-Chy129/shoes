package cn.ken.shoes.scheduler;

import cn.ken.shoes.client.PoisonClient;
import cn.ken.shoes.mapper.PoisonItemMapper;
import cn.ken.shoes.service.PoisonService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class ModelNumberScratchEventListener implements ApplicationListener<ModelNumberScratchEvent>, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Resource
    private PoisonItemMapper poisonItemMapper;

    @Resource
    private PoisonClient poisonClient;

    @Resource
    private PoisonService poisonService;

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
//        //todo:只爬取最近一年的，并且爬取的时候不删表，只做新增，根据modelNo做唯一键
//        for (ItemService service : itemServiceMap.values()) {
//            Thread.ofVirtual().start(service::refreshAllItems);
//        }
//        Set<String> allItems = new CopyOnWriteArraySet<>();
//        for (ItemService service : itemServiceMap.values()) {
//            Thread.ofVirtual().start(() -> allItems.addAll(service.selectItemsByCondition()));
//        }
//
//        // 2.根据货号查询spuId
//        // 2.1 从db查出已保存的货号->spuId映射关系
//        List<PoisonItemDO> poisonItemDOS = poisonItemMapper.selectAllModelNoAndSpuId();
//
//        Set<String> existItemSet = poisonItemDOS.stream().map(PoisonItemDO::getArticleNumber).collect(Collectors.toSet());
//        // 2.2 查询剩余货号的spuId
//        allItems.removeAll(existItemSet);
//        List<Pair<String, Long>> pairs = poisonService.queryAndSaveSpuIds();
//        pairs.addAll(poisonItemDOS.stream().map(poisonItemDO -> Pair.of(poisonItemDO.getArticleNumber(), poisonItemDO.getSpuId())).toList());
//        // 3.根据
//        poisonService.refreshPrice(pairs);

    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
