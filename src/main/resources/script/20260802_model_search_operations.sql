ALTER TABLE task_item
    ADD COLUMN flex_lowest_price DECIMAL(10,2) NULL COMMENT 'Flex/寄存市场最低价' AFTER lowest_price,
    ADD COLUMN listing_quantity INT NULL COMMENT '本次请求的上架数量' AFTER flex_lowest_price;
