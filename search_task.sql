-- 创建search_task表
CREATE TABLE IF NOT EXISTS `search_task` (
    `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `query` VARCHAR(255) NOT NULL COMMENT '搜索关键词',
    `sorts` VARCHAR(500) NOT NULL COMMENT '排序规则，逗号分隔',
    `page_count` INT(11) NOT NULL COMMENT '每个sort查询的页数',
    `category` VARCHAR(20) NOT NULL DEFAULT 'shoes' COMMENT '商品类别：shoes-鞋类, apparel-服饰',
    `progress` INT(11) NOT NULL DEFAULT 0 COMMENT '进度百分比(0-100)',
    `status` VARCHAR(20) NOT NULL COMMENT '任务状态：pending/running/success/failed',
    `file_path` VARCHAR(500) DEFAULT NULL COMMENT '生成的文件路径',
    `start_time` DATETIME DEFAULT NULL COMMENT '任务开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '任务结束时间',
    `gmt_create` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `version` INT(11) DEFAULT 0 COMMENT '版本号',
    PRIMARY KEY (`id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_category` (`category`),
    INDEX `idx_gmt_create` (`gmt_create`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='StockX搜索任务表';
