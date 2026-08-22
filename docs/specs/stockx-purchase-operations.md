# StockX 购买任务三操作规格

## 目标

新增任务类型 `purchase`（购买），提供三个互斥的只读操作：

1. `bids`（获取出价）：获取当前有效出价。
2. `orders`（获取订单）：获取进行中的购买订单。
3. `history`（获取历史记录）：获取历史购买记录。

任务按 StockX 账号创建，一次任务只执行一个操作。操作保存在任务参数 `operation` 中，支持历史任务重跑。

## StockX 请求契约

| 操作 | GraphQL operationName | state | 排序 | 分页 |
| --- | --- | --- | --- | --- |
| `bids` | `Bids` | `CURRENT` | `UPDATED_AT DESC` | 游标，每页 50 条 |
| `orders` | `Buying` | `PENDING` | `MATCHED_AT DESC` | 游标，每页 50 条 |
| `history` | `Buying` | `HISTORICAL` | `MATCHED_AT DESC` | 游标，每页 50 条 |

请求结构与 persisted-query hash 来自 2026-08-22 的 StockX Pro 实际页面请求。第三方响应必须在任务边界检查 `edges` 和 `pageInfo`；分页声明还有下一页但未返回新游标时，任务应失败，避免无限循环。

## 任务明细映射

三个操作复用 `task_item`：

- 通用商品字段：标题、货号、US 尺码、EU 尺码、商品/变体标识。
- 出价：出价 ID、金额、币种、状态、创建时间。
- 订单与历史：chain ID、订单号、购买价格、币种、订单状态、购买时间。

不新增写操作；本任务不会创建、修改或取消 StockX 出价和订单。

## 兼容性

- 现有 `fetch_orders` 是卖家侧订单任务，继续保留，不更名、不改变行为。
- `purchase` 是买家侧独立任务类型，与 `fetch_orders` 的历史记录和明细展示互不混淆。
