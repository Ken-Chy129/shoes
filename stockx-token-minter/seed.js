'use strict';

// 无头服务器登录方案：把在「有图形界面的机器」上登录后导出的 Auth0 会话 cookie
// （auth0 / auth0_compat / did / did_compat）注入到服务器的浏览器 profile，
// 从而免去在无头服务器上交互登录。会话 cookie 不绑 IP，服务器浏览器会自己现场
// 解 Cloudflare 拿到本机 IP 的 cf_clearance。
//
// 用法：
//   node seed.js "美区" ./seed-cookies.json
//
// 之后用 node index.js --once 验证能否签出 token。

const fs = require('fs');
const path = require('path');
const { openContext } = require('./src/browser');
const { isLoggedIn, mintFreshToken, LISTINGS_URL } = require('./src/mint');

function loadConfig() {
  const p = path.resolve(__dirname, 'config.json');
  if (!fs.existsSync(p)) {
    console.error('缺少 config.json');
    process.exit(1);
  }
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

(async () => {
  const cfg = loadConfig();
  const name = process.argv[2];
  const cookiesFile = process.argv[3];
  if (!name || !cookiesFile) {
    console.error('用法: node seed.js "<账号名>" <cookies.json>');
    process.exit(1);
  }
  const account = cfg.accounts.find((a) => a.name === name);
  if (!account) {
    console.error(`config.json 里找不到账号: ${name}`);
    process.exit(1);
  }
  const cookies = JSON.parse(fs.readFileSync(path.resolve(cookiesFile), 'utf8'));

  const context = await openContext({
    profileDir: account.profileDir,
    headless: cfg.headless,
    useRealChrome: cfg.useRealChrome,
  });
  try {
    await context.addCookies(cookies);
    console.log(`已注入 ${cookies.length} 个会话 cookie 到 profile: ${account.profileDir}`);

    const page = context.pages()[0] || (await context.newPage());
    // 访问一次让 SPA 现场建立 token cookie 并解 Cloudflare（拿本机 IP 的 cf_clearance）
    await page.goto(LISTINGS_URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(3000);

    if (!(await isLoggedIn(context))) {
      console.log('⚠️ 注入后仍未检测到登录态。尝试直接静默签发以确认……');
    }
    const token = await mintFreshToken(page);
    console.log(`✅ 注入成功并签出 token：customer_uuid=${token.customerUuid} 到期=${token.expiresAt}`);
    console.log(`   ⚠️ 请核对上面 customer_uuid 确实是后端账号「${name}」对应的 StockX 账号，避免张冠李戴！`);
    console.log('登录态已保存进 profile，后续 node index.js 即可无人值守刷新。');
  } catch (e) {
    console.error('❌ seed 失败:', e.message);
    process.exitCode = 1;
  } finally {
    await context.close().catch(() => {});
  }
})();
