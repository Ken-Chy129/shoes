'use strict';

const { runWithRetry } = require('./run-policy');

function openPageWithRetry(page, url, {
  attempts = 3,
  delayMs = 30000,
  timeoutMs = 60000,
  sleep = (waitMs) => page.waitForTimeout(waitMs),
  onRetry,
} = {}) {
  return runWithRetry(
    () => page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs }),
    { attempts, delayMs, sleep, onRetry },
  );
}

module.exports = { openPageWithRetry };
