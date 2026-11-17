package cn.ken.shoes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** eBay 买家提问与自动回复记录，按 eBay 消息 ID 唯一。 */
@Data
@TableName("ebay_member_message")
public class EbayMemberMessageDO {

    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_REPLIED = "replied";
    /** 未命中规则或自动回复已关闭，需要人工回复。 */
    public static final String STATUS_MANUAL = "manual";
    public static final String STATUS_FAILED = "failed";
    /** 非买家提问（例如自己发出的消息），不处理。 */
    public static final String STATUS_SKIPPED = "skipped";

    public static final String SOURCE_PUSH = "push";
    public static final String SOURCE_RECONCILE = "reconcile";

    @TableId(value = "message_id", type = IdType.INPUT)
    private String messageId;
    private String itemId;
    private String itemTitle;
    private String senderId;
    private String questionType;
    private String subject;
    private String body;
    private String status;
    private String matchedRule;
    private String reply;
    private String error;
    private String source;
    private Date messageTime;
    private Date repliedTime;
    private Date gmtCreate;
    private Date gmtModified;
}
