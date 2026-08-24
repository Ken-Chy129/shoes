package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.ProductCatalogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductCatalogMapper extends BaseMapper<ProductCatalogDO> {

    long count(@Param("modelNo") String modelNo,
               @Param("brand") String brand,
               @Param("source") String source);

    List<ProductCatalogDO> selectPage(@Param("modelNo") String modelNo,
                                      @Param("brand") String brand,
                                      @Param("source") String source,
                                      @Param("offset") long offset,
                                      @Param("pageSize") int pageSize);

    int upsertFromSource(ProductCatalogDO product);

    int updateManual(ProductCatalogDO product);
}
