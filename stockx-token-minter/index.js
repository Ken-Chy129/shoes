'use strict';

// 发 token 机守护进程：
//   - 每个账号用各自的持久化浏览器上下文（保活登录态）
//   - 启动即跑一轮；之后每 refreshIntervalMs（默认 11h）跑一轮
//   - 每轮：打开 pro.stockx.com → 静默签发新 12h token → 写回后端 stockx-accounts.json
//
// 用法：
//   node index.js          # 守护进程，循环刷新
//   node index.js --once   # 只跑一轮（便于测试 / 配合外部 cron）

const fs = require('fs');
const path = require('path');
const { openContext } = require('./src/browser');
const { isLoggedIn, mintFreshToken, LISTINGS_URL } = require('./src/mint');
const { pushTokenToBackend } = require('./src/backend');

function loadConfig() {
  const p = path.resolve(__dirname, 'config.json');
  if (!fs.existsSync(p)) {
    console.error('缺少 config.json，请先 cp config.example.json config.json 并填写。');
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function log(...a) {
  console.log(`[${new Date().toISOString()}]`, ...a);
}

async function refreshOne(account, cfg) {
  let context;
  try {
    context = await openContext({
      profileDir: account.profileDir,
      headless: cfg.headless,
      useRealChrome: cfg.useRealChrome,
    });

    if (!(await isLoggedIn(context))) {
      throw new Error(`未登录或登录态已失效，请先执行: node login.js "${account.name}"`);
    }

    const page = context.pages()[0] || (await context.newPage());
    await page.goto(LISTINGS_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    // 等 Cloudflare 放行 cookie(__cf_bm 等)就绪，否则静默授权偶发 403
    await page.waitForTimeout(3000);

    let token;
    try {
      token = await mintFreshToken(page);
    } catch (e) {
      log(`[${account.name}] 首次签发失败(${e.message.split('\n')[0]})，5s 后重试`);
      await page.waitForTimeout(5000);
      token = await mintFreshToken(page);
    }
    log(`[${account.name}] 签发成功 azp=${token.azp.slice(0, 8)}… 到期=${token.expiresAt} scope=${token.scope}`);

    await pushTokenToBackend({
      baseUrl: cfg.backendBaseUrl,
      apiToken: cfg.apiToken,
      accountName: account.name,
      bearer: token.bearer,
    });
    log(`[${account.name}] ✅ 已写回后端`);
    return true;
  } catch (e) {
    log(`[${account.name}] ❌ 失败: ${e.message}`);
    return false;
  } finally {
    if (context) await context.close().catch(() => {});
  }
}

async function refreshAll(cfg) {
  const accounts = cfg.accounts.filter((a) => a.enabled !== false);
  log(`开始刷新 ${accounts.length} 个账号 token……`);
  let ok = 0;
  for (const a of accounts) {
    if (await refreshOne(a, cfg)) ok++;
  }
  log(`本轮完成: ${ok}/${accounts.length} 成功`);
}

(async () => {
  const cfg = loadConfig();
  const once = process.argv.includes('--once');

  await refreshAll(cfg);

  if (once) {
    process.exit(0);
  }

  const interval = cfg.refreshIntervalMs || 39600000; // 默认 11h
  log(`守护进程已启动，每 ${Math.round(interval / 3600000)}h 刷新一次。`);
  setInterval(() => refreshAll(cfg).catch((e) => log('刷新轮异常:', e.message)), interval);
})();
