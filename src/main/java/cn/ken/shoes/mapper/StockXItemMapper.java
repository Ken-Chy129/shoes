package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.StockXItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StockXItemMapper extends BaseMapper<StockXItemDO> {

    int insertIgnore(StockXItemDO stockXItemDO);

    List<String> selectAllProductIds();
}
