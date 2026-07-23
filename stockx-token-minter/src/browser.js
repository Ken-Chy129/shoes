'use strict';

const path = require('path');
const { chromium } = require('playwright');
const { normalizeProxy } = require('./proxy-config');

// 与解 cookie 时一致的 UA，降低 Cloudflare/PerimeterX 指纹突变风险
const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36';

/**
 * 打开一个持久化浏览器上下文（每个账号一个 profileDir，登录态长期保存）。
 * headless：日常发 token 用 true；首次登录用 false（headful）。
 * useRealChrome：用本机真实 Chrome（channel:'chrome'），比自带 Chromium 更不易被 Cloudflare 判定为机器人。
 */
async function openContext({ profileDir, headless, useRealChrome, proxy }) {
  const opts = {
    headless: !!headless,
    userAgent: USER_AGENT,
    viewport: { width: 1440, height: 900 },
    locale: 'en-US',
    args: [
      '--disable-blink-features=AutomationControlled',
      // 省内存：小内存服务器上避免 Chrome 尖峰挤爆 MySQL/后端
      '--disable-dev-shm-usage',
      '--disable-gpu',
      '--no-sandbox',
      '--disable-extensions',
      '--disable-background-networking',
    ],
  };
  if (useRealChrome) opts.channel = 'chrome';
  const proxyOptions = normalizeProxy(proxy);
  if (proxyOptions) opts.proxy = proxyOptions;
  return await chromium.launchPersistentContext(path.resolve(profileDir), opts);
}

module.exports = { openContext, USER_AGENT };
