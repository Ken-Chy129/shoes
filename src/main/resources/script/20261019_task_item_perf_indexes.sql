-- task_item 性能索引（2026-10-19 已在生产在线执行，约 1268 万行，各耗时 6~8 分钟，不锁表）。
--   idx_task_operate_time    : 明细分页 WHERE task_id=? ORDER BY operate_time，避免百万级 filesort
--   idx_result_operate_time  : 上架/压价/下架对账 WHERE operate_result IN (...) AND operate_time<? ORDER BY operate_time
--   idx_task_result          : 任务列表统计只回表成功/处理中结果
-- 大表执行前建议临时调大在线 DDL 日志：SET GLOBAL innodb_online_alter_log_max_size = 1073741824;
ALTER TABLE task_item
    ADD INDEX idx_task_operate_time (task_id, operate_time),
    ADD INDEX idx_result_operate_time (operate_result, operate_time),
    ALGORITHM = INPLACE, LOCK = NONE;

ALTER TABLE task_item
    ADD INDEX idx_task_result (task_id, operate_result),
    ALGORITHM = INPLACE, LOCK = NONE;

-- 新索引覆盖 task_id 前缀后，可在确认无 FORCE INDEX (idx_task_id) 引用后删除旧索引：
-- ALTER TABLE task_item DROP INDEX idx_task_id, ALGORITHM = INPLACE, LOCK = NONE;
