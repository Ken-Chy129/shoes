'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { retryable } = require('../index');

for (const code of [0, 1]) {
  test(`oneshot exits with ${code} even with a referenced connection handle`, async () => {
    const child = spawn(process.execPath, ['-e', `
      const net = require('net');
      const { finishOnce } = require(${JSON.stringify(require.resolve('../src/once-exit'))});
      net.createServer().listen(0, '127.0.0.1', () => {
        console.log('ROUND_COMPLETE');
        finishOnce(${code}, 50);
      });
    `], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', b => output += b);
    const result = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('oneshot did not exit')); }, 5000);
      child.once('error', e => { clearTimeout(timeout); reject(e); });
      child.once('exit', (exitCode, signal) => { clearTimeout(timeout); resolve({ exitCode, signal }); });
    });
    assert.deepEqual(result, { exitCode: code, signal: null });
    assert.match(output, /ROUND_COMPLETE/);
  });
}

test('expired login sessions fail fast, transient timeouts remain retryable', () => {
  assert.equal(retryable(new Error('page.evaluate: Error: authorize_login_required')), false);
  assert.equal(retryable(new Error('authorize_consent_required')), false);
  assert.equal(retryable(new Error('未登录或登录态已失效')), false);
  assert.equal(retryable(new Error('authorize_timeout')), true);
});
