package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.EbayListingDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface EbayListingMapper extends BaseMapper<EbayListingDO> {

    /** 上架成功后写入或覆盖映射，并把状态恢复为 active。 */
    int upsert(EbayListingDO listing);

    List<EbayListingDO> selectActive();

    List<EbayListingDO> selectAll();

    int markEnded(@Param("sku") String sku);

    int updatePriceAndQuantity(@Param("sku") String sku,
                               @Param("price") BigDecimal price,
                               @Param("quantity") Integer quantity);
}
