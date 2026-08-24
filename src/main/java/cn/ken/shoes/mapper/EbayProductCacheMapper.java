package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.EbayProductCacheDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EbayProductCacheMapper extends BaseMapper<EbayProductCacheDO> {

    int upsert(EbayProductCacheDO cache);
}
