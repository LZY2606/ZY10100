'use strict';

const G = {
  imageId: null,
  image: null,
  analysis: null,
  actor: localStorage.getItem('gsb_actor') || ('user-' + Math.random().toString(36).slice(2, 6)),
  layer: 'consensus',
  tool: 'pan',
  view: { z: 0, x: 0, y: 0 },
  baseVersion: 0,
  polygon: [],
  tileMinZoom: 0,
  tileMaxZoom: 0,
  imgW: 0,
  imgH: 0,
};

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

async function api(path, opts = {}) {
  const res = await fetch(path, {
    method: opts.method || 'GET',
    headers: { 'X-Actor': G.actor, ...(opts.json ? { 'Content-Type': 'application/json' } : {}) },
    body: opts.json ? JSON.stringify(opts.json) : opts.body,
  });
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) {
    const data = await res.json();
    if (!res.ok || data.error) {
      const err = new Error(data.message || ('HTTP ' + res.status));
      err.payload = data;
      throw err;
    }
    return data;
  }
  if (!res.ok) {
    throw new Error('HTTP ' + res.status);
  }
  return res;
}

function toast(msg, kind = '') {
  const el = $('#toast');
  el.textContent = msg;
  el.className = kind || '';
  el.style.display = 'block';
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.style.display = 'none'; }, kind === 'err' ? 7000 : 3000);
}

function formMsg(form, msg, kind) {
  const el = form.querySelector('.formMsg');
  if (!el) return;
  el.textContent = msg || '';
  el.className = 'formMsg ' + (kind || '');
}

// SHA-256 over raw bytes (SubtleCrypto); fingerprint computed client-side first.
async function sha256Hex(buffer) {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function readFile(file) {
  return new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(new Uint8Array(fr.result));
    fr.onerror = () => reject(fr.error);
    fr.readAsArrayBuffer(file);
  });
}

async function jsonField(meta, file) {
  const fd = new FormData();
  fd.append('meta', JSON.stringify(meta));
  fd.append('file', file);
  return fd;
}

// ---- Synthetic demo mask generation (MRLE1 encoder mirrored from Java) ----
function writeUvarint(arr, value) {
  while (value >= 0x80) {
    arr.push((value & 0x7f) | 0x80);
    value = value >>> 7;
  }
  arr.push(value);
}

function strBytes(s) { return Array.from(new TextEncoder().encode(s)); }

function u32be(arr, v) {
  arr.push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
}

async function deflateRaw(payloadBytes) {
  const cs = new CompressionStream('deflate');
  const stream = new Blob([payloadBytes]).stream().pipeThrough(cs);
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function encodeMrle(width, height, labels, confidence, boundary) {
  const n = width * height;
  let hasConf = false;
  if (confidence) for (const c of confidence) if (c !== 0) { hasConf = true; break; }
  let hasBoundary = false;
  if (boundary) for (const b of boundary) if (b) { hasBoundary = true; break; }
  const flags = (hasConf ? 1 : 0) | (hasBoundary ? 2 : 0);

  const payload = [];
  let i = 0;
  while (i < n) {
    const value = labels[i];
    let end = i + 1;
    while (end < n && labels[end] === value) end++;
    writeUvarint(payload, value + 1);
    writeUvarint(payload, end - i);
    i = end;
  }
  writeUvarint(payload, 0);
  if (hasConf) {
    const dv = new DataView(new ArrayBuffer(n * 4));
    for (let k = 0; k < n; k++) dv.setFloat32(k * 4, confidence[k], false);
    for (const b of new Uint8Array(dv.buffer)) payload.push(b);
  }
  if (hasBoundary) {
    const packed = new Uint8Array(Math.ceil(n / 8));
    for (let k = 0; k < n; k++) if (boundary[k]) packed[k >> 3] |= 0x80 >>> (k & 7);
    for (const b of packed) payload.push(b);
  }
  const deflated = await deflateRaw(new Uint8Array(payload));
  const out = [];
  for (const b of strBytes('MRL1')) out.push(b);
  u32be(out, width);
  u32be(out, height);
  u32be(out, flags);
  for (const b of deflated) out.push(b);
  return new Uint8Array(out);
}

// Palette PNG for a label grid (uses canvas + toBlob).
async function encodeMaskPng(width, height, labels) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(width, height);
  for (let i = 0; i < labels.length; i++) {
    const v = labels[i];
    const h = ((v * 2654435761) >>> 0) || v;
    img.data[i * 4] = h & 0xc0;
    img.data[i * 4 + 1] = (h >>> 8) & 0xa0;
    img.data[i * 4 + 2] = (h >>> 16) & 0xe0;
    img.data[i * 4 + 3] = v ? 255 : 0;
  }
  ctx.putImageData(img, 0, 0);
  return new Promise((resolve) => canvas.toBlob((b) => resolve(b), 'image/png'));
}

// Simple base image PNG from canvas.
function canvasToPng(canvas) {
  return new Promise((resolve) => canvas.toBlob((b) => resolve(b), 'image/png'));
}

function polygonArea(points) {
  let a = 0;
  for (let i = 0; i < points.length; i++) {
    const j = (i + 1) % points.length;
    a += points[i][0] * points[j][1] - points[j][0] * points[i][1];
  }
  return Math.abs(a) / 2;
}
