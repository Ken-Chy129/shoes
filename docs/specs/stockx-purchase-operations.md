# StockX 购买任务四操作规格

## 目标

任务类型 `purchase`（购买）提供四个互斥操作：

1. `bids`（获取出价）：获取当前有效出价。
2. `orders`（获取订单）：获取进行中的购买订单。
3. `history`（获取历史记录）：获取历史购买记录。
4. `create_bids`（创建出价）：上传包含「货号、尺码、价格」的 Excel，创建有效期 365 天的 USD 出价。

任务按 StockX 账号创建，一次任务只执行一个操作。操作保存在任务参数 `operation` 中。创建出价任务保存 Excel 输入快照，历史重跑会先查询已有有效出价并跳过相同 variant，避免重复出价。

## StockX 请求契约

| 操作 | GraphQL operationName | state | 排序 | 分页 |
| --- | --- | --- | --- | --- |
| `bids` | `Bids` | `CURRENT` | `UPDATED_AT DESC` | 游标，每页 50 条 |
| `orders` | `Buying` | `PENDING` | `MATCHED_AT DESC` | 游标，每页 50 条 |
| `history` | `Buying` | `HISTORICAL` | `MATCHED_AT DESC` | 游标，每页 50 条 |

创建出价使用 `BulkCreateBids` mutation，每批最多 100 条。每条请求包含 `variantId`、`amount`、`currency=USD`、`expiresIn=365`、`deliveryOptionType=HOME_DELIVERY`、`context=BID` 和对应的 `localizedSizeType`。协议来自 2026-08-22 的 StockX Pro 实际页面代码和真实 $1 创建/删除验证。

请求结构与 persisted-query hash 来自 2026-08-22 的 StockX Pro 实际页面请求。第三方响应必须在任务边界检查 `edges` 和 `pageInfo`；分页声明还有下一页但未返回新游标时，任务应失败，避免无限循环。

## 任务明细映射

三个操作复用 `task_item`：

- 通用商品字段：标题、货号、US 尺码、EU 尺码、商品/变体标识。
- 出价：出价 ID、金额、币种、状态、创建时间。
- 订单与历史：chain ID、订单号、购买价格、币种、订单状态、购买时间。

前三个操作不会创建、修改或取消 StockX 出价和订单；只有 `create_bids` 会提交真实出价。Excel 价格必须为正整数美元，尺码支持 US M、US W 和 EU 前缀。

## 兼容性

- 现有 `fetch_orders` 是卖家侧订单任务，继续保留，不更名、不改变行为。
- `purchase` 是买家侧独立任务类型，与 `fetch_orders` 的历史记录和明细展示互不混淆。
