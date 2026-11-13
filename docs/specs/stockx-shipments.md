# StockX 获取货运信息（已实现）

## 目标与确认口径

新增独立的“获取货运信息”任务，沿用卖家侧“获取订单”的账号选择、
多类型选择、任务进度、取消、重跑、明细查看及 Excel 导出方式。
类型仅包含 `packed`（已打包）、`shipped`（已发货）、`received`（已收到）。
本功能只查询，不打包、发货、修改订单或打印标签。

用户已确认开发方案：按每件商品一行保存明细，附带所属货运号、追踪号码；
同一包裹的多个商品会有相同货运号。截图中的包裹分组卡片不在此方案内。

## 接口

`POST /api/task/stockx/startFetchShipments`

```json
{
  "accountId": "已有账号名称",
  "shipmentTypes": ["packed", "shipped", "received"]
}
```

返回现有 `Result<String>` 包装的任务 ID。账号、类型数组必须非空；
未知类型拒绝，重复类型去重。任务类型为 `fetch_shipments`。
原 `startFetchOrders` 接口及其四种订单类型保持兼容。

## 上游契约调查（2026-11-13）

从 StockX Pro 公开构建资源读取了如下定义，尚未用登录账号验证真实响应：

- `https://pro.stockx.com/assets/standardShipmentsVariables-Cq0gJJaz.js`
- `https://pro.stockx.com/assets/standardPacked-DtqSfocd.js`
- `https://pro.stockx.com/assets/standardShipped-CK9oYpC7.js`
- `https://pro.stockx.com/assets/standardReceived-Dl0XdaWJ.js`
- `https://pro.stockx.com/assets/sellerListings-B6Xea-gv.js`
- `https://pro.stockx.com/assets/sellerListingAttributes-QSZb-iqM.js`

列表视图使用 `SellerListings` / `viewer.sellerListings` 查询。
三种类型都限制 `inventoryType.in = ["STANDARD"]`、
`optionalVerification.is = true`，按 `MATCHED_AT DESC` 排序。

| 类型 | listingStatus.in | orderStatus.in |
| --- | --- | --- |
| 已打包 | READY_TO_SHIP | CREATED |
| 已发货 | MATCHED、READY_TO_SHIP、IN_TRANSIT_TO_WAREHOUSE | SHIPPED |
| 已收到 | MATCHED、READY_TO_SHIP | RECEIVED、AUTHENTICATING、AUTHENTICATED、AUTHFAILED |

货运号来自 `shipment.displayId`，追踪号码来自 `shipment.trackingNumber`；
已删除的 shipment 不展示物流信息。发货截止时间来自
`associatedOrders.standardizedSellOrder.shipByDate`。
商品、尺码、售价、售出时间、销售订单号复用订单转换逻辑。
不要把 `shipment.deliveryDate` 未经核实标为“收到时间”。

## 实现范围与顺序

1. 定义类型与请求测试，复用 StockX 客户端的请求、账号鉴权、限流机制。
2. 接入后台任务、分页、错误处理、取消与历史重跑；不查询得物价或购买来源。
3. 增加货运明细及专用导出，接入控制台任务选项和三种类型的多选框。
4. 验证旧订单路径不变，再提交并推送 master；生产部署另行确认。

需覆盖的调用点：

- 后端 `client/StockXClient`、`controller/TaskController`、
  `manager/TaskExecutorManager`、任务类型、任务执行器及 `service/TaskService`。
- 明细 `model/entity/TaskItemDO`、`controller/TaskItemController` 和 Excel 模型。
- 前端 `services/task.ts`、`pages/task/history/`、`pages/task/components/TaskItemModal.tsx`。
- 对应客户端请求、状态转换、任务分页/取消/重跑、导出及前端测试。

存储复用现有 `trackingNumber`，新增可空 `shipment_id` 列保存展示货运号，
不挪用现有 `listingId`、`sku` 或订单号字段。
部署前须执行 `src/main/resources/script/20261113_stockx_shipments.sql`；
应用回滚时保留该列，不删除已抓取的货运号。本次开发未执行生产迁移。
货运任务与订单任务独立互斥、取消；重跑沿用原账号和货运类型，
进程重启后不自动恢复。查询按类型顺序执行，不保证跨类型的同一时刻快照。

## 技术与代码约定

Java 21 / Spring Boot / MyBatis-Plus / EasyExcel；
React / TypeScript / Ant Design。使用现有请求响应、任务和表单模式，
例如 `Result.buildSuccess(String.valueOf(taskId))`，不引入新依赖。
规格位于 `docs/specs/`，Java 测试位于 `src/test/java/`，
前端测试与现有任务页面测试同目录。

## 验证与边界

验证命令（Maven 使用 Java 21）：

```sh
mvn test
mvn -DskipTests package
npm --prefix console test -- --runInBand
npm --prefix console run tsc
npm --prefix console run build
```

已按先测试再实现覆盖：三种筛选参数、多类型去重、非法类型、
分页和空结果、鉴权失效、上游错误、取消、重跑以及货运字段导出。
后端 759 项测试、前端 22 项测试、TypeScript 检查及前后端构建通过。
本地浏览器使用模拟接口验证了任务入口、三类型多选、创建请求、
参数翻译、逐商品明细和导出入口；Excel 内容由后端测试验证。
本地静态服务的既有 `/scripts/loading.js` 缺失导致控制台报错，
未影响上述功能；未修改该无关资源配置。
尚未使用真实 StockX 账号联调，也未执行生产部署。

始终验证输入和上游响应；保留异常处理，不将错误响应视为空结果成功。
分页缺失或不前进必须失败；日志和测试不得包含真实凭证。
不扩展到待处理、Flex、物流操作或自动调度。
不修改其他业务任务，不执行生产迁移或重启。
