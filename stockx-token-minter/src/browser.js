'use strict';

const path = require('path');
const { chromium } = require('playwright');

// 与解 cookie 时一致的 UA，降低 Cloudflare/PerimeterX 指纹突变风险
const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36';

/**
 * 打开一个持久化浏览器上下文（每个账号一个 profileDir，登录态长期保存）。
 * headless：日常发 token 用 true；首次登录用 false（headful）。
 * useRealChrome：用本机真实 Chrome（channel:'chrome'），比自带 Chromium 更不易被 Cloudflare 判定为机器人。
 */
async function openContext({ profileDir, headless, useRealChrome }) {
  const opts = {
    headless: !!headless,
    userAgent: USER_AGENT,
    viewport: { width: 1440, height: 900 },
    locale: 'en-US',
    args: ['--disable-blink-features=AutomationControlled'],
  };
  if (useRealChrome) opts.channel = 'chrome';
  return await chromium.launchPersistentContext(path.resolve(profileDir), opts);
}

module.exports = { openContext, USER_AGENT };
