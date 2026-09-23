-- eBay 在售商品映射：SKU/offer 与本地货号、尺码的长期对照表。
-- 改价、下架都读这张表，不再依赖会被历史清理删除的任务明细。
CREATE TABLE IF NOT EXISTS ebay_listing
(
    sku            VARCHAR(50)    NOT NULL PRIMARY KEY COMMENT 'eBay卖家SKU',
    offer_id       VARCHAR(64)    NOT NULL COMMENT 'eBay Offer ID',
    listing_id     VARCHAR(64)    NULL COMMENT 'eBay Listing ID',
    style_id       VARCHAR(256)   NOT NULL COMMENT '货号',
    size           VARCHAR(16)    NULL COMMENT '原始尺码',
    eu_size        VARCHAR(16)    NULL COMMENT 'EU码',
    title          VARCHAR(255)   NULL COMMENT '标题',
    brand          VARCHAR(64)    NULL COMMENT '品牌',
    price          DECIMAL(10, 2) NULL COMMENT '最近一次写入eBay的价格(USD)',
    quantity       INT            NULL COMMENT '最近一次写入eBay的库存',
    status         VARCHAR(16)    NOT NULL DEFAULT 'active' COMMENT 'active/ended',
    source_task_id BIGINT         NULL COMMENT '最近一次写入该映射的任务ID',
    gmt_create     DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified   DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_offer_id (offer_id),
    KEY idx_style_id (style_id(64)),
    KEY idx_status (status)
) COMMENT 'eBay在售商品映射';

-- 从现有任务明细回填：批量上架成功记录 + 改价任务的审计明细
-- （改价明细里保留了已被清理的旧上架任务的映射）。每个 SKU 取最新一条。
INSERT INTO ebay_listing
    (sku, offer_id, listing_id, style_id, size, eu_size, title, brand, price, quantity, status, source_task_id)
SELECT sku, offer_id, listing_id, style_id, size, eu_size, title, brand, price, quantity, 'active', task_id
FROM (SELECT ti.sku,
             ti.offer_id,
             ti.listing_id,
             TRIM(ti.style_id)                                                 AS style_id,
             ti.size,
             ti.eu_size,
             ti.title,
             ti.brand,
             IF(t.task_type = 'ebay_price_sync', COALESCE(ti.target_price, ti.current_price),
                ti.current_price)                                              AS price,
             ti.listing_quantity                                               AS quantity,
             ti.task_id,
             ROW_NUMBER() OVER (PARTITION BY ti.sku ORDER BY ti.operate_time DESC, ti.id DESC) AS rn
      FROM task_item ti
               INNER JOIN task t ON t.id = ti.task_id
      WHERE t.platform = 'ebay'
        AND ti.sku IS NOT NULL AND ti.sku != ''
        AND ti.offer_id IS NOT NULL AND ti.offer_id != ''
        AND ti.style_id IS NOT NULL AND ti.style_id != ''
        AND ((t.task_type = 'ebay_bulk_listing' AND ti.operate_result LIKE '上架成功%')
          OR t.task_type = 'ebay_price_sync')) latest
WHERE rn = 1
ON DUPLICATE KEY UPDATE sku = ebay_listing.sku;
