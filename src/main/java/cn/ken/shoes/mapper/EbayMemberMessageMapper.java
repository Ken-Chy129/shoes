package cn.ken.shoes.mapper;

import cn.ken.shoes.model.entity.EbayMemberMessageDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface EbayMemberMessageMapper extends BaseMapper<EbayMemberMessageDO> {

    /** 认领新消息：已存在（推送重发/对账重复）时返回 0，调用方不再处理。 */
    @Insert("""
            INSERT IGNORE INTO ebay_member_message
                (message_id, item_id, item_title, sender_id, question_type, subject, body,
                 status, source, message_time)
            VALUES (#{messageId}, #{itemId}, #{itemTitle}, #{senderId}, #{questionType}, #{subject}, #{body},
                    #{status}, #{source}, #{messageTime})
            """)
    int insertIfAbsent(EbayMemberMessageDO message);

    /** 重新认领上次回复失败、或处理中途进程退出（超过 30 分钟仍是 processing）的消息，只有一个线程能认领成功。 */
    @Update("UPDATE ebay_member_message SET status = 'processing', error = NULL "
            + "WHERE message_id = #{messageId} AND (status = 'failed' "
            + "OR (status = 'processing' AND gmt_modified < NOW() - INTERVAL 30 MINUTE))")
    int claimFailed(@Param("messageId") String messageId);

    /** 该买家的其他消息是否已被自动回复过。shortcut: sender_id 无索引，消息量到十万级再加索引。 */
    @Select("SELECT COUNT(*) > 0 FROM ebay_member_message "
            + "WHERE sender_id = #{senderId} AND status = 'replied' AND message_id <> #{messageId}")
    boolean hasRepliedSender(@Param("senderId") String senderId, @Param("messageId") String messageId);

    @Update("""
            UPDATE ebay_member_message
            SET status = #{status}, matched_rule = #{matchedRule}, reply = #{reply}, error = #{error},
                replied_time = #{repliedTime}
            WHERE message_id = #{messageId}
            """)
    int updateResult(EbayMemberMessageDO message);

    @Select("""
            <script>
            SELECT * FROM ebay_member_message
            <if test="status != null and status != ''">WHERE status = #{status}</if>
            ORDER BY gmt_create DESC
            LIMIT #{limit}
            </script>
            """)
    List<EbayMemberMessageDO> selectRecent(@Param("status") String status, @Param("limit") int limit);

    /** gmt_create 只精确到秒，用 >= 避免同一秒内后写入的消息漏掉，调用方按 messageId 去重。 */
    @Select("SELECT * FROM ebay_member_message WHERE gmt_create >= #{after} ORDER BY gmt_create LIMIT #{limit}")
    List<EbayMemberMessageDO> selectCreatedAfter(@Param("after") java.util.Date after, @Param("limit") int limit);
}
