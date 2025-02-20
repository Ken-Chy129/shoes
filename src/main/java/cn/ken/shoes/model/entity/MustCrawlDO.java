package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("must_crawl")
public class MustCrawlDO {

    private String platform;

    private String modelNo;
}
