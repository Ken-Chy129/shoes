package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.PoisonPriceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface PoisonPriceMapper extends BaseMapper<PoisonPriceDO> {

    Long count();

    int insertOverwrite(PoisonPriceDO poisonPrice);

    List<PoisonPriceDO> selectPage(@Param("startIndex") Long startIndex, @Param("pageSize") Integer pageSize);

    List<PoisonPriceDO> selectListByModelNos(Set<String> modelNos);

    Integer selectPriceByModelNoAndSize(@Param("modelNo") String modelNo, @Param("euSize") String euSize);
}
