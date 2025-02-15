package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.PoisonItemDO;
import cn.ken.shoes.model.shoes.ShoesRequest;
import cn.ken.shoes.model.shoes.ShoesVO;
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

    Long shoesCount(ShoesRequest request);

    List<ShoesVO> shoes(ShoesRequest request);
}
