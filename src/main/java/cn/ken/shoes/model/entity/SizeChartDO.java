package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("size_chart")
public class SizeChartDO {

    private String brand;

    private String gender;

    /**
     * 欧码
     */
    private String euSize;

    /**
     * 美码
     */
    private String usSize;

    /**
     * 男美码
     */
    @TableField("men_us_size")
    private String menUSSize;

    /**
     * 女美码
     */
    @TableField("women_us_size")
    private String womenUSSize;

    /**
     * 英码
     */
    private String ukSize;

    /**
     * CM
     */
    private String cmSize;
}
