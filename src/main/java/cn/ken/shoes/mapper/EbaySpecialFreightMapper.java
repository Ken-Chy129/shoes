package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.EbaySpecialFreightDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EbaySpecialFreightMapper extends BaseMapper<EbaySpecialFreightDO> {

    @Insert("""
            INSERT INTO ebay_special_freight (target, eu_size, freight)
            VALUES (#{target}, #{euSize}, #{freight})
            ON DUPLICATE KEY UPDATE freight = VALUES(freight)
            """)
    int upsert(EbaySpecialFreightDO row);

    @Select("SELECT id, target, eu_size, freight, gmt_modified FROM ebay_special_freight ORDER BY gmt_modified DESC, id DESC")
    List<EbaySpecialFreightDO> selectAll();
}
