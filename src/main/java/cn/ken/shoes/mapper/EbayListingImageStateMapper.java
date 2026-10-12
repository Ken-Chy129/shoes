package cn.ken.shoes.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 已上架商品组当前使用的图片来源。eBay 上的图片都是托管地址，看不出原图来源，所以单独记录。
 */
@Mapper
public interface EbayListingImageStateMapper {

    @Select("SELECT style_id FROM ebay_listing_image_state WHERE image_source <> 'stockx' ORDER BY style_id")
    List<String> selectPendingStyleIds();

    @Update("UPDATE ebay_listing_image_state SET image_source = 'stockx' WHERE style_id = #{styleId}")
    int markReplaced(@Param("styleId") String styleId);
}
