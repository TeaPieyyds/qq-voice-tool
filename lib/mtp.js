'use strict';
/** mtp.js —— MTP 后端（无需 adb，手机以「传输文件」模式连接即可） */
const { execFile } = require('child_process');
const path = require('path');

const PS1 = path.join(__dirname, 'mtp.ps1');

function call(args, timeout = 90000) {
  return new Promise((resolve) => {
    execFile('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', PS1, ...args,
    ], { timeout, windowsHide: true, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      const line = (stdout || '').trim().split(/\r?\n/).filter(Boolean).pop();
      if (!line) {
        return resolve({ ok: false, error: (stderr || (err && err.message) || '没有输出').trim().slice(0, 300) });
      }
      try { resolve(JSON.parse(line)); }
      catch { resolve({ ok: false, error: '返回解析失败：' + line.slice(0, 200) }); }
    });
  });
}

const seg = (arr) => JSON.stringify(arr || []);

async function probe(device, segments) {
  return call(['-Action', 'probe', '-Device', device, '-Segments', seg(segments)], 20000);
}
async function list(device, segments) {
  return call(['-Action', 'list', '-Device', device, '-Segments', seg(segments)], 30000);
}
async function copyOut(device, segments, remote, local) {
  return call(['-Action', 'copyout', '-Device', device, '-Segments', seg(segments), '-Remote', remote, '-Local', local], 60000);
}
async function copyOutMany(device, segments, names, localDir) {
  return call(['-Action', 'copyoutmany', '-Device', device, '-Segments', seg(segments),
    '-Remote', JSON.stringify(names || []), '-Local', localDir], 120000);
}
async function copyIn(device, segments, local, remote) {
  return call(['-Action', 'copyin', '-Device', device, '-Segments', seg(segments), '-Remote', remote, '-Local', local], 90000);
}
/** 直接覆盖（不先删除，避免 MTP 删除卡死） */
async function overwrite(device, segments, local, remote) {
  return call(['-Action', 'overwrite', '-Device', device, '-Segments', seg(segments), '-Remote', remote, '-Local', local], 60000);
}
async function verbs(device, segments, remote) {
  return call(['-Action', 'verbs', '-Device', device, '-Segments', seg(segments), '-Remote', remote], 30000);
}

async function remove(device, segments, remote) {
  return call(['-Action', 'delete', '-Device', device, '-Segments', seg(segments), '-Remote', remote], 30000);
}

const PTT_SEG = ['Android', 'data', 'com.tencent.mobileqq', 'Tencent', 'MobileQQ'];

/** 找到 ptt 目录的分段（跨日期自动取最新一个含 slk 的目录） */
async function findPtt(device, uinHint) {
  const base = ['__storage__', ...PTT_SEG];
  const uins = await list(device, base);
  if (!uins.ok) return { ok: false, error: uins.error || '无法打开手机存储' };

  const numeric = (uins.items || []).map(i => i.name).filter(n => /^\d{5,12}$/.test(n));
  const uinsToTry = (uinHint && numeric.includes(String(uinHint))) ? [String(uinHint)] : numeric;

  for (const uin of uinsToTry) {
    const ptt = await list(device, [...base, uin, 'ptt']);
    if (!ptt.ok) continue;
    const months = (ptt.items || []).map(i => i.name)
      .filter(n => /^\d{6}$/.test(n) || /^\d{4}-\d{2}$/.test(n))
      .sort().reverse();
    for (const m of months) {
      const days = await list(device, [...base, uin, 'ptt', m]);
      if (!days.ok) continue;
      const dayNames = (days.items || []).map(i => i.name).filter(n => /^\d{1,2}$/.test(n))
        .sort((a, b) => Number(b) - Number(a));
      for (const d of dayNames) {
        const segments = [...base, uin, 'ptt', m, d];
        const fl = await list(device, segments);
        if (fl.ok && (fl.items || []).some(i => i.name.endsWith('.slk'))) {
          const storage = (fl.path || '').split('\\').filter(Boolean)[0] || '';
          return { ok: true, storage, uin, month: m, day: d, segments };
        }
      }
    }
  }
  return { ok: false, error: '没有找到 QQ 语音目录。常见原因：① 手机没解锁或不是「传输文件」模式；② 该机型（尤其 Pixel/部分小米）的 MTP 不暴露 Android/data，请改用 ADB 方式；③ QQ 没有语音记录' };
}

module.exports = { verbs, probe, list, copyOut, copyOutMany, copyIn, overwrite, remove, findPtt, PTT_SEG };
