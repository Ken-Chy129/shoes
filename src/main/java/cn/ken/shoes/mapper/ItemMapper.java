package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.ItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemMapper extends BaseMapper<ItemDO> {

    @Override
    int insert(ItemDO entity);
}
