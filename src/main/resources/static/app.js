'use strict';

$('#actor').value = G.actor;
$('#actor').addEventListener('change', () => {
  G.actor = $('#actor').value || 'anonymous';
  localStorage.setItem('gsb_actor', G.actor);
});

// ---------- image list ----------
async function refreshImageList(selectId) {
  const data = await api('/api/images');
  const ul = $('#imageList');
  ul.innerHTML = '';
  for (const img of data.images) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="row"><strong>${escapeHtml(img.name)}</strong>
      <span class="sub">v${img.version}</span></div>
      <div class="sub">${img.imageId}${img.created ? '' : '（未上传底图）'}</div>`;
    li.style.cursor = 'pointer';
    li.addEventListener('click', () => openImage(img.imageId));
    if (img.imageId === G.imageId) li.classList.add('active');
    ul.appendChild(li);
  }
}

async function openImage(id) {
  G.imageId = id;
  G.polygon = [];
  const data = await api('/api/images/' + id);
  if (!data.created) {
    toast('该工作区尚未创建底图', 'err');
    return;
  }
  G.image = data;
  G.baseVersion = data.version;
  G.imgW = data.width;
  G.imgH = data.height;
  G.tileMinZoom = Math.max(0, Math.ceil(Math.log2(Math.max(data.width, data.height) / 256)));
  G.tileMaxZoom = G.tileMinZoom + 2;
  $('#sourcesSection').classList.remove('hidden');
  $('#canvasSection').classList.remove('hidden');
  $('#rightPanel').classList.remove('hidden');
  $('#ruleVersion').textContent = data.ruleVersion;
  $('#dataVersion').textContent = 'v' + data.version;
  $('#thresholdRange').value = data.confidenceThreshold;
  $('#thresholdValue').textContent = Number(data.confidenceThreshold).toFixed(2);
  populateForms(data);
  renderSources(data);
  renderMappings(data);
  renderDecisions(data);
  renderReleases(data);
  fitView();
  await refreshAnalysis();
  refreshImageList();
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------- create image ----------
$('#createImageForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  formMsg(f, '计算浏览器侧指纹…', '');
  try {
    const file = f.file.files[0];
    if (!file) throw new Error('请选择 PNG');
    const bytes = await readFile(file);
    const fingerprint = await sha256Hex(bytes);
    const meta = {
      name: f.name.value || file.name,
      width: Number(f.width.value),
      height: Number(f.height.value),
      orientation: Number(f.orientation.value || 0),
      spacingX: Number(f.spacing.value || 1),
      spacingY: Number(f.spacing.value || 1),
      fingerprint,
    };
    formMsg(f, '上传中，服务端会再次校验指纹/尺寸/方向/间距…', '');
    const created = await api('/api/images', {
      method: 'POST', json: { imageId: f.imageId.value || undefined, name: meta.name },
    });
    const fd = new FormData();
    fd.append('meta', JSON.stringify(meta));
    fd.append('file', file);
    await api('/api/images/' + created.imageId + '/upload', { method: 'POST', body: fd });
    formMsg(f, '已创建 ' + created.imageId, 'ok');
    await openImage(created.imageId);
  } catch (err) {
    formMsg(f, formatError(err), 'err');
  }
});

$('#importForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    const fd = new FormData();
    if (f.imageId.value) fd.append('imageId', f.imageId.value);
    fd.append('file', f.file.files[0]);
    const r = await api('/api/import', { method: 'POST', body: fd });
    formMsg(f, r.ok
      ? `导入成功 ${r.imageId}：区域面积与边界哈希一致（事件 v${r.logSeq}）`
      : `导入完成但存在不一致：\n${r.mismatches.join('\n')}`, r.ok ? 'ok' : 'err');
    await refreshImageList();
    if (r.ok) await openImage(r.imageId);
  } catch (err) {
    formMsg(f, formatError(err), 'err');
  }
});

// ---------- vocab / source / version forms ----------
$('#vocabForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    const categories = f.categories.value.split('\n').map((line) => {
      const [id, name] = line.split(',').map((s) => s.trim());
      return { id, name, color: colorForName(name || id) };
    }).filter((c) => c.id && c.name);
    await api(`/api/images/${G.imageId}/vocabularies`, {
      method: 'POST', json: {
        vocabularyId: f.vocabularyId.value, version: f.version.value,
        name: f.vocabularyId.value, categories,
      },
    });
    formMsg(f, '词表已导入（原始证据不可变）', 'ok');
    await reloadImage();
  } catch (err) { formMsg(f, formatError(err), 'err'); }
});

$('#sourceForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  try {
    await api(`/api/images/${G.imageId}/sources`, { method: 'POST', json: {
      sourceId: f.sourceId.value || undefined, name: f.name.value || f.sourceId.value,
      kind: f.kind.value,
    }});
    formMsg(f, '来源已注册', 'ok');
    await reloadImage();
  } catch (err) { formMsg(f, formatError(err), 'err'); }
});

$('#syntheticToggle').addEventListener('change', (e) => {
  $('#versionForm').file.disabled = e.target.checked;
});

$('#versionForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  formMsg(f, '生成派生数据并计算指纹…', '');
  try {
    const w = Number(f.nativeWidth.value);
    const h = Number(f.nativeHeight.value);
    let blob;
    let fmt = f.maskFormat.value;
    if (f.synthetic.checked) {
      const seed = (f.sourceId.value + f.vocabVersion.value + G.actor).split('')
        .reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
      const { labels, confidence } = demoRaster(w, h, seed);
      blob = fmt === 'png'
        ? await encodeMaskPng(w, h, labels)
        : new Blob([await encodeMrle(w, h, labels, confidence, null)]);
    } else {
      blob = f.file.files[0];
      if (!blob) throw new Error('请选择掩膜文件或勾选演示栅格');
    }
    const bytes = new Uint8Array(await blob.arrayBuffer());
    const rawFingerprint = await sha256Hex(bytes);
    const fd = new FormData();
    fd.append('meta', JSON.stringify({
      sourceId: f.sourceId.value,
      vocabularyId: f.vocabId.value,
      vocabularyVersion: f.vocabVersion.value,
      nativeWidth: w, nativeHeight: h,
      orientation: Number(f.orientation.value || 0),
      spacingX: Number(f.spacingX.value || 1),
      spacingY: Number(f.spacingX.value || 1),
      maskFormat: fmt, rawFingerprint,
    }));
    fd.append('file', new File([blob], 'mask.' + (fmt === 'png' ? 'png' : 'mrle')));
    const r = await api(`/api/images/${G.imageId}/sources/${f.sourceId.value}/versions`,
      { method: 'POST', body: fd });
    const v = r.version;
    formMsg(f,
      `版本 ${v.versionId.slice(0, 18)}… 已导入\n` +
      (v.resampled ? `已重采样：边界像素 ${v.boundaryPixels}，越界 ${v.outOfBoundsPixels}\n` : '几何完全对齐，无需重采样\n') +
      `派生指纹 ${v.derivedFingerprint.slice(0, 16)}…（${'pairwise-gsb-v1'}）`,
      v.outOfBoundsPixels > 0 ? 'err' : 'ok');
    await reloadImage();
  } catch (err) { formMsg(f, formatError(err), 'err'); }
});

function demoRaster(w, h, seed) {
  const n = w * h;
  const labels = new Int32Array(n);
  const confidence = new Float32Array(n);
  let s = seed || 1;
  const rand = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
  const cx = w * (0.3 + rand() * 0.4);
  const cy = h * (0.3 + rand() * 0.4);
  const r1 = Math.min(w, h) * 0.22;
  const r2 = Math.min(w, h) * 0.12;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      const d1 = Math.hypot(x - cx, y - cy);
      const d2 = Math.hypot(x - w * 0.68, y - h * 0.62);
      if (d1 < r1) { labels[i] = 1; confidence[i] = 0.95; }
      else if (d2 < r2) { labels[i] = 2; confidence[i] = 0.8; }
      else if (rand() < 0.015) { labels[i] = 2; confidence[i] = 0.35 + rand() * 0.3; }
    }
  }
  return { labels, confidence };
}

function colorForName(name) {
  let h = 0;
  for (const c of name) h = (h * 31 + c.charCodeAt(0)) | 0;
  return '#' + ((h & 0xffffff) | 0x101010).toString(16).padStart(6, '0');
}

// ---------- rendering of left-panel entities ----------
function populateForms(data) {
  const srcSel = $('#versionForm').sourceId;
  srcSel.innerHTML = '';
  for (const s of data.sources) {
    srcSel.add(new Option(`${s.name} (${s.sourceId.slice(0, 10)})`, s.sourceId));
  }
  const vid = $('#versionForm').vocabId;
  const vv = $('#versionForm').vocabVersion;
  vid.innerHTML = '';
  vv.innerHTML = '';
  for (const v of data.vocabularies) {
    vid.add(new Option(`${v.name}`, v.vocabularyId));
    vv.add(new Option(v.version, v.version));
  }
  $('#versionForm').nativeWidth.value = data.width;
  $('#versionForm').nativeHeight.value = data.height;
  $('#versionForm').spacingX.value = data.spacingX;
  $('#versionForm').orientation.value = data.orientation;

  const accept = $('#acceptSource');
  accept.innerHTML = '';
  for (const s of data.sources) accept.add(new Option(s.name, s.sourceId));
  const correct = $('#correctLabel');
  correct.innerHTML = '';
  for (const voc of data.vocabularies) {
    for (const c of voc.categories) {
      correct.add(new Option(`${c.name} (${voc.vocabularyId}@${voc.version}#${c.id})`,
        c.id));
    }
  }
  const layerButtons = $('#sourceLayerButtons');
  layerButtons.innerHTML = '';
  for (const s of data.sources) {
    if (s.activeVersionId) {
      const id = `source:${s.activeVersionId}`;
      const label = document.createElement('label');
      label.innerHTML = `<input type="radio" name="layer" value="${id}"> ${escapeHtml(s.name)}`;
      layerButtons.appendChild(label);
    }
  }
}

function renderSources(data) {
  const ul = $('#sourceList');
  ul.innerHTML = '';
  for (const s of data.sources) {
    const li = document.createElement('li');
    const active = s.versions.find((v) => v.versionId === s.activeVersionId);
    li.innerHTML = `<div class="row"><strong>${escapeHtml(s.name)}</strong>
      <span class="sub">${s.kind}</span></div>`;
    for (const v of s.versions) {
      const row = document.createElement('div');
      row.className = 'sub';
      const stale = active && v.versionId !== active.versionId;
      row.innerHTML = `${stale ? '⏳ 旧版本 ' : '● 当前 '}${escapeHtml(v.versionLabel)}
        · ${v.vocabularyId}@${v.vocabularyVersion}
        · ${v.resampled ? `重采样 边界=${v.boundaryPixels} 越界=${v.outOfBoundsPixels}` : '原生对齐'}
        <div>${(v.derivedFingerprint || '').slice(0, 16)}…</div>`;
      li.appendChild(row);
    }
    ul.appendChild(li);
  }
}

function renderMappings(data) {
  const ul = $('#mappingList');
  ul.innerHTML = '';
  if (!data.mappings.length) {
    ul.innerHTML = '<li class="sub">尚无映射建议；上传多个来源后会按相同类别名提出 PROPOSED 映射，必须确认后才能发布。</li>';
    return;
  }
  for (const g of data.mappings) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="row"><strong>${escapeHtml(g.canonicalName)}</strong>
      <span class="badge ${g.status}">${g.status}</span></div>
      <div class="sub">${g.refs.map((r) => `${r.vocabularyId}@${r.vocabularyVersion}#${r.categoryId} ${escapeHtml(r.categoryName)}`).join('<br>')}</div>`;
    if (g.status === 'PROPOSED') {
      const buttons = document.createElement('div');
      buttons.className = 'row';
      buttons.style.marginTop = '5px';
      buttons.innerHTML = '<button class="mini">确认一致</button><button class="mini">拒绝</button>';
      buttons.children[0].onclick = async () => {
        await api(`/api/images/${G.imageId}/mappings/${g.groupId}/confirm`, { method: 'POST' });
        await reloadImage();
      };
      buttons.children[1].onclick = async () => {
        await api(`/api/images/${G.imageId}/mappings/${g.groupId}/reject`, { method: 'POST' });
        await reloadImage();
      };
      li.appendChild(buttons);
    }
    ul.appendChild(li);
  }
}

function renderDecisions(data) {
  const ul = $('#decisionList');
  ul.innerHTML = '';
  for (const d of [...data.decisions].reverse()) {
    const li = document.createElement('li');
    const undone = d.undoneBy || d.kind.startsWith('UNDO_');
    li.className = undone ? 'strike' : '';
    li.innerHTML = `<div class="row"><strong>${d.kind}</strong>
      <span class="sub">${(d.actor || '').slice(0, 16)}</span></div>
      <div class="sub">区域 ${d.areaPixels} px · 基于 v${d.basedOnLogSeq} · ${d.createdAt || ''}
      ${d.stale ? '<span class="badge stale">来源已过期</span>' : ''}</div>`;
    if (!undone) {
      const btn = document.createElement('button');
      btn.className = 'mini';
      btn.textContent = '撤销（产生反向事件）';
      btn.onclick = async () => {
        await api(`/api/images/${G.imageId}/decisions/${d.decisionId}/undo`, { method: 'POST' });
        toast('已生成撤销反向事件');
        await reloadImage();
      };
      li.appendChild(btn);
    }
    ul.appendChild(li);
  }
}

function renderReleases(data) {
  const ul = $('#releaseList');
  ul.innerHTML = '';
  if (!data.releases.length) {
    ul.innerHTML = '<li class="sub">尚未发布。所有相关类别映射已确认且没有越界像素时才可生成。</li>';
    return;
  }
  for (const r of data.releases) {
    const li = document.createElement('li');
    li.innerHTML = `<strong>${r.releaseId}</strong>
      <div class="sub">v${r.logSeq} · 边界 ${r.consensusBoundaryPixels} · 越界 ${r.outOfBoundsPixels}</div>
      <div class="sub">${r.manifestHash.slice(0, 24)}…</div>`;
    ul.appendChild(li);
  }
}

// ---------- threshold / release / export ----------
$('#thresholdForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  await api(`/api/images/${G.imageId}/threshold`, {
    method: 'POST', json: { threshold: Number($('#thresholdRange').value) },
  });
  await reloadImage();
});
$('#thresholdRange').addEventListener('input', () => {
  $('#thresholdValue').textContent = Number($('#thresholdRange').value).toFixed(2);
});

$('#releaseBtn').addEventListener('click', async () => {
  try {
    const r = await api(`/api/images/${G.imageId}/release`, { method: 'POST' });
    toast('已发布 ' + r.release.releaseId.slice(0, 20), 'ok');
    await reloadImage();
  } catch (err) { showDiagnostic(err); }
});

$('#exportLink').addEventListener('click', (e) => {
  e.preventDefault();
  if (!G.imageId) return;
  window.location = `/api/images/${G.imageId}/export`;
});

async function reloadImage() {
  await openImage(G.imageId);
}

function formatError(err) {
  if (!err.payload) return err.message;
  const p = err.payload;
  return `[${p.code}] ${p.message}` + (p.details && p.details.length
    ? '\n— ' + p.details.join('\n— ') : '');
}

function showDiagnostic(err) {
  toast(formatError(err), 'err');
}

// ---------- analysis / regions ----------
async function refreshAnalysis() {
  G.analysis = await api(`/api/images/${G.imageId}/analysis`);
  const s = G.analysis.summary;
  $('#summary').innerHTML =
    `<span class="badge AGREE">一致 ${s.agreePixels}</span>
     <span class="badge DISAGREE">分歧 ${s.disagreePixels}</span>
     <span class="badge UNCOVERED">未覆盖 ${s.uncoveredPixels}</span>
     重采样边界 ${s.boundaryPixels} · 越界 ${s.outOfBoundsPixels}`;
  const ul = $('#regionList');
  ul.innerHTML = '';
  for (const r of G.analysis.regions) {
    const li = document.createElement('li');
    li.innerHTML = `<div class="row"><span class="badge ${r.kind}">${r.kind}</span>
      <span class="sub">${r.pixels} px · 边界 ${r.boundaryPixels}</span></div>
      <div class="sub">${r.boundaryHash.slice(0, 16)}…</div>`;
    li.style.cursor = 'pointer';
    li.addEventListener('click', () => focusRegion(r));
    li.dataset.region = r.id;
    ul.appendChild(li);
  }
  draw();
}

function focusRegion(r) {
  const ring = r.rings[0];
  if (!ring) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const [x, y] of ring) {
    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
  }
  G.polygon = ring.map(([x, y]) => [x, y]);
  G.tool = 'draw';
  $$('input[name=tool]').forEach((el) => { el.checked = el.value === 'draw'; });
  $('#actionGroup').classList.remove('hidden');
  draw();
}

// ---------- canvas: tiled slippy view + polygon drawing ----------
const canvas = $('#map');
const ctx = canvas.getContext('2d');
const tileCache = new Map();
let loading = 0;

function resizeCanvas() {
  const dpr = window.devicePixelRatio || 1;
  const rect = $('#viewport').getBoundingClientRect();
  canvas.width = Math.round(rect.width * dpr);
  canvas.height = Math.round(rect.height * dpr);
  canvas.style.width = rect.width + 'px';
  canvas.style.height = rect.height + 'px';
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  draw();
}
window.addEventListener('resize', resizeCanvas);

function levelParams() {
  const span = Math.max(G.imgW, G.imgH);
  const tilesAcross = Math.pow(2, G.view.z - G.tileMinZoom);
  const scale = 256 * tilesAcross / span;
  return { scale, imgWpx: G.imgW * scale, imgHpx: G.imgH * scale };
}

function imageToScreen(x, y) {
  const { scale } = levelParams();
  return [x * scale + G.view.x, y * scale + G.view.y];
}

function screenToImage(x, y) {
  const { scale } = levelParams();
  return [(x - G.view.x) / scale, (y - G.view.y) / scale];
}

function fitView() {
  G.view.z = G.tileMinZoom;
  requestAnimationFrame(() => {
    const rect = $('#viewport').getBoundingClientRect();
    const { imgWpx, imgHpx } = levelParams();
    G.view.x = (rect.width - imgWpx) / 2;
    G.view.y = (rect.height - imgHpx) / 2;
    $('#zoomLabel').textContent = 'z' + G.view.z;
    draw();
  });
}

function tileUrl(layer, z, x, y) {
  return `/api/images/${G.imageId}/tiles/${encodeURIComponent(layer)}/${z}/${x}/${y}.png`;
}

function loadTile(layer, z, x, y) {
  const key = `${layer}/${z}/${x}/${y}`;
  if (tileCache.has(key)) return Promise.resolve(tileCache.get(key));
  return fetch(tileUrl(layer, z, x, y)).then(async (r) => {
    const blob = await r.blob();
    const bmp = await createImageBitmap(blob);
    tileCache.set(key, bmp);
    return bmp;
  });
}

async function draw() {
  if (!G.image) return;
  const rect = $('#viewport').getBoundingClientRect();
  ctx.clearRect(0, 0, rect.width, rect.height);
  const { scale, imgWpx, imgHpx } = levelParams();
  const tilesAcross = Math.pow(2, G.view.z - G.tileMinZoom);
  const cols = Math.ceil(G.imgW * scale / 256);
  const rows = Math.ceil(G.imgH * scale / 256);
  const x0 = Math.max(0, Math.floor(-G.view.x / 256));
  const y0 = Math.max(0, Math.floor(-G.view.y / 256));
  const x1 = Math.min(tilesAcross - 1, Math.ceil((rect.width - G.view.x) / 256));
  const y1 = Math.min(tilesAcross - 1, Math.ceil((rect.height - G.view.y) / 256));
  for (let ty = y0; ty <= y1; ty++) {
    for (let tx = x0; tx <= x1; tx++) {
      try {
        const img = await loadTile(G.layer, G.view.z, tx, ty);
        ctx.drawImage(img, G.view.x + tx * 256, G.view.y + ty * 256);
      } catch (e) { /* tile drawn on next pass */ }
    }
  }
  ctx.strokeStyle = '#4f9cff';
  ctx.lineWidth = 1;
  ctx.strokeRect(G.view.x, G.view.y, imgWpx, imgHpx);
  drawRegions(scale);
  drawPolygon(scale);
}

function drawRegions(scale) {
  if (!G.analysis || G.layer === 'base') return;
  for (const r of G.analysis.regions) {
    ctx.beginPath();
    ctx.lineWidth = 1;
    for (const ring of r.rings) {
      ring.forEach(([x, y], i) => {
        const [sx, sy] = imageToScreen(x, y);
        if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy);
      });
    }
    ctx.strokeStyle = r.kind === 'DISAGREE' ? '#ff8a80' : '#9fe8b6';
    ctx.stroke();
  }
}

function drawPolygon(scale) {
  if (!G.polygon.length) return;
  ctx.beginPath();
  G.polygon.forEach(([x, y], i) => {
    const [sx, sy] = imageToScreen(x, y);
    if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy);
  });
  ctx.closePath();
  ctx.fillStyle = 'rgba(79,156,255,.25)';
  ctx.fill();
  ctx.strokeStyle = '#9ec7ff';
  ctx.lineWidth = 2;
  ctx.stroke();
  G.polygon.forEach(([x, y]) => {
    const [sx, sy] = imageToScreen(x, y);
    ctx.fillStyle = '#fff';
    ctx.beginPath();
    ctx.arc(sx, sy, 3.5, 0, Math.PI * 2);
    ctx.fill();
  });
}

// pan / zoom interactions
let dragging = false;
let dragMoved = false;
let last = null;
canvas.addEventListener('mousedown', (e) => {
  last = [e.clientX, e.clientY];
  if (G.tool === 'pan') dragging = true;
  dragMoved = false;
});
canvas.addEventListener('mousemove', (e) => {
  if (!dragging) return;
  const dx = e.clientX - last[0];
  const dy = e.clientY - last[1];
  if (Math.abs(dx) + Math.abs(dy) > 2) dragMoved = true;
  G.view.x += dx;
  G.view.y += dy;
  last = [e.clientX, e.clientY];
  draw();
});
window.addEventListener('mouseup', () => { dragging = false; });
canvas.addEventListener('click', (e) => {
  if (G.tool !== 'draw' || dragMoved) return;
  const rect = canvas.getBoundingClientRect();
  const [ix, iy] = screenToImage(e.clientX - rect.left, e.clientY - rect.top);
  if (ix < 0 || iy < 0 || ix > G.imgW || iy > G.imgH) return;
  G.polygon.push([Math.round(ix * 10) / 10, Math.round(iy * 10) / 10]);
  $('#actionGroup').classList.remove('hidden');
  draw();
});
canvas.addEventListener('dblclick', () => {
  if (G.tool === 'draw' && G.polygon.length > 3) {
    G.polygon.pop();
    draw();
  }
});
$('#zoomIn').onclick = () => setZoom(G.view.z + 1);
$('#zoomOut').onclick = () => setZoom(G.view.z - 1);
$('#fitBtn').onclick = fitView;

function setZoom(z) {
  z = Math.max(G.tileMinZoom, Math.min(G.tileMaxZoom, z));
  if (z === G.view.z) return;
  const rect = $('#viewport').getBoundingClientRect();
  const cx = rect.width / 2;
  const cy = rect.height / 2;
  const before = screenToImage(cx, cy);
  G.view.z = z;
  const after = imageToScreen(before[0], before[1]);
  G.view.x += cx - after[0];
  G.view.y += cy - after[1];
  $('#zoomLabel').textContent = 'z' + z;
  draw();
}
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  setZoom(G.view.z + (e.deltaY < 0 ? 1 : -1));
}, { passive: false });

$$('input[name=layer]').forEach((el) => {
  el.addEventListener('change', () => { G.layer = el.value; draw(); });
});
$$('input[name=tool]').forEach((el) => {
  el.addEventListener('change', () => {
    G.tool = el.value;
    $('#actionGroup').classList.toggle('hidden', G.tool !== 'draw');
    canvas.style.cursor = G.tool === 'draw' ? 'crosshair' : 'grab';
  });
});
$('#clearPolygon').onclick = () => { G.polygon = []; draw(); };

// ---------- decisions with optimistic concurrency ----------
$('#decisionKind').addEventListener('change', () => {
  const kind = $('#decisionKind').value;
  $('#acceptSource').parentElement.style.visibility = kind === 'ACCEPT' ? 'visible' : 'hidden';
  $('#correctLabel').parentElement.style.visibility = kind === 'CORRECT' ? 'visible' : 'hidden';
});

$('#submitDecision').addEventListener('click', async () => {
  if (G.polygon.length < 3) { toast('先在图上绘制至少 3 个点的多边形', 'err'); return; }
  const kind = $('#decisionKind').value;
  const body = {
    kind,
    baseVersion: G.baseVersion,
    polygon: G.polygon,
    acceptedSourceId: kind === 'ACCEPT' ? $('#acceptSource').value : undefined,
    correctionClass: kind === 'CORRECT' ? Number($('#correctLabel').value) : undefined,
  };
  try {
    const r = await api(`/api/images/${G.imageId}/decisions`, { method: 'POST', json: body });
    G.baseVersion = r.image.version;
    if (r.conflicted) {
      const banner = $('#conflictBanner');
      banner.classList.remove('hidden');
      banner.textContent =
        `检测到并发编辑冲突（基于 v${body.baseVersion}，当前 v${r.image.version}）：\n` +
        `重叠 ${r.conflictPixels} px 已拒绝并返回冲突多边形；不重叠的 ${r.appliedPixels} px 已自动合入。\n` +
        `冲突决定：${r.conflictDecisionIds.join(', ')}\n` +
        `红色多边形为冲突区域，请查看后在新版本上重新合并。`;
      G.polygon = (r.conflictPolygons[0] || G.polygon).map((p) => [p[0], p[1]]);
    } else {
      $('#conflictBanner').classList.add('hidden');
      toast(`区域决定已记录（${r.appliedPixels} px），事件引用来源/修正，不改写输入掩膜`, 'ok');
      G.polygon = [];
    }
    G.image = r.image;
    renderDecisions(r.image);
    renderSources(r.image);
    await refreshAnalysis();
  } catch (err) {
    showDiagnostic(err);
    G.image = await api('/api/images/' + G.imageId);
    G.baseVersion = G.image.version;
  }
});

// ---------- boot ----------
$('#refreshBtn').addEventListener('click', async () => {
  tileCache.clear();
  if (G.imageId) await reloadImage(); else await refreshImageList();
});
(async function init() {
  resizeCanvas();
  await refreshImageList();
})();
