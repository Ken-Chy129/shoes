package cn.ken.shoes.service;

import cn.ken.shoes.model.entity.BrandDO;

import java.util.List;

public interface ItemService {

    String getPlatformName();

    /**
     * 爬取品牌信息
     */
    List<BrandDO> scratchBrands();

    /**
     * 全量爬取商品信息，会清空数据重新获取
     */
    void refreshAllItems();

    /**
     * 增量爬取商品
     */
    void refreshIncrementalItems();

    /**
     * 条件查询商品
     */
    List<String> selectItemsByCondition();

    /**
     * 爬取价格
     */
    void refreshAllPrices();

    /**
     * 和得物价格进行比较并压价有盈利的商品
     */
    int compareWithPoisonAndChangePrice();

    void refreshAllPricesV2();

    void refreshPrices(List<String> modelNos);
}
