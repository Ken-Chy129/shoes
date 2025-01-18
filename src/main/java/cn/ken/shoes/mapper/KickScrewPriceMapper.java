package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.KickScrewPriceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KickScrewPriceMapper extends BaseMapper<KickScrewPriceDO> {

    long count();

    List<KickScrewPriceDO> selectPage(@Param("startIndex") long startIndex, @Param("pageSize") int pageSize);
}
