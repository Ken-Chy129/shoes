package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.MustCrawlDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MustCrawlMapper extends BaseMapper<MustCrawlDO> {

    List<String> queryByPlatformList(@Param("platform") String platform);

    void deleteByPlatform(@Param("platform") String platform);
}
