'use strict';
/** adb.js —— ADB 后端：设备发现、目录扫描、拉取/推送、校验 */
const { execFile } = require('child_process');
const fs = require('fs');
const path = require('path');

const QQ_PTT_ROOT = '/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ';

function run(exe, args, timeout = 20000) {
  return new Promise((resolve) => {
    execFile(exe, args, { timeout, windowsHide: true, maxBuffer: 4 * 1024 * 1024 }, (err, stdout, stderr) => {
      resolve({ code: err ? (err.code ?? 1) : 0, out: stdout || '', err: (stderr || '') + (err && !stderr ? err.message : '') });
    });
  });
}

function candidates(cfgAdb, toolDir) {
  const list = [];
  if (cfgAdb) list.push(cfgAdb);
  list.push(path.join(toolDir, 'platform-tools', 'adb.exe'));
  list.push(path.join(toolDir, 'adb.exe'));
  list.push('adb');                                   // PATH
  list.push('C:\\platform-tools\\adb.exe');
  list.push(path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'));
  list.push('D:\\platform-tools\\adb.exe');
  list.push('E:\\platform-tools\\adb.exe');
  return [...new Set(list.filter(Boolean))];
}

let cachedAdb = null;

async function locate(cfgAdb, toolDir) {
  if (cachedAdb && fs.existsSync(cachedAdb)) return cachedAdb;
  for (const c of candidates(cfgAdb, toolDir)) {
    if (c === 'adb') {
      const r = await run('adb', ['version'], 5000);
      if (r.code === 0) { cachedAdb = 'adb'; return 'adb'; }
    } else if (fs.existsSync(c)) {
      const r = await run(c, ['version'], 5000);
      if (r.code === 0) { cachedAdb = c; return c; }
    }
  }
  return null;
}

async function info(cfgAdb, toolDir) {
  const adb = await locate(cfgAdb, toolDir);
  if (!adb) return { ok: false, reason: '未找到 adb（可在设置里填 adb.exe 的路径）' };
  const v = await run(adb, ['version']);
  const d = await run(adb, ['devices', '-l']);
  const lines = d.out.split(/\r?\n/).filter(l => l && !l.startsWith('List of devices'));
  const devices = lines.map(l => {
    const [serial, state] = l.split(/\s+/);
    const model = (l.match(/model:(\S+)/) || [])[1] || '';
    return { serial, state, model };
  }).filter(x => x.serial);
  const ready = devices.filter(x => x.state === 'device');
  return {
    ok: ready.length > 0,
    adbPath: adb,
    version: (v.out.match(/version ([\d.]+)/) || [])[1] || '',
    devices,
    reason: ready.length ? '' : (devices.length ? '设备未授权，请在手机上点「允许 USB 调试」' : '没有检测到设备'),
  };
}

const sh = (adb, cmd) => run(adb, ['shell', cmd], 25000);

/**
 * QQ 语音根目录的候选清单
 * 覆盖：标准路径 / 不同挂载点 / 应用分身与工作资料（user 999）/ QQ 国际版·极速版包名
 */
const QQ_ROOTS = [
  '/sdcard/Android/data/com.tencent.mobileqq/Tencent/MobileQQ',
  '/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ',
  '/storage/self/primary/Android/data/com.tencent.mobileqq/Tencent/MobileQQ',
  '/storage/emulated/999/Android/data/com.tencent.mobileqq/Tencent/MobileQQ',
  '/sdcard/Android/data/com.tencent.mobileqqi/Tencent/MobileQQ',
  '/sdcard/Android/data/com.tencent.qqlite/Tencent/MobileQQ',
];

/** 找出所有「有 ptt 目录」的组合，返回 [{root, uin, ptt}] */
async function listAccounts(adb, cfgUin) {
  const out = [];
  for (const root of QQ_ROOTS) {
    const ls = await sh(adb, `ls -1 ${root} 2>/dev/null`);
    let uins = ls.out.split(/\r?\n/).map(s => s.trim()).filter(s => /^\d{4,12}$/.test(s));
    if (cfgUin) uins = uins.includes(String(cfgUin)) ? [String(cfgUin)] : uins;
    for (const u of uins) {
      const t = await sh(adb, `ls -d ${root}/${u}/ptt 2>/dev/null`);
      if (t.out.trim()) out.push({ root, uin: u, ptt: `${root}/${u}/ptt` });
    }
  }
  return out;
}

/** 某个 ptt 目录下最新一条 slk 的修改时间（拿不到返回 0） */
async function newestMtime(adb, ptt) {
  const r = await sh(adb, `ls -1t ${ptt}/*/*/*.slk 2>/dev/null | head -1`);
  let f = r.out.trim().split(/\r?\n/)[0];
  if (!f) {
    const r2 = await sh(adb, `find ${ptt} -type f -name '*.slk' 2>/dev/null | head -1`);
    f = r2.out.trim().split(/\r?\n/)[0];
  }
  if (!f) return 0;
  const s = await sh(adb, `stat -c '%Y' "${f}" 2>/dev/null`);
  return parseInt(s.out.trim(), 10) || 0;
}

/**
 * 选账号：不取"第一个"，而是取"最近有录音的那个"
 * （多账号登录时，第一个不一定是正在用的那个）
 */
async function findUin(adb, cfgUin) {
  const accounts = await listAccounts(adb, cfgUin);
  if (!accounts.length) return null;
  let best = accounts[0], bestTime = -1;
  for (const a of accounts) {
    const t = await newestMtime(adb, a.ptt);
    if (t > bestTime) { bestTime = t; best = a; }
  }
  return best;
}

/**
 * 列出最近的 slk（不假设目录层级、不限定文件名前缀）
 * 用 find 递归，兼容 ptt/YYYYMM/DD、ptt/YYYY-MM-DD 等不同布局
 */
async function listRecent(adb, account, limit = 40) {
  const ptt = typeof account === 'string' ? account : account.ptt;
  let r = await sh(adb, `ls -1t ${ptt}/*/*/*.slk 2>/dev/null | head -${limit * 2}`);
  let files = r.out.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  if (!files.length) {
    r = await sh(adb, `find ${ptt} -type f -name '*.slk' 2>/dev/null | head -${limit * 2}`);
    files = r.out.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
  }
  if (!files.length) return [];

  const meta = new Map();
  for (let i = 0; i < files.length; i += 40) {
    const chunk = files.slice(i, i + 40).map(f => `"${f}"`).join(' ');
    const st = await sh(adb, `stat -c '%n|%s|%Y' ${chunk} 2>/dev/null`);
    for (const line of st.out.split(/\r?\n/)) {
      const parts = line.trim().split('|');
      if (parts.length >= 3) meta.set(parts[0], { size: +parts[1], mtime: +parts[2] * 1000 });
    }
  }

  return files
    .map(p => ({
      path: p,
      name: p.split('/').pop(),
      dir: p.slice(0, p.lastIndexOf('/')),
      size: meta.get(p)?.size ?? null,
      modified: meta.get(p)?.mtime ?? null,
    }))
    .sort((a, b) => (b.modified || 0) - (a.modified || 0))
    .slice(0, limit);
}

async function pull(adb, remote, local) {
  fs.mkdirSync(path.dirname(local), { recursive: true });
  const r = await run(adb, ['pull', remote, local], 60000);
  if (r.code !== 0 || !fs.existsSync(local)) throw new Error('拉取失败：' + (r.err || r.out).trim());
  return local;
}

async function push(adb, local, remote) {
  const r = await run(adb, ['push', local, remote], 90000);
  if (r.code !== 0) throw new Error('推送失败：' + (r.err || r.out).trim());
  return true;
}

async function remove(adb, remote) {
  const r = await sh(adb, `rm -f "${remote}"`);
  return r.code === 0;
}

async function remoteMd5(adb, remote) {
  const r = await sh(adb, `md5sum "${remote}" 2>/dev/null`);
  return (r.out.trim().split(/\s+/)[0] || '').toLowerCase();
}

module.exports = { QQ_PTT_ROOT, QQ_ROOTS, listAccounts, info, findUin, listRecent, pull, push, remove, remoteMd5, sh, locate };
