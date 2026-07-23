'use strict';

// 一次性交互登录：给指定账号打开有头浏览器，你手动登录 StockX 卖家账号，
// 登录态会保存进该账号的 profileDir，之后 index.js 即可无人值守签 token（约 3.5 个月内免再登录）。
//
// 用法：
//   node login.js "<账号名>"
//   node login.js            # 不带参数则依次为 config 里所有账号登录

const fs = require('fs');
const path = require('path');
const { openContext } = require('./src/browser');
const { isLoggedIn, LISTINGS_URL } = require('./src/mint');

function loadConfig() {
  const p = path.resolve(__dirname, 'config.json');
  if (!fs.existsSync(p)) {
    console.error('缺少 config.json，请先 cp config.example.json config.json 并填写。');
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

async function loginOne(account, cfg) {
  console.log(`\n=== 登录账号【${account.name}】 profile: ${account.profileDir} ===`);
  const context = await openContext({
    profileDir: account.profileDir,
    headless: false, // 登录必须有头
    useRealChrome: cfg.useRealChrome,
    proxy: account.browserProxy || cfg.browserProxy,
  });
  const page = context.pages()[0] || (await context.newPage());
  await page.goto(LISTINGS_URL, { waitUntil: 'domcontentloaded' });

  console.log('请在弹出的浏览器里完成登录（含 2FA）。检测到登录态后会自动继续……');
  // 轮询登录态，最多等 5 分钟
  const deadline = Date.now() + 5 * 60 * 1000;
  while (Date.now() < deadline) {
    if (await isLoggedIn(context)) {
      console.log(`✅ 账号【${account.name}】登录成功，登录态已保存。`);
      await page.waitForTimeout(1500);
      await context.close();
      return true;
    }
    await page.waitForTimeout(2000);
  }
  console.error(`⏱️ 等待登录超时（账号 ${account.name}）。`);
  await context.close();
  return false;
}

(async () => {
  const cfg = loadConfig();
  const target = process.argv[2];
  const accounts = target ? cfg.accounts.filter((a) => a.name === target) : cfg.accounts;
  if (accounts.length === 0) {
    console.error(`config.json 里找不到账号: ${target}`);
    process.exit(1);
  }
  for (const a of accounts) {
    await loginOne(a, cfg);
  }
  process.exit(0);
})();
