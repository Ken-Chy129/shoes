-- eBay 上的图片都是托管地址，看不出原图来源；单独记录哪些在架货号还在用 KC 图片，
-- 供 EbayImageBackfillService 逐个替换成 StockX 图片，额度用完后可以断点续跑。
CREATE TABLE IF NOT EXISTS ebay_listing_image_state
(
    style_id     VARCHAR(255) NOT NULL PRIMARY KEY COMMENT '货号',
    image_source VARCHAR(32)  NOT NULL COMMENT '已上架商品组当前图片来源(kickscrew/stockx)',
    gmt_create   DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    gmt_modified DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP
) COMMENT 'eBay已上架商品图片来源，供图片替换补数据使用';

-- 初始化数据需按 2026-10-13 清理前的 product_catalog 备份执行（见部署记录），
-- 把上架时资料库图片不含 StockX 的在架货号记为 kickscrew。
