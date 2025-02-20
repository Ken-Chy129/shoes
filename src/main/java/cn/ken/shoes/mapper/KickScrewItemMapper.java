package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.KickScrewItemDO;
import cn.ken.shoes.model.kickscrew.KickScrewItemRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KickScrewItemMapper extends BaseMapper<KickScrewItemDO> {

    Integer count(KickScrewItemRequest request);

    List<KickScrewItemDO> selectPageByCondition(KickScrewItemRequest request);

    void insertIgnore(KickScrewItemDO kickScrewItemDO);

    void deleteAll();

    List<KickScrewItemDO> selectListByBrand(@Param("brand") String brand);

    List<String> selectModelNoByReleaseYear(@Param("releaseYear") Integer releaseYear);

    List<KickScrewItemDO> selectItemsWithPoisonPrice(@Param("startIndex") Integer startIndex, @Param("pageSize") Integer pageSize);

    List<KickScrewItemDO> selectAllItemsWithPoisonPrice();

    Long countItemsWithPoisonPrice();

    String selectHandleByModelNo(@Param("modelNo") String modelNo);
}
