ALTER TABLE task_item
    ADD COLUMN highest_bid_price DECIMAL(10,2) NULL COMMENT 'StockX市场最高求购价' AFTER flex_lowest_price,
    ADD COLUMN highest_bid_count INT NULL COMMENT 'StockX市场最高求购价数量' AFTER highest_bid_price,
    ADD COLUMN second_highest_bid_price DECIMAL(10,2) NULL COMMENT 'StockX市场第二档求购价' AFTER highest_bid_count,
    ADD COLUMN second_highest_bid_count INT NULL COMMENT 'StockX市场第二档求购数量' AFTER second_highest_bid_price;
