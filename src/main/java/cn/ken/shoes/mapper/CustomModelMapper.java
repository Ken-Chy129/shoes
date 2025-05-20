package cn.ken.shoes.mapper;


import cn.ken.shoes.model.entity.CustomModelDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomModelMapper extends BaseMapper<CustomModelDO> {

    int insertIgnore(CustomModelDO model);

    List<CustomModelDO> selectByType(@Param("type") int type);

    int clearByType(@Param("type") int type);
}
