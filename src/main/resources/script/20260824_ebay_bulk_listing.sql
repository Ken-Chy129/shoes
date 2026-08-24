CREATE TABLE ebay_product_cache
(
    model_no          VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '商品货号',
    title             VARCHAR(255) NOT NULL COMMENT '商品标题',
    brand             VARCHAR(65)  NULL COMMENT '品牌',
    description       TEXT         NULL COMMENT '商品描述',
    product_type      VARCHAR(64)  NULL COMMENT '商品类型',
    gender            VARCHAR(32)  NULL COMMENT '性别',
    color             VARCHAR(128) NULL COMMENT '颜色',
    colorway          VARCHAR(255) NULL COMMENT '配色',
    upper_material    VARCHAR(128) NULL COMMENT '鞋面材质',
    image_urls        TEXT         NOT NULL COMMENT '图片URL JSON数组',
    source            VARCHAR(32)  NOT NULL COMMENT '资料来源',
    source_updated_at DATETIME     NOT NULL COMMENT '来源更新时间',
    gmt_create        DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified      DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP
) COMMENT 'eBay商品资料缓存';

ALTER TABLE task_item
    ADD COLUMN sku VARCHAR(50) NULL COMMENT 'eBay卖家SKU' AFTER listing_id,
    ADD COLUMN offer_id VARCHAR(64) NULL COMMENT 'eBay Offer ID' AFTER sku;
