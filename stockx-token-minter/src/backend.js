'use strict';

// 把新 token 写回后端：先 GET 取完整账号对象，只改 authorization，再 PUT 回去，
// 避免 PUT 整体替换时把 apiKey / 费率 / country 等字段冲成默认值。
async function pushTokenToBackend({ baseUrl, apiToken, accountName, bearer }) {
  const headers = { 'content-type': 'application/json' };
  if (apiToken) headers['api-token'] = apiToken;

  const listRes = await fetch(`${baseUrl}/setting/stockx/accounts`, { headers });
  if (!listRes.ok) throw new Error(`GET accounts http ${listRes.status}`);
  const listJson = await listRes.json();
  if (!listJson.success) throw new Error(`GET accounts failed: ${listJson.errorMsg || JSON.stringify(listJson)}`);

  const account = (listJson.data || []).find((a) => a.name === accountName);
  if (!account) {
    const names = (listJson.data || []).map((a) => a.name).join(', ');
    throw new Error(`后端不存在账号 "${accountName}"，现有账号: [${names}]`);
  }

  account.authorization = bearer;
  account.autoRefresh = true; // 标记此账号由发token机托管，前端据此显示"自动刷新中"

  const putRes = await fetch(`${baseUrl}/setting/stockx/accounts/${encodeURIComponent(accountName)}`, {
    method: 'PUT',
    headers,
    body: JSON.stringify(account),
  });
  if (!putRes.ok) throw new Error(`PUT account http ${putRes.status}`);
  const putJson = await putRes.json();
  if (!putJson.success) throw new Error(`PUT account failed: ${putJson.errorMsg || JSON.stringify(putJson)}`);
  return true;
}

module.exports = { pushTokenToBackend };
