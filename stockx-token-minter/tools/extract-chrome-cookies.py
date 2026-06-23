#!/usr/bin/env python3
"""
从本机 Chrome 的某个 profile 中，解密导出 StockX(accounts.stockx.com)的会话 cookie，
输出成 Playwright cookie 格式 JSON，供 seed.js 注入到服务器发token机。

仅 macOS。原理：Chrome cookie 用 AES-128-CBC 加密，密钥来自钥匙串 "Chrome Safe Storage"
(PBKDF2-SHA1, salt='saltysalt', 1003 轮, 16字节)，IV=16个空格。

用法：
    python3 tools/extract-chrome-cookies.py "<Chrome profile 显示名>" <输出文件.json>
例：
    python3 tools/extract-chrome-cookies.py "港大" /tmp/seed_gangda.json

运行时会弹钥匙串授权框，点"允许"。Chrome 可以开着(脚本读的是 Cookies 库的副本)。
"""
import sys, os, json, sqlite3, shutil, subprocess, hashlib, tempfile

WANT = ['auth0', 'auth0_compat', 'did', 'did_compat']
SAMESITE = {'auth0': 'None', 'did': 'None', 'auth0_compat': 'Lax', 'did_compat': 'Lax'}
CHROME = os.path.expanduser('~/Library/Application Support/Google/Chrome')


def find_profile_dir(name):
    ls = json.load(open(os.path.join(CHROME, 'Local State')))
    cache = ls.get('profile', {}).get('info_cache', {})
    for d, info in cache.items():
        if info.get('name') == name:
            return d
    raise SystemExit(f'找不到名为 {name!r} 的 Chrome profile。现有：' +
                     ', '.join(repr(i.get('name')) for i in cache.values()))


def get_key():
    pw = subprocess.check_output(
        ['security', 'find-generic-password', '-w', '-s', 'Chrome Safe Storage']).strip()
    return hashlib.pbkdf2_hmac('sha1', pw, b'saltysalt', 1003, 16)


def decrypt(enc, keyhex, ivhex):
    if enc[:3] != b'v10':
        try:
            return enc.decode()
        except Exception:
            return ''
    p = subprocess.run(['openssl', 'enc', '-aes-128-cbc', '-d', '-K', keyhex, '-iv', ivhex, '-nopad'],
                       input=enc[3:], capture_output=True)
    out = p.stdout
    if out and out[-1] <= 16:
        out = out[:-out[-1]]  # 去 PKCS7 padding
    for cand in (out, out[32:]):  # 新版 Chrome 可能有 32 字节域名哈希前缀
        try:
            s = cand.decode('utf8')
            if s and all(32 <= ord(c) < 127 for c in s[:8]):
                return s
        except Exception:
            pass
    return out[32:].decode('utf8', 'replace')


def chrome_to_unix(us):
    return int(us / 1_000_000 - 11644473600) if us else -1


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    name, outf = sys.argv[1], sys.argv[2]
    pdir = find_profile_dir(name)
    cookies_db = os.path.join(CHROME, pdir, 'Cookies')
    if not os.path.exists(cookies_db):
        cookies_db = os.path.join(CHROME, pdir, 'Network', 'Cookies')
    if not os.path.exists(cookies_db):
        raise SystemExit(f'找不到 {name} 的 Cookies 库')

    key = get_key()
    keyhex, ivhex = key.hex(), (b'\x20' * 16).hex()

    tmp = tempfile.mktemp()
    shutil.copy(cookies_db, tmp)
    con = sqlite3.connect(tmp)
    placeholders = ','.join('?' * len(WANT))
    query = ("select name, encrypted_value, expires_utc from cookies "
             "where host_key like '%accounts.stockx.com%' and name in (" + placeholders + ")")
    rows = con.execute(query, WANT).fetchall()
    con.close()
    os.remove(tmp)

    if not rows:
        raise SystemExit(f'⚠️ profile {name!r} 里没找到 accounts.stockx.com 的会话 cookie，'
                         '该 profile 可能没登录 StockX。')

    cookies = [{
        'name': n, 'value': decrypt(ev, keyhex, ivhex),
        'domain': 'accounts.stockx.com', 'path': '/',
        'expires': chrome_to_unix(exp), 'httpOnly': True, 'secure': True,
        'sameSite': SAMESITE.get(n, 'Lax'),
    } for n, ev, exp in rows]

    json.dump(cookies, open(outf, 'w'))
    names = ', '.join(c['name'] for c in cookies)
    print(f'✅ 已导出 {len(cookies)} 个 cookie ({names}) -> {outf}')
    print('   下一步：scp 到服务器，再 node seed.js "<后端账号名>" <该文件>')


if __name__ == '__main__':
    main()
