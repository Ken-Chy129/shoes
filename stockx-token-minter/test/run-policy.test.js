'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { exitCodeForRefresh, runWithRetry } = require('../src/run-policy');

test('returns a failing exit code when any account refresh fails', () => {
  assert.equal(exitCodeForRefresh({ succeeded: 0, total: 6 }), 1);
  assert.equal(exitCodeForRefresh({ succeeded: 5, total: 6 }), 1);
});

test('returns a successful exit code only when every account refresh succeeds', () => {
  assert.equal(exitCodeForRefresh({ succeeded: 6, total: 6 }), 0);
});

test('retries a failed operation until it succeeds', async () => {
  let attempts = 0;
  const delays = [];

  const result = await runWithRetry(async () => {
    attempts++;
    if (attempts < 3) throw new Error('authorize_timeout');
    return 'fresh-token';
  }, {
    attempts: 3,
    delayMs: 5000,
    sleep: async (delayMs) => delays.push(delayMs),
  });

  assert.equal(result, 'fresh-token');
  assert.equal(attempts, 3);
  assert.deepEqual(delays, [5000, 5000]);
});

test('throws the last error after exhausting retries', async () => {
  let attempts = 0;

  await assert.rejects(
    runWithRetry(async () => {
      attempts++;
      throw new Error(`failure-${attempts}`);
    }, {
      attempts: 2,
      delayMs: 0,
      sleep: async () => {},
    }),
    /failure-2/,
  );

  assert.equal(attempts, 2);
});
