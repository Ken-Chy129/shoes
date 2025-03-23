package cn.ken.shoes.mapper;

import cn.ken.shoes.model.brand.BrandRequest;
import cn.ken.shoes.model.entity.BrandDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BrandMapper extends BaseMapper<BrandDO> {

    List<BrandDO> selectByPlatform(@Param("platform") String platform);

    List<BrandDO> selectPage(BrandRequest request);

    Long count(BrandRequest request);

    void updateByName(BrandDO brand);

    void updateDefaultCrawlCnt(@Param("cnt") Integer cnt);

    void batchInsertOrUpdate(@Param("brands") List<BrandDO> brands);
}
