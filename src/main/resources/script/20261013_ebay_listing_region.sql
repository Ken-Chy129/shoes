-- eBay 改价改为直接按映射表的 offerId 批量写价格，不再逐个 SKU 读取 offer，
-- 因此发货区域（港区/美区）也要记在映射表里，改价只处理港区。
ALTER TABLE ebay_listing
    ADD COLUMN region VARCHAR(8) NOT NULL DEFAULT 'hk' COMMENT '发货区域 hk/us' AFTER source_task_id;

-- 回填：最近一次写入映射的上架任务参数里带 region=us 的记为美区。
UPDATE ebay_listing l
    INNER JOIN task t ON t.id = l.source_task_id
SET l.region = 'us'
WHERE t.task_type = 'ebay_bulk_listing'
  AND JSON_UNQUOTE(JSON_EXTRACT(t.params, '$.region')) = 'us';
