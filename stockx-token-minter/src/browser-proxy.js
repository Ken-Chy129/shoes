'use strict';

const { anonymizeProxy, closeAnonymizedProxy } = require('proxy-chain');
const { normalizeProxy } = require('./proxy-config');

// Chromium cannot authenticate SOCKS proxies. Keep credentials in the upstream
// connector only, and give Chrome a loopback HTTP CONNECT endpoint.
async function prepareBrowserProxy(proxy) {
  const normalized = normalizeProxy(proxy);
  if (!normalized || !/^socks5h?:\/\//i.test(normalized.server)) {
    return { proxy: normalized, close: async () => {} };
  }
  let upstream;
  try {
    upstream = new URL(normalized.server);
    if (normalized.username !== undefined) upstream.username = normalized.username;
    if (normalized.password !== undefined) upstream.password = normalized.password;
    // Resolve destination hostnames at the proxy, not at the server.
    upstream.protocol = 'socks5h:';
  } catch {
    throw new Error('Invalid SOCKS5 browser proxy configuration');
  }
  if (!upstream.username && !upstream.password) {
    return { proxy: { server: normalized.server.replace(/^socks5h:/i, 'socks5:') }, close: async () => {} };
  }
  let local;
  try {
    local = await anonymizeProxy(upstream.href);
  } catch {
    // Never propagate upstream URLs containing credentials into the minter log.
    throw new Error('Could not start SOCKS5 browser proxy bridge');
  }
  return {
    proxy: { server: local },
    close: () => closeAnonymizedProxy(local, true),
  };
}

async function launchWithProxy(proxy, launch) {
  const bridge = await prepareBrowserProxy(proxy);
  try {
    const context = await launch(bridge.proxy);
    const originalClose = context.close.bind(context);
    const cleanup = () => bridge.close().catch(() => {});
    context.once('close', cleanup);
    context.close = async (...args) => {
      try { return await originalClose(...args); }
      finally { await cleanup(); }
    };
    return context;
  } catch (error) {
    await bridge.close().catch(() => {});
    throw error;
  }
}

module.exports = { prepareBrowserProxy, launchWithProxy };
