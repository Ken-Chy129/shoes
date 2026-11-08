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

    List<EbayListingDO> selectBySkus(@Param("skus") java.util.Collection<String> skus);

    List<EbayListingDO> selectByListingIds(@Param("listingIds") java.util.Collection<String> listingIds);

    int markEnded(@Param("sku") String sku);

    /** 按编号下架系统刊登后，把挂在该刊登上的在架映射标记为 ended。 */
    int markListingEnded(@Param("listingId") String listingId);

    /** 系统刊登重新发布后换了刊登号：把旧刊登号的映射指向新刊登号并恢复为 active。 */
    int relinkListing(@Param("oldListingId") String oldListingId, @Param("newListingId") String newListingId);

    /** 整组下架后把同一货号、同一区域的在架映射全部标记为 ended。 */
    int markGroupEnded(@Param("styleId") String styleId, @Param("region") String region);

    /** 标题回填后同步同一货号、同一区域在架映射的标题快照。 */
    int updateGroupTitle(@Param("styleId") String styleId, @Param("region") String region,
                         @Param("title") String title);

    /** 更新价格/库存快照；传 null 的字段保持原值（定时改价只改价格时不能把库存写成 NULL）。 */
    int updatePriceAndQuantity(@Param("sku") String sku,
                               @Param("price") BigDecimal price,
                               @Param("quantity") Integer quantity);
}
