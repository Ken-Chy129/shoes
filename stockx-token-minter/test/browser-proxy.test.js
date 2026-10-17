'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('net');
const { EventEmitter } = require('events');
const { prepareBrowserProxy, launchWithProxy } = require('../src/browser-proxy');

const credentials = { server: 'socks5://127.0.0.1:1080', username: 'user@:/', password: 'pass@:/#' };
const connectable = url => new Promise(resolve => {
  const u = new URL(url);
  const socket = net.connect(Number(u.port), u.hostname);
  socket.once('connect', () => { socket.destroy(); resolve(true); });
  socket.once('error', () => resolve(false));
});

test('HTTP proxies remain unchanged; SOCKS auth is only exposed at loopback bridge', async () => {
  const http = { server: 'http://localhost:8000', username: 'u', password: 'p' };
  assert.deepEqual((await prepareBrowserProxy(http)).proxy, http);
  assert.equal((await prepareBrowserProxy(undefined)).proxy, undefined);
  const bridge = await prepareBrowserProxy(credentials);
  try {
    assert.deepEqual(Object.keys(bridge.proxy), ['server']);
    assert.match(bridge.proxy.server, /^http:\/\/127\.0\.0\.1:\d+$/);
    assert.equal(await connectable(bridge.proxy.server), true);
  } finally { await bridge.close(); }
  assert.equal(await connectable(bridge.proxy.server), false);
});

test('unauthenticated SOCKS stays direct', async () => {
  assert.deepEqual((await prepareBrowserProxy('socks5h://localhost:1080')).proxy, { server: 'socks5://localhost:1080' });
});

test('bridge is closed on normal context shutdown and launch failure', async () => {
  let endpoint;
  const ctx = await launchWithProxy(credentials, async proxy => {
    endpoint = proxy.server;
    const context = new EventEmitter();
    context.close = async () => context.emit('close');
    return context;
  });
  await ctx.close();
  assert.equal(await connectable(endpoint), false);
  await assert.rejects(launchWithProxy(credentials, async proxy => {
    endpoint = proxy.server;
    throw new Error('launch failed');
  }), /launch failed/);
  assert.equal(await connectable(endpoint), false);
});

test('invalid upstream does not disclose credentials', async () => {
  await assert.rejects(prepareBrowserProxy({ server: 'socks5://user:secret@[broken' }), error => {
    assert.equal(error.message.includes('secret'), false);
    return true;
  });
});

test('CONNECT traverses SOCKS5 authentication with remote DNS and special characters', async () => {
  let authenticated = false;
  let destination;
  const sockets = new Set();
  const socks = net.createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    let pending = Buffer.alloc(0), stage = 0;
    socket.on('data', chunk => {
      pending = Buffer.concat([pending, chunk]);
      if (stage === 0 && pending.length >= 2 + pending[1]) {
        pending = pending.subarray(2 + pending[1]);
        socket.write(Buffer.from([5, 2])); stage = 1;
      }
      if (stage === 1 && pending.length >= 3 + pending[1]) {
        const userLen = pending[1], passLen = pending[2 + userLen];
        if (pending.length < 3 + userLen + passLen) return;
        authenticated = pending.subarray(2, 2 + userLen).toString() === credentials.username
          && pending.subarray(3 + userLen, 3 + userLen + passLen).toString() === credentials.password;
        pending = pending.subarray(3 + userLen + passLen);
        socket.write(Buffer.from([1, authenticated ? 0 : 1])); stage = 2;
      }
      if (stage === 2 && pending.length >= 5 && pending.length >= 7 + pending[4]) {
        assert.equal(pending[3], 3); // domain, not locally resolved IP
        destination = pending.subarray(5, 5 + pending[4]).toString();
        pending = pending.subarray(7 + pending[4]);
        socket.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 80])); stage = 3;
      }
      if (stage === 3 && pending.length) { socket.write(pending); pending = Buffer.alloc(0); }
    });
  });
  await new Promise(resolve => socks.listen(0, '127.0.0.1', resolve));
  const bridge = await prepareBrowserProxy({ ...credentials, server: `socks5://127.0.0.1:${socks.address().port}` });
  try {
    await new Promise((resolve, reject) => {
      const u = new URL(bridge.proxy.server);
      const client = net.connect(Number(u.port), u.hostname);
      let received = '', connected = false;
      client.setTimeout(3000, () => client.destroy(new Error('tunnel timeout')));
      client.on('error', reject);
      client.on('connect', () => client.write('CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n'));
      client.on('data', data => {
        received += data.toString();
        if (!connected && received.includes('\r\n\r\n')) {
          if (!received.startsWith('HTTP/1.1 200')) return client.destroy(new Error('CONNECT failed'));
          connected = true; received = ''; client.write('echo-payload');
        } else if (connected && received === 'echo-payload') { client.destroy(); resolve(); }
      });
    });
    assert.equal(authenticated, true);
    assert.equal(destination, 'example.test');
  } finally {
    await bridge.close();
    sockets.forEach(s => s.destroy());
    await new Promise(resolve => socks.close(resolve));
  }
});
