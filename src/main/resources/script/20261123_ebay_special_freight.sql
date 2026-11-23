-- eBay 盈利改价：特殊货号运费，独立维护，每轮改价读取。target 为货号（大写）或 eBay 物品编号。
CREATE TABLE IF NOT EXISTS ebay_special_freight
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    target       VARCHAR(64)   NOT NULL COMMENT '货号(大写)或eBay物品编号',
    eu_size      VARCHAR(16)   NOT NULL DEFAULT '' COMMENT 'EU尺码，空=全尺码',
    freight      DECIMAL(10, 2) NOT NULL COMMENT 'AG链接运费(元)，非AG链接按×1.2',
    gmt_create   DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_target_size (target, eu_size)
) COMMENT 'eBay盈利改价特殊货号运费';
