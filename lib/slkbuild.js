'use strict';
/**
 * slkbuild.js —— 任意音频 → 手机 QQ 可播放的 slk
 *
 * 关键格式约束（由反推原版样本得出，详见 ../QQ语音SLK格式分析报告.md）：
 *   0x02 | "#!SILK_V3" | [uint16 LE 帧长][20ms 帧] × N   ← 帧链必须正好结束在 EOF
 *   不能缺 0x02，不能带 FF FF 结束符，不能有填充字节。
 */
const silk = require('silk-wasm');

const TARGET_RATE = 24000;   // QQ 手机端按 24kHz 播放
const SILK_HEADER = '#!SILK_V3';

/** 解码任意受支持格式（wav/mp3/flac/ogg/opus/qoa…）→ {channels:Float32Array[], sampleRate} */
async function decodeAny(buf) {
  let decodeAudio;
  try {
    ({ default: decodeAudio } = await import('audio-decode'));
  } catch (e) {
    throw new Error('音频解码库加载失败：' + e.message);
  }
  const ab = await decodeAudio(buf);
  const channels = ab.channelData || [];
  if (!channels.length) throw new Error('这个文件里没有可用的音频数据');
  return { channels, sampleRate: ab.sampleRate };
}

/** 多声道下混为单声道 */
function toMono(channels) {
  if (channels.length === 1) return Float32Array.from(channels[0]);
  const n = channels[0].length;
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let s = 0;
    for (let c = 0; c < channels.length; c++) s += channels[c][i];
    out[i] = s / channels.length;
  }
  return out;
}

/** 归一化窗口 sinc 重采样（足够好的语音质量，且不依赖 ffmpeg） */
function resample(input, srcRate, dstRate) {
  if (srcRate === dstRate) return input;
  const ratio = dstRate / srcRate;
  const outLen = Math.max(1, Math.round(input.length * ratio));
  const out = new Float32Array(outLen);
  const TAPS = 24;
  const cut = Math.min(1, ratio) * 0.92;          // 抗混叠低通
  const scale = Math.PI * cut;
  for (let i = 0; i < outLen; i++) {
    const center = i / ratio;
    const base = Math.floor(center);
    let acc = 0, wsum = 0;
    for (let j = -TAPS; j <= TAPS; j++) {
      const idx = base + j;
      if (idx < 0 || idx >= input.length) continue;
      const d = center - idx;
      const x = d * cut;
      const sinc = x === 0 ? 1 : Math.sin(scale * d) / (scale * d);
      const w = 0.5 + 0.5 * Math.cos(Math.PI * d / TAPS);
      const k = sinc * w;
      acc += input[idx] * k;
      wsum += k;
    }
    out[i] = wsum !== 0 ? acc / wsum : 0;
  }
  return out;
}

/** 峰值保护，避免编码前削波 */
function peakLimit(x, ceiling = 0.95) {
  let peak = 0;
  for (let i = 0; i < x.length; i++) { const a = Math.abs(x[i]); if (a > peak) peak = a; }
  if (peak <= ceiling || peak === 0) return { data: x, gain: 1 };
  const g = ceiling / peak;
  const out = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) out[i] = x[i] * g;
  return { data: out, gain: g };
}

/** Float32 单声道 → 16bit PCM WAV */
function toWav16(mono, rate) {
  const n = mono.length;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 2, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) {
    const v = Math.max(-1, Math.min(1, mono[i]));
    buf.writeInt16LE(Math.round(v * 32767), 44 + i * 2);
  }
  return buf;
}

/** 解析 slk 容器结构 */
function parseSlk(buf) {
  const d = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  const hasTag = d[0] === 0x02;
  const header = d.subarray(hasTag ? 1 : 0, (hasTag ? 1 : 0) + 9).toString('latin1');
  const frames = [];
  let i = (hasTag ? 1 : 0) + 9;
  let broken = null;
  while (i + 2 <= d.length) {
    const n = d.readUInt16LE(i);
    i += 2;
    if (n === 0) { broken = '空帧'; break; }
    if (n === 0xffff) { broken = 'FF FF 结束符'; break; }
    if (i + n > d.length) { broken = '帧长越界'; break; }
    frames.push(n);
    i += n;
  }
  return {
    size: d.length,
    hasTag,
    header,
    headerOk: header === SILK_HEADER,
    frames: frames.length,
    durationMs: frames.length * 20,
    endAtEof: i === d.length,
    broken,
    minFrame: frames.length ? Math.min(...frames) : 0,
    avgFrame: frames.length ? frames.reduce((a, b) => a + b, 0) / frames.length : 0,
    maxFrame: frames.length ? Math.max(...frames) : 0,
    bitrate: frames.length ? frames.reduce((a, b) => a + b, 0) * 8 / (frames.length * 0.02) / 1000 : 0,
    tailHex: d.subarray(Math.max(0, d.length - 6)).toString('hex').replace(/(..)/g, '$1 ').trim(),
    valid: hasTag && header === SILK_HEADER && i === d.length && !broken,
  };
}

/**
 * 主流程：音频 buffer → QQ 兼容 slk
 * @returns {Promise<{buffer:Buffer, info:object}>}
 */
async function buildSlk(inputBuf) {
  const t0 = Date.now();
  const { channels, sampleRate } = await decodeAny(inputBuf);
  const srcChannels = channels.length;
  let mono = toMono(channels);
  const srcDuration = mono.length / sampleRate;

  mono = resample(mono, sampleRate, TARGET_RATE);
  const { data, gain } = peakLimit(mono, 0.95);

  const wav = toWav16(data, TARGET_RATE);
  const enc = await silk.encode(wav, 0);
  const buffer = Buffer.from(enc.data);

  const info = parseSlk(buffer);
  if (!info.valid) throw new Error('编码结果结构异常：' + (info.broken || '帧链未对齐'));

  return {
    buffer,
    info: {
      ...info,
      sourceSampleRate: sampleRate,
      sourceChannels: srcChannels,
      sourceDuration: +srcDuration.toFixed(3),
      durationSec: +(info.durationMs / 1000).toFixed(3),
      gainApplied: +gain.toFixed(3),
      encodeMs: Date.now() - t0,
    },
  };
}

/** 解码 slk 成可试听的 WAV（用于界面预览） */
async function slkToWav(buf) {
  const r = await silk.decode(Buffer.isBuffer(buf) ? buf : Buffer.from(buf), TARGET_RATE);
  const pcm = Buffer.from(r.data);
  const n = Math.floor(pcm.length / 2);
  const mono = new Float32Array(n);
  for (let i = 0; i < n; i++) mono[i] = pcm.readInt16LE(i * 2) / 32768;
  return toWav16(mono, TARGET_RATE);
}

module.exports = { buildSlk, parseSlk, slkToWav, TARGET_RATE };
