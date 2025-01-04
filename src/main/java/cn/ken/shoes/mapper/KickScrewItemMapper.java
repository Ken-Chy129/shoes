package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.KickScrewItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KickScrewItemMapper extends BaseMapper<KickScrewItemDO> {

    void insertIgnore(KickScrewItemDO kickScrewItemDO);
}
