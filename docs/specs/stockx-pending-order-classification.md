# StockX 待处理订单补单标识

## 目标与口径

仅 `fetch_orders` 的 `PENDING` 订单查询买家视角市场成交历史；其他订单类型、货运、补单任务不查询。
以订单的 variant ID 定位同货号同尺码，不用展示尺码做模糊匹配。
新增可空布尔字段 `replenishmentOrder`：`false` 为正常单，`true` 为补单，`null` 为未判定/不适用。

用户要求按成交时间与价格匹配，未指定容差，本次采用严格的“同一 UTC 秒”（忽略毫秒）、
同币种、金额数值相等（200 与 200.00 相等）。不自动做汇率或费用换算，不扩大到同一天。
存在匹配即为正常单；只有成功查完目标时间范围且没有匹配才标为补单。
接口失败、缺字段、分页不完整、币种/variant 不符、缺少订单时间/价格/币种时保留未判定。
这属于按公开成交记录推断，不是 StockX 官方补单标记，公开记录缺失或延迟仍可能导致误判。

## 实现顺序与范围

1. 复用 `StockXClient` 的 BUYER `VariantSales` 请求，增加按币种读取单页的入口。
   原有购买指导的 90 天美元查询保留原行为。新路径校验响应 variant、币种与分页。
2. 在 `StockXPendingOrderClassifier` 中按 variant/币种缓存成交页和匹配键。
   校验成交记录按时间倒序；读到早于订单所在秒或总记录已读完才确认无匹配。
   同秒跨页必须继续读取；查询失败不变成补单，任务取消须继续向外传播。
   复用任务现有空响应/限流重试；每页检查取消。
3. 待处理订单入库前设置标识。扩展 `TaskItemDO`、mapper、建表及迁移 SQL；
   页面与订单 Excel 新增“是否补单”：补单/正常单/未判定，其他类型为 `-`。
   不改写延期状态，不修改已存在的任务记录。

## 项目约定

沿用 Java 21/Spring/MyBatis、React/Ant Design；复用现有 HTTP、分页重试与 Excel 导出方式。
金额使用 `BigDecimal`，时间使用 `Instant`，不引入依赖。
前后端空值展示必须区分 `false` 和 `null`，不可用真假判断把正常单当成缺失值。

## 测试与命令

- JUnit：请求币种/BUYER、精确匹配、同秒跨页、不同价/时间、空历史、历史覆盖、
  异常及错误分页、缓存隔离与复用、取消、仅 PENDING 查询、JSON 与 Excel 输出。
- Jest：页面字段、三态显示与非待处理适用范围。
- 浏览器：本地模拟数据验证三态展示，不访问真实订单 API。
- 后端：`JAVA_HOME=/Users/bytedance/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home mvn -Dtest=StockXPendingOrderClassifierTest,StockXPendingOrderClassificationTaskTest,StockXClientVariantSalesPageTest,StockXClientVariantSalesRequestTest,StockXFetchOrdersTaskRunnerTest,StockXFetchShipmentsTaskRunnerTest,TaskItemControllerPendingClassificationTest package`
- 前端：`cd console && node_modules/.bin/jest --runInBand --config '{}' && npm run build`

## 发布边界

开发、测试、提交并推送 master；本次不部署、不创建真实任务、不调用真实市场接口。
部署前执行 `src/main/resources/script/20261113_stockx_pending_order_classification.sql`，
再启动新版本；回滚应用时保留新增列。旧数据维持 NULL，需重新获取待处理订单才能判定。
没有自动回填、人工修正入口、可配置时间窗口或额外筛选功能。

## 本地验证（2026-11-13）

- 后端上述测试加订单转换、购买来源、订单 Excel 回归共 51 项通过，`mvn package` 成功。
- 前端全部 29 项 Jest 测试通过，生产构建成功。
- 浏览器加载真实 `TaskItemModal` 与模拟接口：三种待处理状态分别显示正常单、补单、未判定，
  历史订单显示 `-`；控制台无 error/warn。未调用真实订单或市场接口。
- 未执行数据库迁移、生产部署或历史数据回填；公开成交记录延迟/缺失及严格同秒匹配仍有误判风险。
