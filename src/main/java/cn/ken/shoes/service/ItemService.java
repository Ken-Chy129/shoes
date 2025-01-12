package cn.ken.shoes.service;

import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.ItemDO;

import java.util.List;

public interface ItemService {

    /**
     * 爬取品牌信息
     */
    List<BrandDO> scratchBrands();

    /**
     * 爬取商品信息
     */
    void scratchItems();

    /**
     * 条件查询商品
     */
    List<ItemDO> selectItemsByCondition();

    /**
     * 爬取价格
     */
    void scratchPrices(List<ItemDO> items);

    /**
     * 改价
     */
    void changePrice();
}
