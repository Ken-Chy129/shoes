'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { openPageWithRetry } = require('../src/navigation');

test('retries a timed-out page navigation in the same browser session', async () => {
  let navigations = 0;
  const delays = [];
  const page = {
    async goto(url, options) {
      navigations++;
      assert.equal(url, 'https://example.com/listings');
      assert.deepEqual(options, { waitUntil: 'domcontentloaded', timeout: 60000 });
      if (navigations === 1) throw new Error('page.goto: Timeout 60000ms exceeded');
      return 'loaded';
    },
  };

  const result = await openPageWithRetry(page, 'https://example.com/listings', {
    attempts: 3,
    delayMs: 30000,
    sleep: async (delayMs) => delays.push(delayMs),
  });

  assert.equal(result, 'loaded');
  assert.equal(navigations, 2);
  assert.deepEqual(delays, [30000]);
});
