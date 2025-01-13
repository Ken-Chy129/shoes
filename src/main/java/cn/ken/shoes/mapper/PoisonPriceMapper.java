package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.BrandDO;
import cn.ken.shoes.model.entity.PoisonPriceDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PoisonPriceMapper extends BaseMapper<PoisonPriceDO> {


}
