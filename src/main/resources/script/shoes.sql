-- ========================================
-- Shoes Price Tracking System Database Schema
-- 球鞋价格追踪系统数据库表结构
-- ========================================

-- -------------------------------------------
-- 品牌表：存储各平台的品牌信息
-- -------------------------------------------
CREATE TABLE brand
(
    name       VARCHAR(64)          NOT NULL COMMENT '品牌名称',
    total      INT                  NOT NULL COMMENT '该品牌商品总数',
    crawl_cnt  INT                  NULL     COMMENT '已爬取数量',
    need_crawl TINYINT(1) DEFAULT 1 NOT NULL COMMENT '是否需要爬取：1-是，0-否',
    platform   VARCHAR(16)          NULL     COMMENT '平台标识：kickscrew/stockx/poison',
    CONSTRAINT brand_pk UNIQUE (name, platform)
) COMMENT '品牌信息表';

-- -------------------------------------------
-- 自定义商品表：用户自定义的货号和尺码配置
-- -------------------------------------------
CREATE TABLE custom_model
(
    model_no VARCHAR(64) NOT NULL COMMENT '商品货号',
    eu_size  VARCHAR(16) NOT NULL COMMENT '欧码尺码',
    type     INT         NOT NULL COMMENT '类型：1-黑名单，2-白名单，3-特殊定价',
    CONSTRAINT custom_model_pk UNIQUE (model_no, eu_size, type)
) COMMENT '自定义商品配置表';

-- -------------------------------------------
-- KickScrew商品表：存储KickScrew平台的商品基础信息
-- -------------------------------------------
CREATE TABLE kick_screw_item
(
    model_no     VARCHAR(32)  NOT NULL PRIMARY KEY COMMENT '商品货号（主键）',
    title        VARCHAR(128) NULL     COMMENT '商品标题',
    brand        VARCHAR(32)  NULL     COMMENT '品牌名称',
    product_type VARCHAR(32)  NULL     COMMENT '商品类型：shoes/apparel等',
    gender       VARCHAR(16)  NULL     COMMENT '性别：men/women/unisex/kids',
    release_year INT          NULL     COMMENT '发售年份',
    image        TEXT         NULL     COMMENT '商品图片URL',
    handle       VARCHAR(256) NULL     COMMENT 'URL路径标识'
) COMMENT 'KickScrew商品信息表';

CREATE INDEX kick_screw_item_brand_index ON kick_screw_item (brand);

-- -------------------------------------------
-- KickScrew价格表：存储KickScrew平台的商品价格
-- -------------------------------------------
CREATE TABLE kick_screw_price
(
    id       BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    model_no VARCHAR(128) NULL COMMENT '商品货号',
    eu_size  VARCHAR(16)  NULL COMMENT '欧码尺码',
    price    INT          NULL COMMENT '价格（单位：分/cents）',
    CONSTRAINT model_no_size_ukey UNIQUE (model_no, eu_size)
) COMMENT 'KickScrew价格表';

-- -------------------------------------------
-- 必须爬取表：存储需要强制爬取的商品
-- -------------------------------------------
CREATE TABLE must_crawl
(
    platform VARCHAR(32) NOT NULL COMMENT '平台标识',
    model_no VARCHAR(64) NOT NULL COMMENT '商品货号'
) COMMENT '强制爬取商品表';

-- -------------------------------------------
-- 得物商品表：存储得物平台的商品基础信息
-- -------------------------------------------
CREATE TABLE poison_item
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    article_number VARCHAR(64)  NOT NULL COMMENT '商品货号',
    brand_name     VARCHAR(32)  NULL     COMMENT '品牌名称',
    category_name  VARCHAR(32)  NULL     COMMENT '分类名称',
    spu_id         INT          NOT NULL COMMENT '得物SPU ID',
    spu_logo       TEXT         NULL     COMMENT '商品图片URL',
    title          VARCHAR(256) NOT NULL COMMENT '商品标题',
    release_year   INT          NULL     COMMENT '发售年份',
    CONSTRAINT poison_item_pk_2 UNIQUE (article_number)
) COMMENT '得物商品信息表';

-- -------------------------------------------
-- 得物价格表：存储得物平台的商品价格
-- -------------------------------------------
CREATE TABLE poison_price
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    model_no    VARCHAR(128) NULL COMMENT '商品货号',
    eu_size     VARCHAR(16)  NULL COMMENT '欧码尺码',
    price       INT          NULL COMMENT '价格（单位：分）',
    update_time DATE         NULL COMMENT '价格更新时间',
    CONSTRAINT poison_price_pk UNIQUE (model_no, eu_size)
) COMMENT '得物价格表';

-- -------------------------------------------
-- StockX搜索任务表：存储StockX平台的搜索任务
-- -------------------------------------------
CREATE TABLE search_task
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY    COMMENT '主键ID',
    platform     VARCHAR(255)                         NOT NULL COMMENT '平台标识',
    type         VARCHAR(32)                          NULL     COMMENT '任务类型：keyword-关键词搜索，model-货号搜索',
    query        TEXT                                 NOT NULL COMMENT '搜索关键词或货号',
    sorts        VARCHAR(500)                         NOT NULL COMMENT '排序规则，逗号分隔',
    page_count   INT                                  NOT NULL COMMENT '每个排序规则查询的页数',
    search_type  VARCHAR(20)                          NULL     COMMENT '搜索类型：shoes-鞋类，clothes-服饰',
    progress     INT      DEFAULT 0                   NOT NULL COMMENT '进度百分比(0-100)',
    status       VARCHAR(20)                          NOT NULL COMMENT '任务状态：pending-待执行，running-执行中，success-成功，failed-失败',
    file_path    VARCHAR(500)                         NULL     COMMENT '生成的文件路径',
    start_time   DATETIME                             NULL     COMMENT '任务开始时间',
    end_time     DATETIME                             NULL     COMMENT '任务结束时间',
    gmt_create   DATETIME DEFAULT CURRENT_TIMESTAMP   NOT NULL COMMENT '创建时间',
    gmt_modified DATETIME DEFAULT CURRENT_TIMESTAMP   NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    version      INT      DEFAULT 0                   NULL     COMMENT '乐观锁版本号'
) COMMENT 'StockX搜索任务表';

CREATE INDEX idx_gmt_create   ON search_task (gmt_create);
CREATE INDEX idx_search_type  ON search_task (search_type);
CREATE INDEX idx_status       ON search_task (status);

-- -------------------------------------------
-- 尺码对照表：存储不同品牌的尺码转换关系
-- -------------------------------------------
CREATE TABLE size_chart
(
    brand         VARCHAR(64) NOT NULL COMMENT '品牌名称',
    gender        VARCHAR(16) NOT NULL COMMENT '性别：men/women/kids',
    eu_size       VARCHAR(16) NOT NULL COMMENT '欧码尺码',
    us_size       VARCHAR(16) NOT NULL COMMENT '美码尺码（通用）',
    men_us_size   VARCHAR(16) NULL COMMENT '男款美码尺码',
    women_us_size VARCHAR(16) NULL COMMENT '女款美码尺码',
    uk_size       VARCHAR(16) NULL COMMENT '英码尺码',
    cm_size       VARCHAR(16) NULL COMMENT '厘米尺码',
    dunk_brand    VARCHAR(64) NULL COMMENT 'Dunk品牌特殊标识',
    stockx_brand  VARCHAR(64) NULL COMMENT 'StockX品牌标识',
    UNIQUE KEY uk_brand_gender_size (brand, gender, eu_size, us_size)
) COMMENT '尺码对照表';

-- -------------------------------------------
-- 特殊定价表：存储需要特殊处理的商品价格
-- -------------------------------------------
CREATE TABLE special_price
(
    model_no VARCHAR(128) NOT NULL COMMENT '商品货号',
    eu_size  VARCHAR(16)  NOT NULL COMMENT '欧码尺码',
    price    INT          NOT NULL COMMENT '特殊价格（单位：分）'
) COMMENT '特殊定价表';

-- -------------------------------------------
-- StockX商品表：存储StockX平台的商品基础信息
-- -------------------------------------------
CREATE TABLE stockx_item
(
    product_id   VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT 'StockX商品ID（主键）',
    brand        VARCHAR(32)  NULL     COMMENT '品牌名称',
    product_type VARCHAR(32)  NULL     COMMENT '商品类型：sneakers/apparel等',
    model_no     VARCHAR(64)  NULL     COMMENT '商品货号',
    url_key      VARCHAR(128) NULL     COMMENT 'URL路径标识',
    title        VARCHAR(128) NULL     COMMENT '商品标题'
) COMMENT 'StockX商品信息表';

-- -------------------------------------------
-- StockX价格表：存储StockX平台的商品价格
-- -------------------------------------------
CREATE TABLE stockx_price
(
    variant_id         VARCHAR(128) NOT NULL PRIMARY KEY COMMENT 'StockX变体ID（主键）',
    model_no           VARCHAR(64)  NOT NULL COMMENT '商品货号',
    product_id         VARCHAR(128) NULL     COMMENT 'StockX商品ID',
    eu_size            VARCHAR(16)  NULL     COMMENT '欧码尺码',
    sell_faster_amount INT          NULL     COMMENT '快速出售价格（单位：美分）',
    earn_more_amount   INT          NULL     COMMENT '赚更多价格（单位：美分）',
    sell_now_amount    INT          NULL     COMMENT '立即出售价格（单位：美分）'
) COMMENT 'StockX价格表';

-- -------------------------------------------
-- 任务表：存储系统任务执行记录
-- -------------------------------------------
CREATE TABLE task
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    platform     VARCHAR(16) NOT NULL COMMENT '平台标识：kickscrew/stockx/poison',
    task_type    VARCHAR(32) NOT NULL COMMENT '任务类型：price_sync/item_crawl等',
    start_time   DATETIME    NULL     COMMENT '任务开始时间',
    end_time     DATETIME    NULL     COMMENT '任务结束时间',
    cost         VARCHAR(32) NULL     COMMENT '任务耗时',
    status       VARCHAR(16) NOT NULL COMMENT '任务状态：running/success/failed/stop/cancel',
    round        INT         DEFAULT 0 COMMENT '执行轮次'
) COMMENT '任务执行记录表';

-- -------------------------------------------
-- 任务明细表：存储任务操作的商品详情
-- -------------------------------------------
CREATE TABLE task_item
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id        BIGINT       NOT NULL COMMENT '关联任务ID',
    round          INT          DEFAULT 0 COMMENT '执行轮次',
    title          VARCHAR(255) NULL     COMMENT '标题',
    listing_id     VARCHAR(64)  NULL     COMMENT '上架ID',
    product_id     VARCHAR(64)  NULL     COMMENT '商品ID',
    style_id       VARCHAR(64)  NULL     COMMENT '货号',
    size           VARCHAR(16)  NULL     COMMENT '尺码',
    eu_size        VARCHAR(16)  NULL     COMMENT 'EU码',
    current_price  DECIMAL(10,2) NULL    COMMENT '当前售价',
    lowest_price   DECIMAL(10,2) NULL    COMMENT '最低价',
    poison_price   DECIMAL(10,2) NULL    COMMENT '毒价格',
    poison_35_price DECIMAL(10,2) NULL   COMMENT '毒3.5价格',
    profit_35      DECIMAL(10,2) NULL    COMMENT '3.5利润',
    profit_rate_35 DECIMAL(10,4) NULL    COMMENT '3.5利润率',
    operate_result VARCHAR(255) NULL     COMMENT '操作结果',
    operate_time   DATETIME     NULL     COMMENT '操作时间',
    INDEX idx_task_id (task_id)
) COMMENT '任务明细表';