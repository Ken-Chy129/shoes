-- 上线新代码前执行；仅添加可空字段，旧代码可继续运行。
ALTER TABLE task_item
    ADD COLUMN shipment_id VARCHAR(64) NULL COMMENT 'StockX展示货运号';

-- 回滚应用时保留本列，避免丢失已抓取的货运号。
