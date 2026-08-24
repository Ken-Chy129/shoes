ALTER TABLE product_catalog
    ADD COLUMN model_name VARCHAR(128) NULL COMMENT 'eBay型号/Model' AFTER product_type,
    ADD COLUMN product_line VARCHAR(128) NULL COMMENT 'eBay产品线/Product Line' AFTER model_name,
    ADD COLUMN country_of_origin VARCHAR(64) NULL COMMENT '商品原产国，不根据仓库推断' AFTER product_line;
