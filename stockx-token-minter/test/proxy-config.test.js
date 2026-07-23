'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { normalizeProxy } = require('../src/proxy-config');

test('returns undefined when no browser proxy is configured', () => {
  assert.equal(normalizeProxy(undefined), undefined);
  assert.equal(normalizeProxy(''), undefined);
});

test('keeps backward compatibility with a proxy server string', () => {
  assert.deepEqual(normalizeProxy('socks5://127.0.0.1:18080'), {
    server: 'socks5://127.0.0.1:18080',
  });
});

test('supports an authenticated HTTP proxy object', () => {
  assert.deepEqual(normalizeProxy({
    server: 'http://proxy.example.com:15818',
    username: 'proxy-user',
    password: 'proxy-password',
  }), {
    server: 'http://proxy.example.com:15818',
    username: 'proxy-user',
    password: 'proxy-password',
  });
});

test('rejects proxy credentials without a proxy server', () => {
  assert.throws(
    () => normalizeProxy({ username: 'proxy-user', password: 'proxy-password' }),
    /browserProxy.server/,
  );
});

test('rejects unsupported proxy configuration types', () => {
  assert.throws(() => normalizeProxy(1234), /browserProxy/);
});
