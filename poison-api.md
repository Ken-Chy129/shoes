# 分销开放 API 文档（Markdown 梳理版）

> 来源：`分销开放API文档（API Integration Document for B2B ）20260210.pdf`  
> 日期：2026-02-10  
> 说明：本文档为 PDF 的 Markdown 梳理版，重点整理调用规范、响应码、接口目录与各接口用途，便于研发接入、评审和后续维护。

## 1. 基础调用规范

| 项目 | 说明 |
|---|---|
| 生产环境地址 | `https://distopen.poizon.com/open/api/v1` |
| 鉴权方式 | 每个请求必须在 Header 中携带 `access-token` |
| Content-Type | `application/json` |
| 接口限流 | 每个接口限制 `3 QPS` |
| 时间格式 | 原文涉及时间字段时，通常为 UTC 时间戳，单位毫秒 |

### Header 示例

```http
access-token: <your_access_token>
Content-Type: application/json
```

## 2. 响应码说明

| Code | 描述 | 含义 |
|---:|---|---|
| 200 | success | 请求成功 |
| 30300013 | No permission | 无权限 |
| 30400001 | Invalid access token | access token 无效 |
| 30400002 | access token expired | access token 已过期 |
| 30300014 | The request is too frequent | 请求过于频繁，触发限流 |
| 40900006 | System exception | 系统异常 |
| 30300012 | Param exception | 参数异常 |

## 3. 接口目录

| 模块 | 接口 | Method | Path | 用途 |
|---|---|---|---|---|
| 商品 | 查询 SPU 列表 | `POST` | `/distribute/product/querySpuList` | 按商品 SPU 条件分页查询商品信息，可选择返回 SKU 信息 |
| 商品 | 查询 SKU 列表 | `POST` | `/distribute/product/querySkuList` | 按 SKU、SPU、货号、修改时间等条件分页查询 SKU |
| 商品 | 查询 SKU 详情 | `POST` | `/distribute/product/querySkuInfo` | 查询指定 SKU 的详细信息 |
| 订单 | 查询订单信息 | `POST` | `/distribute/order/queryOrderList` | 按订单号、状态、时间等条件查询订单列表 |
| 订单 | 加入购物车（供销平台） | `POST` | `/distribute/order/addSiteOrderCart` | 将指定商品加入供销平台购物车 |
| 订单 | 创建订单 | `POST` | `/distribute/order/createOrder` | 创建订单 |
| 订单 | 取消订单 | `POST` | `/distribute/order/cancelOrderApply` | 对指定订单发起取消申请 |
| 履约 | 查询物流轨迹信息 | `POST` | `/distribute/order/queryLogisticTrace` | 查询订单物流轨迹 |
| 预付款 | 查询预付款余额 | `GET` | `/distribute/prepayment/queryAmount` | 查询当前账户预付款余额 |

---

# 4. 商品接口

## 4.1 查询 SPU 列表

**接口**：`POST /distribute/product/querySpuList`

### 用途

分页查询 SPU 维度商品列表，支持按 SPU ID、商品名称、货号、品牌、类目、人群、季节、状态、修改时间等条件筛选。

### 常用请求参数

| 参数 | 类型 | 是否必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `startId` | Long | 否 | `0` | 分页起始 ID |
| `pageSize` | Int | 否 | `100` | 每页大小，范围 `1-200` |
| `dwSpuId` | List<Long> | 否 | - | SPU ID，最多 200 个 |
| `dwSpuTitle` | String | 否 | - | 中文商品名称 |
| `distSpuTitle` | String | 否 | - | 英文商品名称 |
| `dwDesignerId` | List<String> | 否 | - | 商品货号，最多 200 个 |
| `distBrandName` | List<String> | 否 | - | 品牌名称，最多 200 个 |
| `distCategoryl1Name` | List<String> | 否 | - | 一级类目名称 |
| `distCategoryl2Name` | List<String> | 否 | - | 二级类目名称 |
| `distCategoryl3Name` | List<String> | 否 | - | 三级类目名称 |
| `distFitPeopleName` | List<String> | 否 | - | 适用人群名称 |
| `season` | String | 否 | - | 季节 |
| `distStatus` | String | 否 | - | 商品状态：`PRODUCT_ON` 上架，`PRODUCT_OFF` 下架 |
| `modifyStartTime` | Long | 否 | - | 修改起始时间，UTC 毫秒时间戳 |
| `modifyEndTime` | Long | 否 | - | 修改结束时间，UTC 毫秒时间戳 |
| `querySku` | Boolean | 否 | `true` | 是否返回 SKU 信息 |

### 请求示例

```bash
curl --location --request POST \
  'https://distopen.poizon.com/open/api/v1/distribute/product/querySpuList' \
  --header 'access-token: <your_access_token>' \
  --header 'Content-Type: application/json' \
  --data-raw '{
    "startId": 0,
    "pageSize": 100
  }'
```

### 响应核心字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | Int | 结果码 |
| `msg` | String | 结果描述 |
| `data.total` | Long | 商品总数量 |
| `data.spuList` | List<Object> | SPU 列表 |
| `spuList[].dwSpuId` | Long | SPU ID |
| `spuList[].distStatus` | String | 商品状态 |
| `spuList[].dwSpuTitle` | String | 中文商品名称 |
| `spuList[].distSpuTitle` | String | 英文商品名称 |
| `spuList[].distBrandName` | String | 品牌名称 |
| `spuList[].skuList` | List<Object> | SKU 列表 |

## 4.2 查询 SKU 列表

**接口**：`POST /distribute/product/querySkuList`

### 用途

分页查询 SKU 维度商品列表，适用于库存、价格、状态等 SKU 级信息同步。

### 常用请求参数

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `startId` | Long | 否 | 分页起始 ID |
| `pageSize` | Int | 否 | 每页大小，通常最大 200 |
| `dwSkuId` | List<Long> | 否 | SKU ID |
| `dwSpuId` | List<Long> | 否 | SPU ID |
| `dwDesignerId` | List<String> | 否 | 商品货号 |
| `distBrandName` | List<String> | 否 | 品牌名称 |
| `distStatus` | String | 否 | SKU 状态：`PRODUCT_ON` / `PRODUCT_OFF` |
| `modifyStartTime` | Long | 否 | 修改起始时间，UTC 毫秒时间戳 |
| `modifyEndTime` | Long | 否 | 修改结束时间，UTC 毫秒时间戳 |

### 响应核心字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `data.total` | Long | SKU 总数量 |
| `data.skuList` | List<Object> | SKU 列表 |
| `skuList[].dwSkuId` | Long | SKU ID |
| `skuList[].dwSpuId` | Long | SPU ID |
| `skuList[].distStatus` | String | SKU 状态 |
| `skuList[].price` | Long | 价格相关字段，具体以原接口返回为准 |
| `skuList[].stock` | Int | 库存相关字段，具体以原接口返回为准 |

## 4.3 查询 SKU 详情

**接口**：`POST /distribute/product/querySkuInfo`

### 用途

查询单个或指定 SKU 的详细信息，用于商品详情页、库存价格校验、下单前校验等场景。

### 关键字段

| 类型 | 字段示例 | 说明 |
|---|---|---|
| 请求 | `dwSkuId` | SKU ID |
| 请求 | `dwSpuId` | SPU ID |
| 响应 | `skuInfo` | SKU 详情信息 |
| 响应 | `distStatus` | SKU 上下架状态 |

---

# 5. 订单接口

## 5.1 查询订单信息

**接口**：`POST /distribute/order/queryOrderList`

### 用途

按订单维度查询订单列表及订单详情，适合订单同步、售后状态同步、对账等场景。

### 常用请求参数

| 参数 | 类型 | 是否必填 | 说明 |
|---|---|---|---|
| `startId` | Long | 否 | 分页起始 ID |
| `pageSize` | Int | 否 | 每页大小 |
| `orderNo` | String / List | 否 | 订单号，具体类型以原接口为准 |
| `orderStatus` | String | 否 | 订单状态 |
| `createStartTime` | Long | 否 | 创建起始时间，UTC 毫秒时间戳 |
| `createEndTime` | Long | 否 | 创建结束时间，UTC 毫秒时间戳 |
| `modifyStartTime` | Long | 否 | 修改起始时间，UTC 毫秒时间戳 |
| `modifyEndTime` | Long | 否 | 修改结束时间，UTC 毫秒时间戳 |

## 5.2 加入购物车（供销平台）

**接口**：`POST /distribute/order/addSiteOrderCart`

### 用途

将指定商品 / SKU 加入供销平台购物车，是创建订单前的前置步骤之一。

### 关键字段

| 类型 | 字段示例 | 说明 |
|---|---|---|
| 请求 | `dwSkuId` | SKU ID |
| 请求 | `quantity` | 购买数量 |
| 响应 | `cartId` / 购物车信息 | 用于后续创建订单，具体以接口返回为准 |

## 5.3 创建订单

**接口**：`POST /distribute/order/createOrder`

### 用途

根据购物车信息、商品信息、收货信息等创建订单。

### 接入注意事项

- 创建订单前建议先校验 SKU 状态、价格、库存和收货地址。
- 若创建失败，应优先查看 `code` 和 `msg`。
- 不建议对创建订单接口做无退避的高频重试，避免重复下单或触发限流。

## 5.4 取消订单

**接口**：`POST /distribute/order/cancelOrderApply`

### 用途

对指定订单发起取消申请。

### 关键字段

| 类型 | 字段示例 | 说明 |
|---|---|---|
| 请求 | `orderNo` | 订单号 |
| 请求 | `cancelReason` | 取消原因，若接口要求则需传入 |
| 响应 | `status` / `msg` | 取消申请处理结果 |

## 5.5 返回字段说明

原 PDF 中包含订单相关返回字段说明。由于 PDF 表格存在跨页与断词，建议以接口实际返回 JSON 为准，并将以下字段作为重点关注对象：

| 字段类型 | 重点字段 |
|---|---|
| 订单基础信息 | 订单号、订单状态、创建时间、修改时间 |
| 商品信息 | SPU、SKU、商品名称、尺码、数量 |
| 金额信息 | 商品金额、运费、优惠、实付金额 |
| 收货信息 | 收件人、手机号、地址 |
| 履约信息 | 发货状态、物流单号、物流轨迹 |

---

# 6. 履约接口

## 6.1 查询物流轨迹信息

**接口**：`POST /distribute/order/queryLogisticTrace`

### 用途

查询订单对应的物流轨迹信息，适用于物流状态同步、售后查询、用户端物流展示等场景。

### 关键字段

| 类型 | 字段示例 | 说明 |
|---|---|---|
| 请求 | `orderNo` | 订单号 |
| 请求 | `trackingNo` | 物流单号，如接口支持 |
| 响应 | `logisticTrace` | 物流轨迹列表 |
| 响应 | `logisticCompany` | 物流公司 |
| 响应 | `trackingNo` | 物流单号 |

---

# 7. 预付款接口

## 7.1 查询预付款余额

**接口**：`GET /distribute/prepayment/queryAmount`

### 用途

查询当前账户预付款余额，用于下单前余额校验、财务对账、余额预警等场景。

### 响应核心字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | Int | 结果码 |
| `msg` | String | 结果描述 |
| `data` | Object | 预付款余额数据 |
| `amount` | Number | 余额金额，具体单位以接口返回为准 |

---

# 8. 接入建议

## 8.1 推荐接入流程

1. **鉴权准备**：获取正式 `access-token`。
2. **商品同步**：先调用 SPU / SKU 查询接口建立本地商品库。
3. **增量更新**：使用 `modifyStartTime`、`modifyEndTime` 做增量同步。
4. **下单前校验**：创建订单前校验 SKU 状态、价格、库存和预付款余额。
5. **订单创建**：加入购物车后创建订单。
6. **订单同步**：定时查询订单状态。
7. **履约同步**：查询物流轨迹并同步到内部系统。

## 8.2 异常处理建议

| 场景 | 建议处理方式 |
|---|---|
| Token 无效 / 过期 | 重新获取或刷新 token |
| 无权限 | 检查接口权限、账号权限和环境配置 |
| 参数异常 | 记录请求体和响应信息，按接口字段要求修正 |
| 请求频繁 | 降低请求频率，增加指数退避重试 |
| 系统异常 | 保留请求日志，稍后重试或联系接口方排查 |

## 8.3 日志建议

建议记录以下字段，便于排查问题：

- 请求时间
- 接口路径
- 请求参数摘要
- 响应 `code`
- 响应 `msg`
- 业务主键，如 `dwSpuId`、`dwSkuId`、`orderNo`
- 请求耗时
- 重试次数

---

# 9. 备注

- 原 PDF 为中英文双语文档，本文档以中文版为主进行梳理。
- 若需要逐字段 100% 还原，建议结合原 PDF 或接口返回 JSON Schema 二次校验。
- 文档中的示例 token 已统一替换为 `<your_access_token>`，避免误用示例凭证。
