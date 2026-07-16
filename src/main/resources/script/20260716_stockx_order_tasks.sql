ALTER TABLE task_item
    ADD COLUMN order_number VARCHAR(64) NULL COMMENT 'StockX订单号' AFTER eu_size,
    ADD COLUMN order_status VARCHAR(32) NULL COMMENT 'StockX订单状态' AFTER order_number,
    ADD COLUMN currency_code VARCHAR(8) NULL COMMENT '订单币种' AFTER order_status,
    ADD COLUMN sale_price DECIMAL(12, 2) NULL COMMENT '出售价格' AFTER currency_code,
    ADD COLUMN payout_amount DECIMAL(12, 2) NULL COMMENT '预计或实际货款' AFTER sale_price,
    ADD COLUMN sold_on DATETIME NULL COMMENT '出售日期' AFTER payout_amount;
