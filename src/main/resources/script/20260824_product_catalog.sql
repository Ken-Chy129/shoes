CREATE TABLE IF NOT EXISTS product_catalog
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
    manual_override   TINYINT(1) DEFAULT 0 NOT NULL COMMENT '人工资料保护标记',
    gmt_create        DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified      DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_product_catalog_brand (brand),
    INDEX idx_product_catalog_source (source),
    INDEX idx_product_catalog_modified (gmt_modified)
) COMMENT '跨平台商品资料库';

INSERT INTO product_catalog
    (model_no, title, brand, description, product_type, gender, color, colorway,
     upper_material, image_urls, source, source_updated_at, manual_override,
     gmt_create, gmt_modified)
SELECT model_no, title, brand, description, product_type, gender, color, colorway,
       upper_material, image_urls, source, source_updated_at, 0,
       gmt_create, gmt_modified
FROM ebay_product_cache
ON DUPLICATE KEY UPDATE model_no = VALUES(model_no);

-- ebay_product_cache 暂时保留，确认所有消费者均迁移后再另行下线。
