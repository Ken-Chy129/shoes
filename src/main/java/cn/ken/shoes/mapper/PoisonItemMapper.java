package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.PoisonItemDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PoisonItemMapper extends BaseMapper<PoisonItemDO> {

    int count();

    void deleteAll();

    PoisonItemDO selectByArticleNumber(@Param("articleNumber") String articleNumber);

    List<String> selectModelNoByReleaseYear(@Param("releaseYear") Integer releaseYear);

    List<PoisonItemDO> selectSpuId(@Param("startIndex") Integer startIndex, @Param("pageSize") Integer pageSize);

    void insertIgnore(PoisonItemDO item);
}
