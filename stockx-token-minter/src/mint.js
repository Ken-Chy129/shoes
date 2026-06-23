'use strict';

// StockX 网页端 SPA 的 Auth0 client_id（azp）。graphql 网关只认这个 client 签发的 token。
const SPA_CLIENT_ID = 'v1Ytb8BK4vJ3tr7b5gNFCxoXACmlaGX6';
const LISTINGS_URL = 'https://pro.stockx.com/listings';

function decodeJwt(jwt) {
  const parts = (jwt || '').split('.');
  if (parts.length !== 3) return null;
  try {
    const json = Buffer.from(parts[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
    return JSON.parse(json);
  } catch {
    return null;
  }
}

// 判断当前持久化上下文是否仍是登录态。
// 持久标志是 accounts.stockx.com 的 auth0 会话 cookie——token cookie 短命且不一定落盘，
// 不能用它判断。最终是否真登录以 mintFreshToken 能否拿到 code 为准。
async function isLoggedIn(context) {
  const cookies = await context.cookies();
  return cookies.some((c) => c.name === 'auth0' && (c.value || '').length > 10);
}

/**
 * 在已登录的浏览器里跑一遍 Auth0 静默授权（prompt=none + PKCE + web_message iframe），
 * 拿到一枚全新的 12h access_token。整个过程无 UI、无密码。
 * 浏览器自带的 auth0 会话 cookie + cf_clearance 让它能过 accounts.stockx.com 的 Cloudflare 挑战，
 * 这正是纯服务端 curl 做不到、必须用浏览器的原因。
 */
async function mintFreshToken(page) {
  const tok = await page.evaluate(async (CLIENT) => {
    const b64url = (buf) =>
      btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
    const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
    const state = b64url(crypto.getRandomValues(new Uint8Array(16)));
    const nonce = b64url(crypto.getRandomValues(new Uint8Array(16)));

    const authUrl =
      'https://accounts.stockx.com/authorize?' +
      new URLSearchParams({
        client_id: CLIENT,
        scope: 'openid profile email',
        audience: 'gateway.stockx.com',
        prompt: 'none',
        response_type: 'code',
        response_mode: 'web_message',
        state,
        nonce,
        redirect_uri: 'https://pro.stockx.com',
        code_challenge: challenge,
        code_challenge_method: 'S256',
      });

    // ① 隐藏 iframe 静默拿 code（靠 accounts.stockx.com 的 auth0 会话 cookie）
    const code = await new Promise((resolve, reject) => {
      const ifr = document.createElement('iframe');
      ifr.style.display = 'none';
      ifr.src = authUrl;
      const to = setTimeout(() => {
        cleanup();
        reject(new Error('authorize_timeout'));
      }, 20000);
      function onMsg(e) {
        if (e.origin !== 'https://accounts.stockx.com') return;
        const d = e.data && e.data.response ? e.data.response : e.data;
        if (d && (d.code || d.error)) {
          clearTimeout(to);
          cleanup();
          d.code ? resolve(d.code) : reject(new Error('authorize_' + (d.error || 'unknown')));
        }
      }
      function cleanup() {
        window.removeEventListener('message', onMsg);
        ifr.remove();
      }
      window.addEventListener('message', onMsg);
      document.body.appendChild(ifr);
    });

    // ② code 换 token（公有客户端，无 client_secret；form-encoded 避免 CORS 预检）
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT,
      code,
      code_verifier: verifier,
      redirect_uri: 'https://pro.stockx.com',
    });
    const res = await fetch('https://accounts.stockx.com/oauth/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (!res.ok) throw new Error('token_http_' + res.status);
    return await res.json();
  }, SPA_CLIENT_ID);

  const accessToken = tok && tok.access_token;
  if (!accessToken) throw new Error('no access_token in response');

  const claims = decodeJwt(accessToken);
  if (!claims) throw new Error('access_token not a JWT');
  if (claims.azp !== SPA_CLIENT_ID) {
    throw new Error(`unexpected azp: ${claims.azp} (expected SPA client)`);
  }

  return {
    bearer: `Bearer ${accessToken}`,
    accessToken,
    azp: claims.azp,
    scope: tok.scope,
    expiresInSec: tok.expires_in,
    expiresAt: new Date(claims.exp * 1000).toISOString(),
    customerUuid: claims['https://stockx.com/customer_uuid'],
  };
}

module.exports = { SPA_CLIENT_ID, LISTINGS_URL, decodeJwt, isLoggedIn, mintFreshToken };
