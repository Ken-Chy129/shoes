'use strict';

function normalizeProxy(proxy) {
  if (proxy === undefined || proxy === null || proxy === '') return undefined;

  if (typeof proxy === 'string') {
    return { server: proxy };
  }

  if (typeof proxy !== 'object' || Array.isArray(proxy)) {
    throw new TypeError('browserProxy 必须是代理地址字符串或包含 server 的对象');
  }

  const server = typeof proxy.server === 'string' ? proxy.server.trim() : '';
  const username = typeof proxy.username === 'string' ? proxy.username : undefined;
  const password = typeof proxy.password === 'string' ? proxy.password : undefined;

  if (!server) {
    if (username || password) {
      throw new Error('配置 browserProxy.username/password 时必须同时配置 browserProxy.server');
    }
    return undefined;
  }

  const normalized = { server };
  if (username !== undefined && username !== '') normalized.username = username;
  if (password !== undefined && password !== '') normalized.password = password;
  return normalized;
}

module.exports = { normalizeProxy };
