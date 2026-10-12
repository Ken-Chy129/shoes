-- 多个货号用 "/" 拼接的组合货号会超过 64 位，导致资料库写入失败。
ALTER TABLE product_catalog MODIFY model_no VARCHAR(255) NOT NULL COMMENT '商品货号';

-- 图片改为优先使用 StockX：删除未人工维护、且没有 StockX 图片的 KC 缓存，
-- 下次上架会重新按 "StockX 优先，不足 5 张用 KC 补齐" 生成资料。
DELETE FROM product_catalog
WHERE manual_override = 0
  AND image_urls NOT LIKE '%images.stockx.com%'
  AND image_urls NOT LIKE '%stockx-assets.imgix.net%';
