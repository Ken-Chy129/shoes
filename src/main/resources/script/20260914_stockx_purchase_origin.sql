-- 发布新版后端前执行；旧记录保持 NULL，重新运行获取任务后采集。
ALTER TABLE task_item
    ADD COLUMN purchase_order_number VARCHAR(64) NULL COMMENT 'StockX原购买订单号',
    ADD COLUMN purchase_price DECIMAL(12,2) NULL COMMENT 'StockX原购买成交价（非含税费总成本）',
    ADD COLUMN purchase_currency_code VARCHAR(10) NULL COMMENT 'StockX原购买币种';

-- 应用回滚无需删除新增列（保留已采集数据）。
