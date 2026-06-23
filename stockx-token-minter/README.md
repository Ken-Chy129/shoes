# StockX 发 token 机（无头浏览器）

自动续期 StockX **graphql 链路**用的 bearer token，把"每 12 小时手动粘一次"降到"约每 3.5 个月重新登录一次"。

## 原理

StockX 的 graphql 网关（`gateway.stockx.com`）只认其**网页端 SPA**（Auth0 client `v1Ytb8BK…`）签发的 token，
而该 token 的签发端点 `accounts.stockx.com` 挡在 **Cloudflare JS 挑战**后面——纯 curl/服务端拿不到（实测 403）。
唯一能过的是**真实浏览器**。所以本工具用一个**持久化登录态的浏览器**，定时跑一遍 Auth0 静默授权
（`prompt=none` + PKCE + 隐藏 iframe），无 UI、无密码地签出新的 12h token，再写回后端 `stockx-accounts.json`。

```
[持久化浏览器 · 登录一次保活 auth0 会话(~3.5个月)]
      │ 每 ~11h：静默 authorize → /oauth/token → 新 12h token
      ▼
  PUT /setting/stockx/accounts/{name}  （只改 authorization 字段）
      ▼
[后端不变] 用 token 经现有代理打 gateway.stockx.com graphql
```

## 安装

```bash
cd stockx-token-minter
npm install
npx playwright install chrome   # 或 chromium
cp config.example.json config.json
# 编辑 config.json：backendBaseUrl、accounts[].name 要与后端账号名完全一致
```

## 使用

```bash
# ① 一次性登录（有头浏览器，手动登录每个 StockX 卖家账号，含 2FA）
node login.js "美区"
node login.js "港大"

# ② 测试单轮（不常驻）
node index.js --once

# ③ 常驻守护（每 11h 自动刷新）
node index.js
# 生产建议用 pm2 / systemd 守护：pm2 start index.js --name sx-token-minter
```

登录态失效（约 3.5 个月后，或被 StockX 强制登出）时，`index.js` 会报
`未登录或登录态已失效`，重新跑一次 `node login.js "<账号>"` 即可。

## config.json 字段

| 字段 | 说明 |
|---|---|
| `backendBaseUrl` | 后端地址。跑在服务器上用 `http://127.0.0.1:8080` |
| `apiToken` | 预留：若后端给 setting 接口加了 `api-token` 鉴权则填（当前接口无鉴权，可留空） |
| `refreshIntervalMs` | 刷新间隔，默认 39600000（11h，留 1h 安全边际） |
| `headless` | 日常 true；首次 login.js 会强制有头 |
| `useRealChrome` | true 用本机真实 Chrome（channel:chrome），比 Chromium 更不易被判机器人 |
| `accounts[].name` | **必须和后端 stockx-accounts.json 里的账号名完全一致** |
| `accounts[].profileDir` | 该账号的浏览器持久化目录（含登录 cookie，已 gitignore） |

## ⚠️ 部署位置的权衡（重要）

- **跑在 HK 服务器**：后端走 `127.0.0.1:8080` 最方便；但数据中心 IP 更容易被 Cloudflare/PerimeterX
  判为机器人，登录和静默授权可能被加强挑战（建议 `useRealChrome:true` + 必要时 xvfb 有头）。
- **跑在本机/住宅 IP**：过风控最稳，但需让后端可达（暴露端口或 SSH 隧道），且机器要常开。
- **关键自洽要求**：**同一账号的"登录"和"签发"必须在同一个 profile/同一出口 IP 上**完成，
  否则 `cf_clearance`（绑 IP）失配会触发 Cloudflare 重新挑战。

## ⚠️ 小内存服务器防护（HK 生产 3.5G 已配置）

Chrome 内存尖峰曾在 3.5G 机器上 OOM 掉 MySQL，拖垮后端。已加三道防护：
1. **发 token 机 systemd 服务限内存**：`/etc/systemd/system/sx-token-minter.service.d/limits.conf`
   设 `MemoryHigh=1000M`、`MemoryMax=1300M`——超限只杀它自己的 Chrome，不殃及 MySQL/后端。
2. **MySQL 容器加重启策略**：`docker update --restart unless-stopped mysql8`（原为 no，被杀后不自愈）。
3. **Chrome 省内存 flags**：`--disable-dev-shm-usage --disable-gpu --no-sandbox` 等（见 src/browser.js）。

> 注意：生产运行目录是 `/opt/stockx-token-minter/`，与 git 仓库内的 `stockx-token-minter/` 是两份拷贝；
> 改动需 scp 同步到运行目录（systemd 服务从该目录跑），git pull 不会更新它。

## ⚠️ 风险

- 属于模拟登录的灰色地带；StockX 若改前端授权流程或加强风控，需相应调整。
- `profiles/` 内含登录态 cookie，等同账号凭据，**严禁入库、注意服务器文件权限**。
