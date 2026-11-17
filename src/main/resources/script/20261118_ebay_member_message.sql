-- eBay 买家提问（Ask Seller a Question）：推送/对账收到的消息与自动回复记录。
-- message_id 唯一，推送重发、对账重复拉取都只处理一次。
CREATE TABLE IF NOT EXISTS ebay_member_message
(
    message_id    VARCHAR(64)   NOT NULL PRIMARY KEY COMMENT 'eBay消息ID(Question.MessageID)',
    item_id       VARCHAR(32)   NULL COMMENT '刊登号',
    item_title    VARCHAR(255)  NULL COMMENT '刊登标题',
    sender_id     VARCHAR(64)   NULL COMMENT '买家ID',
    question_type VARCHAR(32)   NULL COMMENT 'eBay问题分类',
    subject       VARCHAR(255)  NULL COMMENT '标题',
    body          VARCHAR(4000) NULL COMMENT '提问内容',
    status        VARCHAR(16)   NOT NULL COMMENT 'processing/replied/manual/failed/skipped',
    matched_rule  VARCHAR(64)   NULL COMMENT '命中的回复规则',
    reply         VARCHAR(2000) NULL COMMENT '已发送的回复',
    error         VARCHAR(255)  NULL COMMENT '失败原因',
    source        VARCHAR(16)   NOT NULL COMMENT 'push/reconcile',
    message_time  DATETIME      NULL COMMENT '买家发送时间',
    replied_time  DATETIME      NULL COMMENT '回复时间',
    gmt_create    DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified  DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status),
    KEY idx_gmt_create (gmt_create)
) COMMENT 'eBay买家提问与自动回复';
