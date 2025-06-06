package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.MustCrawlDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MustCrawlMapper extends BaseMapper<MustCrawlDO> {

    void insertIgnore(MustCrawlDO crawlDO);

    List<String> queryByPlatformList(@Param("platform") String platform);

    int deleteByPlatform(@Param("platform") String platform);

    List<String> selectAllModelNos();
}
