package cn.ken.shoes.model.search;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

@Data
public class SearchTaskVO {

    private Long id;

    /**
     * 搜索关键词
     */
    private String query;

    /**
     * 排序规则，逗号分隔
     */
    private String sorts;

    /**
     * 每个sort查询的页数
     */
    private Integer pageCount;

    /**
     * 搜索类型: shoes-鞋类, clothes-服饰
     */
    private String searchType;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progress;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 生成的文件路径
     */
    private String filePath;

    /**
     * 任务开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startTime;

    /**
     * 任务结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date gmtCreate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date gmtModified;
}
