'use strict';
/** adbinstall.js —— 一键下载并解压 platform-tools（含 adb.exe） */
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');

const MIRRORS = [
  'https://googledownloads.cn/android/repository/platform-tools-latest-windows.zip',
  'https://dl.google.com/android/repository/platform-tools-latest-windows.zip',
  'https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools-latest-windows.zip',
];

function unzip(zip, dest) {
  return new Promise((resolve, reject) => {
    execFile('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command',
      `Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force`,
    ], { windowsHide: true, timeout: 180000 }, (err, so, se) => {
      if (err) return reject(new Error((se || err.message).trim().slice(0, 300)));
      resolve();
    });
  });
}

async function download(url, out) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const total = Number(res.headers.get('content-length') || 0);
  const chunks = [];
  let got = 0;
  for await (const c of res.body) {
    chunks.push(c);
    got += c.length;
  }
  if (total && got < total * 0.95) throw new Error('下载不完整');
  fs.writeFileSync(out, Buffer.concat(chunks));
  return got;
}

/** @returns {Promise<{ok:boolean, adbPath?:string, sizeMB?:number, source?:string, error?:string}>} */
async function install(toolDir, onProgress = () => {}) {
  const zip = path.join(toolDir, 'platform-tools.zip');
  const target = path.join(toolDir, 'platform-tools');
  let lastErr = '';
  for (const url of MIRRORS) {
    try {
      onProgress(`正在从 ${new URL(url).host} 下载 platform-tools…`);
      const bytes = await download(url, zip);
      onProgress(`下载完成 ${(bytes / 1048576).toFixed(1)} MB，正在解压…`);
      await unzip(zip, toolDir);
      const adbExe = path.join(target, 'adb.exe');
      if (!fs.existsSync(adbExe)) throw new Error('解压后没有找到 adb.exe');
      fs.unlinkSync(zip);
      return { ok: true, adbPath: adbExe, sizeMB: +(bytes / 1048576).toFixed(1), source: new URL(url).host };
    } catch (e) {
      lastErr = e.message;
      try { fs.unlinkSync(zip); } catch { }
    }
  }
  return { ok: false, error: lastErr || '下载失败' };
}

module.exports = { install };
