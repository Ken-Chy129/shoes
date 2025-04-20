package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.NoPriceModelDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NoPriceModelMapper extends BaseMapper<NoPriceModelDO> {

    int insertIgnore(@Param("modelNo") String modelNo);
}
