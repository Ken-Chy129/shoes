# 新增一个 StockX 账号到自动续期（Onboarding 流程）

把一个新的 StockX 卖家账号纳入"发 token 机"自动续期。核心思路：在本机（有界面、住宅 IP）
用浏览器登录该账号 → 导出它的 Auth0 会话 cookie → 注入到服务器的浏览器 profile →
服务器从此自动每 8h 静默签发新 token 写回后端。

> 名词：**后端账号名** = 后端"StockX 账号管理"里的名字（如 `美区`/`港大`/`day 7`）。
> **Chrome profile** = 你本机 Chrome 里登录该账号的那个 profile。两者可以不同名，靠 **customer_uuid 核验**对齐。

---

## 前提

- 本机：macOS + Chrome，且**已在某个 Chrome profile 里登录了目标 StockX 卖家账号**。
- 服务器：`43.99.84.114`，发 token 机在 `/opt/stockx-token-minter/`，后端 API 在 `127.0.0.1:8081`。
- 该账号已存在于后端"StockX 账号管理"（没有的话先在后端页面"添加账号"，费率等配好，authorization 可先留空）。

---

## 步骤

### 1. 本机：导出该账号的会话 cookie

```bash
cd stockx-token-minter
# 参数1 = Chrome profile 的显示名；参数2 = 输出文件
python3 tools/extract-chrome-cookies.py "<Chrome profile名>" /tmp/seed_新账号.json
```
- 会弹一次钥匙串授权框 → 点**允许**。
- 不知道 profile 名？看 `~/Library/Application Support/Google/Chrome/Local State` 里的 `profile.info_cache`，
  或脚本报错时会列出所有 profile 名。
- Chrome 可以开着（脚本读的是 Cookies 库副本）。

### 2. 传到服务器

```bash
scp /tmp/seed_新账号.json root@43.99.84.114:/opt/stockx-token-minter/
```

### 3. 服务器：把账号加进发 token 机配置

编辑 `/opt/stockx-token-minter/config.json`，在 `accounts` 数组加一条
（**`name` 必须和后端账号名一字不差**，`profileDir` 取个新目录）：
```json
{ "name": "<后端账号名>", "profileDir": "./profiles/<英文目录名>", "enabled": true }
```

### 4. 服务器：注入登录态并试签

```bash
cd /opt/stockx-token-minter
node seed.js "<后端账号名>" ./seed_新账号.json
```
输出会打印 `customer_uuid` 和到期时间。

### 5. ⚠️ 核验 customer_uuid（关键，别跳过）

确认上一步打印的 `customer_uuid` 确实是该后端账号对应的 StockX 账号。
对照办法：看后端该账号**原有 token** 的 uuid（若有），或凭你登录时的账号确认。
> 真实踩过的坑：一个名为 "day7" 的 Chrome profile 实际登录的是后端「day 小」账号，
> 靠 uuid 核验才发现，否则会把 token 写错账号、操作到别人的库存。

### 6. 服务器：推送 + 标记自动刷新

```bash
node index.js --once
```
该账号会签发新 token、写回后端、并把后端的 `autoRefresh` 置 true。

### 7. 清理本机明文 cookie（含登录凭据）

```bash
rm -f /tmp/seed_新账号.json
```

### 8. 验证

- 前端"StockX 账号管理"：该账号「自动刷新」列应显示绿色 🟢 **自动**，「Token到期」显示剩余时长。
- 或服务器 `curl -s http://127.0.0.1:8081/setting/stockx/accounts`，看该账号 `autoRefresh:true`。

之后 systemd timer 每 8h 自动续期，无需再管。

---

## 失效与重登（约每 3.5 个月）

Auth0 会话 cookie 约 2026-09-30 过期，届时该账号 `index.js` 会报 `authorize_timeout` / 签发失败。
重新走一遍上面 1→6（重新登录该 Chrome profile → 重新导出 → 重新 seed）即可。

## 注意

- **运行目录是 `/opt/stockx-token-minter/`，与 git 仓库的 `stockx-token-minter/` 是两份拷贝。**
  改发 token 机代码要 `scp` 到运行目录，`git pull` 不会更新它。
- 同一 StockX 账号的会话**别在本机和服务器同时高频使用**，可能被 StockX 异地并发踢下线。
- `profiles/` 和 `seed_*.json` 含登录凭据，注意权限、勿入库（已在 .gitignore）。
