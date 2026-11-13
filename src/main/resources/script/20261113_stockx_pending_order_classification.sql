-- 上线新代码前执行；旧记录保留 NULL，重新获取待处理订单后才有判定结果。
ALTER TABLE task_item
    ADD COLUMN replenishment_order TINYINT(1) NULL
        COMMENT '待处理订单补单判定：1补单，0正常单，NULL未判定或不适用';

-- 回滚应用时保留本列，避免丢失判定结果。
