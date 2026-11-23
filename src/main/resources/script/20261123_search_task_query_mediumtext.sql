-- 货号搜索任务把全部货号以换行拼入 query；TEXT 上限 64KB 约 5~6 千个货号，改为 MEDIUMTEXT(16MB)
ALTER TABLE search_task MODIFY query MEDIUMTEXT NOT NULL COMMENT '搜索关键词或货号';
