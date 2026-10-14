-- 发布 eBay 添加追踪号码任务前执行；旧记录保持 NULL。
-- task_item 数据量大，强制 INSTANT（MySQL 8.0.29+ 仅改元数据），不支持时直接报错而不是拷表。
ALTER TABLE task_item
    ADD COLUMN tracking_number VARCHAR(64) NULL COMMENT '物流追踪号码',
    ADD COLUMN shipping_carrier VARCHAR(64) NULL COMMENT '物流公司',
    ALGORITHM = INSTANT;

-- 应用回滚无需删除新增列。
