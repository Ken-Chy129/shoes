package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.SizeChartDO;
import cn.ken.shoes.model.request.SizeChartUpdateRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SizeChartMapper extends BaseMapper<SizeChartDO> {

    List<String> selectDistinctBrands();

    Long count(@Param("brand") String brand, @Param("gender") String gender);

    List<SizeChartDO> selectPage(@Param("brand") String brand,
                                  @Param("gender") String gender,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    int updateByOldKey(@Param("req") SizeChartUpdateRequest request);

    int deleteByKey(@Param("brand") String brand,
                    @Param("gender") String gender,
                    @Param("euSize") String euSize,
                    @Param("usSize") String usSize);
}
