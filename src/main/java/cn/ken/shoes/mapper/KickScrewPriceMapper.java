package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.KickScrewPriceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface KickScrewPriceMapper extends BaseMapper<KickScrewPriceDO> {

    long count();

    List<KickScrewPriceDO> selectPage(@Param("startIndex") long startIndex, @Param("pageSize") int pageSize);

    List<KickScrewPriceDO> selectListByModelNos(Set<String> modelNos);

    KickScrewPriceDO selectByModelNoAndSize(@Param("modelNo") String modelNo, @Param("euSize") String euSize);
}
