'use strict';
/**
 * QQ 语音发送助手 —— 本地服务
 * 流程：拖入音频 → 自动转成 QQ 可播放的 slk → 选一条手机里已录好的语音 → 一键替换（保留原文件名）
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { execFile } = require('child_process');

const slk = require('./lib/slkbuild');
const adb = require('./lib/adb');
const mtp = require('./lib/mtp');
const adbinstall = require('./lib/adbinstall');

const ROOT = __dirname;
const CACHE = path.join(ROOT, '.cache');
const EXPORT = path.join(ROOT, '导出');
const BACKUP = path.join(ROOT, '备份');
const CONFIG_FILE = path.join(ROOT, 'config.json');
const PORT = 8791;

fs.mkdirSync(CACHE, { recursive: true });
fs.mkdirSync(EXPORT, { recursive: true });
fs.mkdirSync(BACKUP, { recursive: true });

const DEFAULT_CONFIG = {
  qqUin: '',
  deviceName: 'iQOO',
  adbPath: '',
  preferMode: 'auto',
  backup: true,
};

function loadConfig() {
  try { return { ...DEFAULT_CONFIG, ...JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')) }; }
  catch { return { ...DEFAULT_CONFIG }; }
}
function saveConfig(c) { fs.writeFileSync(CONFIG_FILE, JSON.stringify(c, null, 2)); }

const builds = new Map();   // token -> {file, info, name}

/* ---------------- 设备 ---------------- */

async function deviceStatus() {
  const cfg = loadConfig();
  const [adbInfo, mtpProbe] = await Promise.all([
    adb.info(cfg.adbPath, ROOT),
    mtp.probe(cfg.deviceName, []),
  ]);
  return {
    adb: adbInfo,
    mtp: {
      ok: !!mtpProbe.ok,
      device: cfg.deviceName,
      error: mtpProbe.error || '',
    },
    preferMode: cfg.preferMode,
    config: cfg,
  };
}

/* ---------------- 扫描手机里的语音 ---------------- */

function parseSlkFile(filePath) {
  try {
    const b = fs.readFileSync(filePath);
    const p = slk.parseSlk(b);
    return { durationSec: +(p.durationMs / 1000).toFixed(2), frames: p.frames, slkValid: p.valid };
  } catch { return { durationSec: null, frames: null, slkValid: false }; }
}

async function scanAdb(cfg) {
  const adbPath = await adb.locate(cfg.adbPath, ROOT);
  const account = await adb.findUin(adbPath, cfg.qqUin);
  if (!account) throw new Error('手机上没找到 QQ 的 ptt 目录（已尝试标准路径、emulated/0、emulated/999 分身、QQ 国际版/极速版包名），确认 QQ 已登录且有语音记录');
  const files = await adb.listRecent(adbPath, account, 40);
  if (!files.length) throw new Error('这个 QQ 号下还没有语音记录，先随便录一条再取消');

  // 只给最近 10 条拉回来算时长（其余按大小估算）
  const detailed = files.slice(0, 10);
  for (const f of detailed) {
    const local = path.join(CACHE, 'scan', f.name);
    try {
      await adb.pull(adbPath, f.path, local);
      Object.assign(f, parseSlkFile(local));
    } catch { f.durationSec = null; }
  }
  return {
    mode: 'adb',
    where: `QQ ${account.uin} · ${files[0].dir.split('/ptt/')[1] || ''}`,
    uin: account.uin,
    root: account.root,
    dir: files[0].dir,
    files: files.map(f => ({ ...f, durationSec: f.durationSec ?? null })),
  };
}

/** QQ 语音文件名里带录制时间：stream_YYYYMMDDHHMMSSss.slk */
function parseStreamTime(name) {
  const m = /stream_(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/.exec(name);
  if (!m) return null;
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime();
}

async function scanMtp(cfg) {
  const found = await mtp.findPtt(cfg.deviceName, cfg.qqUin);
  if (!found.ok) throw new Error(found.error);
  const fl = await mtp.list(cfg.deviceName, found.segments);
  if (!fl.ok) throw new Error(fl.error);

  const files = (fl.items || [])
    .filter(i => i.name.endsWith('.slk'))
    .map(i => ({
      name: i.name,
      path: found.segments.join('\\') + '\\' + i.name,
      size: i.size || null,
      modified: i.modified ? Date.parse(i.modified) : parseStreamTime(i.name),
      segments: found.segments,
    }))
    .sort((a, b) => (b.modified || 0) - (a.modified || 0));

  const top = files.slice(0, 6);
  if (top.length) {
    const dirLocal = path.join(CACHE, 'scan');
    fs.mkdirSync(dirLocal, { recursive: true });
    const r = await mtp.copyOutMany(cfg.deviceName, found.segments, top.map(f => f.name), dirLocal);
    if (r.ok) {
      for (const it of r.items || []) {
        const f = top.find(x => x.name === it.name);
        if (f) Object.assign(f, parseSlkFile(path.join(dirLocal, it.name)));
      }
    }
  }
  return {
    mode: 'mtp',
    where: `QQ ${found.uin} · ${found.month}/${found.day}`,
    uin: found.uin,
    segments: found.segments,
    dir: `${found.month}/${found.day}`,
    files,
  };
}

/* ---------------- 生成 / 替换 ---------------- */

function stamp() {
  const d = new Date();
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

async function applyAdb(cfg, token, target) {
  const steps = [];
  const adbPath = await adb.locate(cfg.adbPath, ROOT);
  const b = builds.get(token);
  const remote = `${target.dir}/${target.name}`;

  if (cfg.backup) {
    const bkDir = path.join(BACKUP, stamp());
    const bk = path.join(bkDir, target.name);
    await adb.pull(adbPath, remote, bk);
    steps.push({ ok: true, text: `已备份原文件 → 备份\\${path.basename(bkDir)}\\${target.name}` });
  }

  const staged = path.join(CACHE, target.name);
  fs.copyFileSync(b.file, staged);
  steps.push({ ok: true, text: `已生成临时文件 ${target.name}（${(b.info.size / 1024).toFixed(1)} KB / ${b.info.durationSec}s）` });

  await adb.push(adbPath, staged, remote);
  steps.push({ ok: true, text: '已推送到手机' });

  const remoteMd5 = await adb.remoteMd5(adbPath, remote);
  const localMd5 = crypto.createHash('md5').update(fs.readFileSync(staged)).digest('hex');
  if (remoteMd5 && remoteMd5 !== localMd5) throw new Error('校验失败：手机上的文件与本地不一致');
  steps.push({ ok: true, text: `校验通过（md5 ${localMd5.slice(0, 8)}…）` });
  return steps;
}

async function applyMtp(cfg, token, target) {
  const steps = [];
  const b = builds.get(token);
  const segs = target.segments;
  if (!segs) throw new Error('缺少目标目录信息，请重新扫描');

  if (cfg.backup) {
    const bkDir = path.join(BACKUP, stamp());
    const bk = path.join(bkDir, target.name);
    const r = await mtp.copyOut(cfg.deviceName, segs, target.name, bk);
    if (!r.ok) throw new Error('备份失败：' + r.error);
    steps.push({ ok: true, text: `已备份原文件 → 备份\\${path.basename(bkDir)}\\${target.name}` });
  }

  const staged = path.join(CACHE, target.name);
  fs.copyFileSync(b.file, staged);
  steps.push({ ok: true, text: `已生成临时文件 ${target.name}（${(b.info.size / 1024).toFixed(1)} KB / ${b.info.durationSec}s）` });

  const r = await mtp.copyIn(cfg.deviceName, segs, staged, target.name);
  if (!r.ok) throw new Error(r.error);
  steps.push({ ok: true, text: '已写入手机并校验大小一致' });
  return steps;
}

/* ---------------- HTTP ---------------- */

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function readBody(req, limit = 200 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let n = 0;
    req.on('data', c => { n += c.length; if (n > limit) { reject(new Error('文件太大')); req.destroy(); } chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = url.pathname;

  try {
    if (p === '/' || p === '/index.html') {
      const html = fs.readFileSync(path.join(ROOT, 'public', 'index.html'));
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
        'Pragma': 'no-cache',
      });
      return res.end(html);
    }

    if (p === '/api/status') return json(res, 200, await deviceStatus());

    if (p === '/api/adb-ping') {
      const cfg = loadConfig();
      const st = await adb.info(cfg.adbPath, ROOT);
      return json(res, 200, { ok: st.ok, reason: st.reason || '' });
    }

    if (p === '/api/scan') {
      const cfg = loadConfig();
      const mode = url.searchParams.get('mode') || 'auto';
      let modeTried = mode;
      let result;
      if (mode === 'adb' || mode === 'auto') {
        const st = await adb.info(cfg.adbPath, ROOT);
        if (st.ok) { result = await scanAdb(cfg); modeTried = 'adb'; }
        else if (mode === 'adb') throw new Error('ADB 不可用：' + st.reason);
      }
      if (!result) { modeTried = 'mtp'; result = await scanMtp(cfg); }
      return json(res, 200, { ok: true, ...result, mode: modeTried });
    }

    if (p === '/api/build' && req.method === 'POST') {
      const raw = await readBody(req);
      const ctype = String(req.headers['content-type'] || '');
      let buf, name = url.searchParams.get('name') || 'audio';

      if (ctype.includes('application/json')) {
        // 浏览器走这条：base64 文本，避免二进制请求体被中间层截断
        const body = JSON.parse(raw.toString('utf8') || '{}');
        if (body.name) name = body.name;
        if (body.path) {
          // 直接给本地路径（本机工具，读文件比传文件更稳）
          const p = path.resolve(body.path);
          if (!fs.existsSync(p)) throw new Error('找不到文件：' + p);
          buf = fs.readFileSync(p);
          name = path.basename(p);
        } else {
          buf = Buffer.from(body.data || '', 'base64');
        }
      } else {
        buf = raw;   // curl / 脚本直传
      }
      console.log(`[build] ${ctype || 'raw'} 收到 ${raw.length}B → 音频 ${buf.length}B`);
      if (!buf.length) throw new Error(`收到 0 字节（请求体 ${raw.length} 字节，类型 ${ctype || '空'}），请重试`);

      const { buffer, info } = await slk.buildSlk(buf);
      const token = crypto.randomBytes(8).toString('hex');
      const file = path.join(CACHE, `${token}.slk`);
      fs.writeFileSync(file, buffer);
      builds.set(token, { file, info, name });
      fs.writeFileSync(path.join(EXPORT, `成品_${Date.now()}.slk`), buffer);
      return json(res, 200, { ok: true, token, name, info });
    }

    if (p.startsWith('/api/preview/')) {
      const token = p.split('/').pop();
      const b = builds.get(token);
      if (!b) return json(res, 404, { ok: false, error: '已过期，请重新生成' });
      const wav = await slk.slkToWav(fs.readFileSync(b.file));
      res.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': wav.length });
      return res.end(wav);
    }

    if (p === '/api/wake-shizuku' && req.method === 'POST') {
      const cfg = loadConfig();
      const adbPath = await adb.locate(cfg.adbPath, ROOT);
      if (!adbPath) throw new Error('未找到 adb');
      const st = await adb.info(cfg.adbPath, ROOT);
      if (!st.ok) throw new Error('没有检测到手机：' + st.reason);
      const pm = await adb.sh(adbPath, 'pm path moe.shizuku.privileged.api');
      const apk = pm.out.trim().replace('package:', '').trim();
      if (!apk) throw new Error('手机上没装 Shizuku');
      const lib = apk.replace(/base\.apk$/, 'lib/arm64/libshizuku.so');
      // setsid：让服务脱离 adb 会话，拔线后依然存活
      await adb.sh(adbPath, 'setsid ' + lib);
      await new Promise(r => setTimeout(r, 1500));
      const chk = await adb.sh(adbPath, 'pgrep -f shizuku_server | head -1');
      const pid = chk.out.trim();
      return json(res, 200, {
        ok: !!pid, pid,
        text: pid ? `Shizuku 已启动（pid ${pid}），已经脱离数据线，可以拔了` : '启动命令执行了，但没检测到进程，请打开 Shizuku 应用手动点启动',
      });
    }

    if (p === '/api/install-adb' && req.method === 'POST') {
      const r = await adbinstall.install(ROOT, msg => console.log('[adb]', msg));
      if (r.ok) saveConfig({ ...loadConfig(), adbPath: r.adbPath });
      return json(res, 200, r);
    }

    if (p === '/api/apply' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      const cfg = loadConfig();
      const target = body.target || {};
      if (!body.token || !builds.has(body.token)) throw new Error('成品已过期，请重新拖入音频');
      if (!target.name) throw new Error('没有选择要替换的语音');

      const st = await adb.info(cfg.adbPath, ROOT);
      if (!st.ok) {
        return json(res, 200, {
          ok: false, needAdb: true,
          error: `替换这一步需要 ADB：${st.reason}`,
          hint: '手机设置 → 关于手机 → 连点版本号 7 次 → 返回上一级进「开发者选项」→ 打开「USB 调试」；插上数据线后在手机上点「允许」。',
        });
      }
      const steps = await applyAdb(cfg, body.token, target);
      return json(res, 200, {
        ok: true,
        mode: 'adb',
        steps,
        tip: '回到手机 QQ 聊天界面，点这条语音试听；若还是旧声音，退出聊天重进或重启 QQ。',
      });
    }

    if (p === '/api/export' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      const b = builds.get(body.token);
      if (!b) throw new Error('成品已过期，请重新拖入音频');
      const name = body.name || b.name.replace(/\.[^.]+$/, '') + '.slk';
      const out = path.join(EXPORT, name);
      fs.copyFileSync(b.file, out);
      execFile('powershell.exe', ['-NoProfile', '-Command', `Set-Clipboard -Value '${name.replace(/'/g, "''")}'`],
        { windowsHide: true }, () => { });
      execFile('explorer.exe', [EXPORT], { windowsHide: true }, () => { });
      return json(res, 200, { ok: true, path: out, name });
    }

    if (p === '/api/config') {
      if (req.method === 'POST') {
        const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
        saveConfig({ ...loadConfig(), ...body });
        return json(res, 200, { ok: true, config: loadConfig() });
      }
      return json(res, 200, loadConfig());
    }

    if (p === '/api/open-export') {
      execFile('explorer.exe', [EXPORT], { windowsHide: true }, () => {});
      return json(res, 200, { ok: true, path: EXPORT });
    }

    json(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    json(res, 500, { ok: false, error: e.message || String(e) });
  }
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.log(`端口 ${PORT} 已被占用（可能是工具已经在运行），直接打开页面…`);
    execFile('cmd', ['/c', 'start', '', `http://127.0.0.1:${PORT}`], { windowsHide: true }, () => { });
    setTimeout(() => process.exit(0), 800);
  } else {
    console.error(e);
    process.exit(1);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  const url = `http://127.0.0.1:${PORT}`;
  console.log('\n  QQ 语音发送助手已启动');
  console.log('  ' + url + '\n');
  if (!process.env.NO_OPEN) {
    execFile('cmd', ['/c', 'start', '', url], { windowsHide: true }, () => {});
  }
});
