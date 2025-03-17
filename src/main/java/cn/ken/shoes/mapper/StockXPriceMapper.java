package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.StockXPriceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockXPriceMapper extends BaseMapper<StockXPriceDO> {

    int insertIgnore(StockXPriceDO stockXItemDO);

}
