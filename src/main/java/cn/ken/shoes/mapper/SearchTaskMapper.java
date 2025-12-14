package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.SearchTaskDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface SearchTaskMapper extends BaseMapper<SearchTaskDO> {

    /**
     * 根据条件查询任务列表
     */
    List<SearchTaskDO> selectByCondition(@Param("platform") String platform,
                                          @Param("status") String status,
                                          @Param("type") String type,
                                          @Param("startIndex") Integer startIndex,
                                          @Param("pageSize") Integer pageSize);

    /**
     * 查询任务总数
     */
    Long count(@Param("status") String status, @Param("type") String type);

    /**
     * 更新任务进度
     */
    void updateProgress(@Param("id") Long id, @Param("progress") Integer progress);

    /**
     * 更新任务状态和结束时间
     */
    void updateStatus(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("endTime") Date endTime,
                      @Param("filePath") String filePath);

    /**
     * 更新任务开始时间和状态
     */
    void updateStartStatus(@Param("id") Long id,
                           @Param("status") String status,
                           @Param("startTime") Date startTime);
}
