# 得物价格查询 API

## 基础信息

| 项目 | 说明 |
|---|---|
| 地址 | `http://43.99.84.114:8081/poison` |
| Content-Type | `application/json` |
| 鉴权 | Header 携带 `api-token` |
| 价格单位 | 元（人民币） |
| 尺码格式 | EU 欧码（如 `38`、`39⅓`、`42⅔`） |
| 限流 | 3 QPS |

## 鉴权

所有接口需在 Header 中携带 `api-token`：

```
api-token: <向管理员获取>
```

鉴权失败返回：

```json
{ "success": false, "errorMsg": "无效的api-token" }
```

---

## 1. 单个查价

`GET /poison/price?modelNo={货号}`

### 请求示例

```bash
curl -H 'api-token: <your_token>' \
  'http://43.99.84.114:8081/poison/price?modelNo=JS4442'
```

### 成功响应

```json
{
  "success": true,
  "data": [
    { "modelNo": "JS4442", "euSize": "38", "price": 257, "updateTime": "2026-05-17T00:00:00.000+08:00" },
    { "modelNo": "JS4442", "euSize": "41⅓", "price": 198, "updateTime": "2026-05-17T00:00:00.000+08:00" },
    { "modelNo": "JS4442", "euSize": "42", "price": 221, "updateTime": "2026-05-17T00:00:00.000+08:00" }
  ]
}
```

### 查无结果

```json
{ "success": false, "errorMsg": "未找到该货号的价格信息" }
```

---

## 2. 批量查价

`POST /poison/batchPrice`

### 请求示例

```bash
curl -X POST -H 'api-token: <your_token>' \
  -H 'Content-Type: application/json' \
  -d '["JS4442", "DZ5485-612"]' \
  'http://43.99.84.114:8081/poison/batchPrice'
```

### 成功响应

```json
{
  "success": true,
  "data": {
    "JS4442": [
      { "modelNo": "JS4442", "euSize": "38", "price": 257, "updateTime": "..." },
      { "modelNo": "JS4442", "euSize": "42", "price": 221, "updateTime": "..." }
    ],
    "DZ5485-612": [
      { "modelNo": "DZ5485-612", "euSize": "40", "price": 489, "updateTime": "..." }
    ]
  }
}
```

### 说明

- 单次建议不超过 **200** 个货号
- 返回结果只包含查到价格的货号，查无价格的不返回
- 大量未缓存货号时响应较慢（约 N/3 秒）

---

## 响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `modelNo` | String | 货号 |
| `euSize` | String | EU 欧码 |
| `price` | Integer | 最低出价（元） |
| `updateTime` | Date | 价格获取时间 |
