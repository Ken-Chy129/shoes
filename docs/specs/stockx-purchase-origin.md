# StockX 寄存／销售任务的购买来源

## 目标与契约

按用户确认的需求，为 `fetch_orders` 和 `fetch_listings`（`inventoryType=CUSTODIAL`）的明细、API 和 Excel 同时补充：

- `purchaseOrderNumber`：原购买订单号，可空。来自 `associatedOrders.flexIntakeOrder.sources` 中 `type=BUY_INTO_FLEX` 的 `id`。
- `purchasePrice`：当时购买价格，DECIMAL(12,2)，可空。来自相同账号购买历史节点的 `amount`。
- `purchaseCurrencyCode`：购买价格的币种，可空。来自购买节点的 `currencyCode`，不复用销售币种。

保留 `orderNumber`（销售订单号）、`salePrice`（出售价格）、`currentPrice`（当前标价）的既有含义。
购买价格与 StockX 购买历史页面一致，不声称是包含全部税费的最终成本。

## 实现与边界

1. 先补来源解析、账号内价格匹配及测试，再接入任务和持久化，最后接入展示和导出。
2. 每次任务遇到首个购买来源时，分页读取该账号购买历史一次，按 `orderId` 建立价格索引；缺少 `orderId` 才回退 `orderNumber`。不用 `chainId`、货号或尺码匹配。
3. 索引限于当前任务，不跨账号、不跨任务缓存；无来源的普通记录不请求购买历史。
4. 来源存在但历史中没有匹配记录，保留原购买订单号，价格为空；缺少来源的商品两列为空。缺失价格不得填 0 或用当前售价代替。
5. 购买查询失败、未授权、分页结构不完整或重复游标时使任务明确失败，不伪装成“没有购买记录”；沿用任务的限流与取消处理。
6. 不修改买卖订单，不自动回填旧任务；重新运行获取任务才采集新字段。
7. 在现有 Ant Design 表格增加两列，不改变其他任务的列和导出格式。不增加依赖。

## 文件与风格

- `task/StockXPurchaseOrigin`：纯来源解析与任务范围内的历史索引，价格用 `BigDecimal`，构造器传入只读查询及取消检查。
- `TaskItemDO`、MyBatis 映射及 `resources/script`：附加 nullable 字段。
- 两个任务 runner、`StockXClient`：提取、匹配、入库。
- `TaskItemModal`、Excel DTO 与控制器：两处统一展示。
- `src/test/java`：来源、分页、隔离、异常、runner 集成和真实 Excel 往返测试。

## 验证命令

- 后端：`./build.sh -Dtest='StockX*Test,TaskItemControllerStockXExportTest' test`
- 全部离线测试：`./build.sh -Dtest='*Test' test`（不运行依赖外部数据库的 `ShoesApplicationTests`）
- 前端：在 `console` 执行 `npm test -- --runInBand`、`npm run tsc`、`npm run build`。
- 浏览器：在本地用固定接口样本验证两个明细弹窗的两列、货币、空值及横向滚动。

## 发布与回滚

上线前先执行 `20260914_stockx_purchase_origin.sql`，再发布后端与前端。新数据库按 `shoes.sql` 初始化。
回滚应用时保留新增 nullable 列，不删除采集数据；如必须撤销 schema，先备份三列再删除。本次开发不执行生产迁移或部署。

## 验证记录（2026-09-14）

- 来源解析、任务集成、客户端转换、Excel 往返及全量离线测试通过；不运行依赖外部数据库的应用上下文测试。
- 前端 14 项 Jest 测试、TypeScript 检查和生产构建通过。
- 使用本机 Chrome 和隔离的本地接口样本验证销售、寄存和普通现货弹窗：订单号、USD/HKD 金额、空值、320/768/1024/1440 宽度横向滚动通过，控制台无错误。未使用生产登录态。
- 保留现有依赖；构建仅有既有 Browserslist 数据过期及 Mockito 动态代理提示。
