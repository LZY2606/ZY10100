"use strict";

const state = {
  base: "",
  wsId: null,
  summary: null,
  sources: [],
  selectedSource: null,
  comparison: null,
  scale: 24,
  cmpOn: false,
  drawing: false,
  drawPts: [],
  lastConflict: null
};

const $ = (id) => document.getElementById(id);

function toast(msg, kind) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast " + (kind || "");
  t.style.display = "block";
  clearTimeout(toast._h);
  toast._h = setTimeout(() => (t.style.display = "none"), 5200);
}

async function api(path, opts) {
  const res = await fetch(state.base + path, opts || {});
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) {
    const json = await res.json();
    if (!res.ok) {
      const err = new Error(json.message || res.statusText);
      err.payload = json;
      err.status = res.status;
      throw err;
    }
    return json;
  }
  if (!res.ok) throw new Error(res.statusText);
  return res;
}

function jsonPost(path, body) {
  return api(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

function fp64(ch) { return ch.repeat(64); }

// ---------- workspace ----------

async function loadWorkspaceList(select) {
  const data = await api("/api/workspaces");
  const sel = select || $("workspaceSelect");
  sel.innerHTML = "";
  for (const w of data.workspaces) {
    const o = document.createElement("option");
    o.value = w.workspaceId;
    o.textContent = w.name + " (" + w.workspaceId + ") v" + w.eventVersion;
    sel.appendChild(o);
  }
  if (state.wsId) sel.value = state.wsId;
  return data;
}

async function selectWorkspace(id) {
  state.wsId = id;
  await refresh();
}

async function refresh() {
  if (!state.wsId) return;
  const s = await api(`/api/workspaces/${state.wsId}/state`);
  state.summary = s;
  state.sources = s.sources;
  $("wsMeta").innerHTML =
    `<div>${s.name} <span class="tag">${s.workspaceId}</span></div>` +
    `<div class="meta">图像指纹 <span class="tag">${s.image.fingerprintSha256.slice(0,16)}…</span></div>` +
    `<div class="meta">规范网格 ${s.canonicalGeometry.width}×${s.canonicalGeometry.height} ` +
    `间距 ${s.canonicalGeometry.spacingX}×${s.canonicalGeometry.spacingY} ` +
    `方向 ${s.canonicalGeometry.orientation}</div>`;
  $("versionTag").textContent = "v" + s.eventVersion;
  $("recoveryNote").innerHTML = s.recoveryNote
    ? `<div class="pill warn" style="margin-top:8px">♻️ ${s.recoveryNote}</div>` : "";
  renderSources();
  renderMappings();
  renderDecisions();
  renderEvents();
  renderLayerOptions();
  renderTiles();
  await refreshGate();
}

// ---------- sources / mappings rendering ----------

function renderSources() {
  const ul = $("sourceList");
  ul.innerHTML = "";
  for (const src of state.sources) {
    const li = document.createElement("li");
    li.className = "item";
    const stale = isStale(src);
    li.innerHTML =
      `<div><b>${src.algorithm}</b> rev${src.revisionNo}
        ${stale ? '<span class="pill warn">来源已过期</span>' : '<span class="pill good">最新</span>'}
        ${src.meanConfidence != null ? `<span class="pill">置信 ${src.meanConfidence.toFixed(2)}</span>` : ""}
      </div>
      <div class="meta">${src.sourceId.slice(0,8)}… 词表 ${src.vocabulary.vocabVersion} · ${src.revisionOf ? "更新自 "+src.revisionOf.slice(0,8) : "原始"}</div>`;
    ul.appendChild(li);
  }
}

function isStale(src) {
  const latest = state.sources
    .filter((s) => s.algorithm === src.algorithm)
    .reduce((a, b) => (b.revisionNo > a.revisionNo ? b : a), src);
  return latest.revisionNo > src.revisionNo;
}

function renderMappings() {
  const ul = $("mappingList");
  ul.innerHTML = "";
  for (const m of state.summary.mappings) {
    const li = document.createElement("li");
    li.className = "item";
    li.innerHTML =
      `<div>${m.className} <span class="tag">${m.canonicalKey}</span>
        ${m.confirmed ? '<span class="pill good">已确认</span>' : '<span class="pill warn">待确认</span>'}</div>
       <div class="meta">词表 ${m.vocabVersion}</div>`;
    if (!m.confirmed) {
      const btn = document.createElement("button");
      btn.className = "ghost";
      btn.style.marginTop = "6px";
      btn.textContent = "确认映射";
      btn.onclick = async () => {
        await jsonPost(`/api/workspaces/${state.wsId}/mappings/confirm/${m.mappingId}`,
          { user: $("authorName").value || "reviewer" });
        toast("映射已确认", "good");
        refresh();
      };
      li.appendChild(btn);
    }
    ul.appendChild(li);
  }
}

function renderLayerOptions() {
  const sel = $("sourceSelect");
  const prev = state.selectedSource;
  sel.innerHTML = "";
  state.sources.forEach((s, i) => {
    const o = document.createElement("option");
    o.value = s.sourceId;
    o.textContent = `${s.algorithm} rev${s.revisionNo}`;
    sel.appendChild(o);
  });
  state.selectedSource = prev && state.sources.some((s) => s.sourceId === prev)
    ? prev : (state.sources[0] && state.sources[0].sourceId);
  if (state.selectedSource) sel.value = state.selectedSource;
}

// ---------- tile rendering ----------

function canonicalSize() {
  const g = state.summary.canonicalGeometry;
  return { w: g.width, h: g.height };
}

async function loadTilePng(kind, key, tx, ty) {
  const k = key ? `/${encodeURIComponent(key)}` : "/-";
  const url = `/api/workspaces/${state.wsId}/tiles/${kind}${k}/0/${tx}/${ty}.png`;
  const res = await fetch(state.base + url);
  if (!res.ok) throw new Error("tile " + res.status);
  const blob = await res.blob();
  return await createImageBitmap(blob);
}

function tileCount() {
  const { w, h } = canonicalSize();
  return { nx: Math.max(1, Math.ceil(w / 256)), ny: Math.max(1, Math.ceil(h / 256)) };
}

async function renderTiles() {
  if (!state.summary) return;
  const { w, h } = canonicalSize();
  const canvas = $("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, w, h);

  const layer = $("layerSelect").value;
  const { nx, ny } = tileCount();
  const kind = layer === "consensus" ? "consensus" : "source";
  const key = layer === "consensus" ? null : state.selectedSource;

  try {
    for (let ty = 0; ty < ny; ty++) {
      for (let tx = 0; tx < nx; tx++) {
        const bmp = await loadTilePng(kind, key, tx, ty);
        ctx.drawImage(bmp, tx * 256, ty * 256);
        bmp.close();
      }
    }
    if ($("cmpLayer").checked && state.comparison) {
      for (let ty = 0; ty < ny; ty++) {
        for (let tx = 0; tx < nx; tx++) {
          const bmp = await loadTilePng("comparison", state.comparison.comparisonId, tx, ty);
          ctx.drawImage(bmp, tx * 256, ty * 256);
          bmp.close();
        }
      }
    }
    drawDecisionOverlays(ctx);
    renderMiniMap();
    applyTransform();
    updateLegend();
  } catch (e) {
    // tile errors are non-fatal (e.g. comparison expired); leave base layer visible
    console.warn(e);
  }
}

function drawDecisionOverlays(ctx) {
  for (const d of state.summary.decisions) {
    const pts = d.region.ring;
    ctx.beginPath();
    pts.forEach((p, i) => (i ? ctx.lineTo(p[0] + 0.5, p[1] + 0.5) : ctx.moveTo(p[0] + 0.5, p[1] + 0.5)));
    ctx.closePath();
    const color = d.decisionType === "PENDING" ? "255,192,67"
      : d.decisionType === "CORRECTION" ? "255,90,82" : "79,140,255";
    ctx.fillStyle = `rgba(${color},0.18)`;
    ctx.strokeStyle = `rgba(${color},0.95)`;
    ctx.lineWidth = 0.12;
    ctx.fill();
    ctx.stroke();
  }
}

function applyTransform() {
  const c = $("canvas");
  c.style.transform = `scale(${state.scale})`;
  const dl = $("drawLayer");
  dl.width = c.width; dl.height = c.height;
  dl.style.transform = `scale(${state.scale})`;
  dl.style.pointerEvents = "none";
}

function renderMiniMap() {
  const mc = $("mapCanvas");
  const mctx = mc.getContext("2d");
  mctx.clearRect(0, 0, mc.width, mc.height);
  mctx.drawImage($("canvas"), 0, 0, mc.width, mc.height);
}

function updateLegend() {
  const el = $("legend");
  if ($("cmpLayer").checked && state.comparison) {
    el.innerHTML =
      '<span><i class="swatch" style="background:#00C800"></i>一致</span>' +
      '<span><i class="swatch" style="background:#FF2020"></i>分歧</span>' +
      '<span><i class="swatch" style="background:#FFCC00"></i>重采样边界</span>' +
      '<span><i class="swatch" style="background:#808080"></i>低置信排除</span>';
  } else {
    el.innerHTML = '<span class="muted">按类别着色；描边为区域决定</span>';
  }
}

// ---------- decisions & history ----------

function renderDecisions() {
  const ul = $("decisionList");
  ul.innerHTML = "";
  for (const d of state.summary.decisions) {
    const li = document.createElement("li");
    li.className = "item";
    const desc = d.decisionType === "ACCEPT_SOURCE" ? "接受来源 " + (d.sourceId || "").slice(0, 8)
      : d.decisionType === "CORRECTION" ? "修正 → " + d.canonicalKey : "置为未决";
    li.innerHTML =
      `<div>${desc}
        ${d.stale ? '<span class="pill warn">来源已过期</span>' : ''}
      </div>
       <div class="meta">${d.author} · 区域 ${regionDesc(d.region)}</div>`;
    const btn = document.createElement("button");
    btn.className = "ghost";
    btn.style.marginTop = "6px";
    btn.textContent = "撤销";
    btn.onclick = async () => {
      try {
        await jsonPost(`/api/workspaces/${state.wsId}/decisions/${d.eventId}/undo`,
          { user: $("authorName").value || "anon" });
        toast("已追加撤销（反向）事件", "good");
        refresh();
      } catch (e) { toast(e.message, "bad"); }
    };
    li.appendChild(btn);
    ul.appendChild(li);
  }
}

function renderEvents() {
  const ul = $("eventList");
  ul.innerHTML = "";
  for (const e of state.summary.events) {
    const li = document.createElement("li");
    li.className = "item";
    li.innerHTML =
      `<div>#${e.seq} <b>${e.type}</b> · ${e.decisionType || ""} <span class="muted">${e.author}</span></div>
       <div class="meta">${e.eventId.slice(0,8)}… base=${e.baseVersion}
         ${e.undoesEventId ? " undo→" + e.undoesEventId.slice(0,8) : ""}</div>`;
    ul.appendChild(li);
  }
}

function regionDesc(region) {
  const xs = region.ring.map((p) => p[0]);
  const ys = region.ring.map((p) => p[1]);
  const w = Math.max(...xs) - Math.min(...xs);
  const h = Math.max(...ys) - Math.min(...ys);
  return `≈${Math.round(w)}×${Math.round(h)}px`;
}

// ---------- drawing interaction ----------

function eventToImage(evt) {
  const rect = $("canvas").getBoundingClientRect();
  return {
    x: (evt.clientX - rect.left) / state.scale,
    y: (evt.clientY - rect.top) / state.scale
  };
}

$("viewport").addEventListener("click", async (evt) => {
  if (!$("drawToggle").checked || !state.wsId) return;
  const p = eventToImage(evt);
  const { w, h } = canonicalSize();
  if (p.x < 0 || p.y < 0 || p.x > w - 1 || p.y > h - 1) return;
  state.drawPts.push([p.x, p.y]);
  paintDraft();
  if (state.drawPts.length >= 3 && (evt.shiftKey || state.drawPts.length >= 6)) {
    await submitDrawnRegion();
  }
});

function paintDraft() {
  const dl = $("drawLayer");
  const ctx = dl.getContext("2d");
  ctx.clearRect(0, 0, dl.width, dl.height);
  if (state.drawPts.length < 1) return;
  ctx.beginPath();
  state.drawPts.forEach((p, i) => (i ? ctx.lineTo(p[0] + 0.5, p[1] + 0.5)
    : ctx.moveTo(p[0] + 0.5, p[1] + 0.5)));
  if (state.drawPts.length >= 3) ctx.closePath();
  ctx.strokeStyle = "#4f8cff";
  ctx.lineWidth = 0.12;
  ctx.stroke();
  for (const p of state.drawPts) {
    ctx.fillStyle = "#fff";
    ctx.fillRect(p[0], p[1], 0.25, 0.25);
  }
}

async function submitDrawnRegion() {
  const ring = state.drawPts.map((p) => [Math.round(p[0]), Math.round(p[1])]);
  state.drawPts = [];
  paintDraft();
  const action = $("drawAction").value;
  const body = {
    decisionType: action,
    region: { ring },
    baseVersion: state.summary.eventVersion,
    author: $("authorName").value || "anon",
    note: "drawn on canvas"
  };
  if (action === "ACCEPT_SOURCE") body.sourceId = state.selectedSource;
  if (action === "CORRECTION") body.canonicalKey = $("classKey").value || "class:tumor";
  try {
    await jsonPost(`/api/workspaces/${state.wsId}/decisions`, body);
    toast("区域决定已提交", "good");
    refresh();
  } catch (e) { handleDecisionError(e, body); }
}

async function submitDecision(body) {
  try {
    await jsonPost(`/api/workspaces/${state.wsId}/decisions`, body);
    toast("已提交", "good");
    refresh();
  } catch (e) { handleDecisionError(e, body); }
}

function handleDecisionError(e, body) {
  if (e.status === 409 && e.payload && e.payload.conflict) {
    state.lastConflict = { body, payload: e.payload };
    const c = e.payload.conflict;
    $("conflictText").textContent = c.message;
    $("conflictDiag").textContent =
      `服务器版本: ${e.payload.eventVersion}\n` +
      `冲突作者: ${c.conflictingAuthor} (事件 ${c.conflictingEventId.slice(0,8)}…)\n` +
      `冲突像素: ${c.conflictingPixels}\n` +
      `冲突多边形:\n${c.conflictPolygons.map((p, i) =>
        `  [${i}] ` + JSON.stringify(p.ring.map((q) => q.map((v) => +v.toFixed(1))))).join("\n")}\n` +
      `已自动合入非重叠片段: ${c.autoMerged.length} 块`;
    $("conflictDlg").showModal();
  } else {
    const reasons = e.payload && e.payload.reasons ? e.payload.reasons.join("\n") : e.message;
    toast(reasons, "bad");
  }
}

$("conflictClose").onclick = () => $("conflictDlg").close();
$("conflictResubmit").onclick = async () => {
  // After auto-merge the only safe re-submit is explicitly the free area; here we resubmit on
  // the NEW base version. Overlap will still conflict but non-overlap merges, matching workflow.
  const c = state.lastConflict;
  if (!c) return;
  c.body.baseVersion = c.payload.eventVersion;
  $("conflictDlg").close();
  try {
    await jsonPost(`/api/workspaces/${state.wsId}/decisions`, c.body);
    toast("已基于最新版本重新合并", "good");
    refresh();
  } catch (e) {
    if (e.status === 409 && e.payload.conflict) {
      state.lastConflict = { body: c.body, payload: e.payload };
      toast("仍有重叠，已保留非冲突合入；请在画布上避开红色区域", "warn");
      handleDecisionError(e, c.body);
    } else { toast(e.message, "bad"); }
  }
};

// ---------- demo mask generation ----------

function genLabels(seed, w, h) {
  const a = new Array(w * h);
  for (let i = 0; i < a.length; i++) {
    const x = i % w;
    if (seed === "wrong") a[i] = (x < w / 2 ? 1 : 0);
    else if (seed === "A2") a[i] = (i % 3 === 0 ? 1 : (i % 8 === 0 ? 2 : 0));
    else if (seed === "A") a[i] = (x < w / 2 ? 1 : (i % 9 === 0 ? 2 : 0));
    else a[i] = (x < w / 2 ? 1 : (i % 6 === 0 ? 2 : 0));
  }
  return a;
}

async function ingest({ algo, vocab, seed, fingerprint, revisionOf, labels }) {
  if (!state.wsId) { toast("请先创建/选择工作区", "warn"); return; }
  const g = state.summary.canonicalGeometry;
  const body = {
    algorithm: algo,
    image: { imageId: state.summary.image.imageId, fingerprintSha256: fingerprint },
    geometry: { width: g.width, height: g.height, spacingX: g.spacingX, spacingY: g.spacingY,
      orientation: g.orientation, originX: g.originX, originY: g.originY },
    vocabulary: {
      vocabVersion: vocab,
      entries: [
        { classId: 1, name: "liver", displayColor: "#cc0000" },
        { classId: 2, name: "tumor", displayColor: "#00cc00" }
      ]
    },
    labels: labels || genLabels(seed, g.width, g.height),
    allowResample: $("inResample").checked
  };
  if (revisionOf) body.revisionOf = revisionOf;
  const r = await jsonPost(`/api/workspaces/${state.wsId}/sources`, body);
  toast(`已接收 ${r.source.algorithm} rev${r.source.revisionNo}` +
    (r.resampled ? "（含重采样边界）" : ""), "good");
  if (r.warnings && r.warnings.length) toast(r.warnings.join("; "), "warn");
  refresh();
  return r;
}

$("genA").onclick = () => ingest({ algo: $("inAlgo").value || "algo-a",
  vocab: $("inVocab").value || "vocab-1", seed: "A",
  fingerprint: $("inFinger").value || state.summary.image.fingerprintSha256 });
$("genB").onclick = () => ingest({ algo: "algo-b",
  vocab: (parseInt(($("inVocab").value || "vocab-1").replace(/\D/g, "")) + 1)
    ? "vocab-" + (parseInt(($("inVocab").value || "vocab-1").replace(/\D/g, "")) + 1)
    : "vocab-2",
  seed: "B", fingerprint: $("inFinger").value || state.summary.image.fingerprintSha256 });
$("genA2").onclick = () => {
  const a = state.sources.filter((s) => s.algorithm === ($("inAlgo").value || "algo-a"))
    .sort((x, y) => y.revisionNo - x.revisionNo)[0];
  if (!a) { toast("没有可更新的 A 来源", "warn"); return; }
  ingest({ algo: a.algorithm, vocab: a.vocabulary.vocabVersion, seed: "A2",
    fingerprint: state.summary.image.fingerprintSha256, revisionOf: a.sourceId });
};
$("genWrong").onclick = () => ingest({ algo: "evil", vocab: "vocab-x", seed: "wrong",
  fingerprint: fp64("c") }).catch((e) =>
    toast((e.payload ? e.payload.reasons.join("\n") : e.message), "bad"));

// ---------- comparison ----------

$("compareBtn").onclick = async () => {
  if (state.sources.length < 2) { toast("至少需要两个来源", "warn"); return; }
  try {
    const r = await jsonPost(`/api/workspaces/${state.wsId}/comparisons`, {
      sourceIds: [state.sources[0].sourceId, state.sources[1].sourceId],
      confidenceThreshold: parseFloat($("threshold").value || "0"),
      quarantineResampleEdges: $("quarantine").checked
    });
    state.comparison = r;
    $("cmpLayer").checked = true;
    const s = r.stats;
    $("cmpStats").innerHTML =
      `<div>规则版本 <span class="tag">${r.ruleVersion}</span></div>` +
      `<div>一致 <b style="color:var(--good)">${s.agreePixels}</b> ·
        分歧 <b style="color:var(--bad)">${s.disagreePixels}</b> ·
        未覆盖 ${s.uncoveredPixels}</div>` +
      `<div>边界隔离 ${s.resampleEdgePixels} · 低置信排除 ${s.excludedByConfidencePixels}</div>`;
    renderTiles();
  } catch (e) { toast(e.payload ? e.payload.reasons.join("\n") : e.message, "bad"); }
};

// ---------- gate / release / export / import ----------

async function refreshGate() {
  const g = await api(`/api/workspaces/${state.wsId}/gate`);
  $("gateBox").innerHTML = g.allowed
    ? '<span class="pill good">可发布</span>'
    : '<span class="pill bad">不可发布</span><pre class="diag" style="margin-top:8px">'
      + g.problems.map((p) => "• " + p).join("\n") + "</pre>";
}

$("releaseBtn").onclick = async () => {
  try {
    const r = await jsonPost(`/api/workspaces/${state.wsId}/releases`,
      { user: $("authorName").value || "anon" });
    toast(`已发布版本 #${r.versionNo}，区域 ${r.regionAreaPixels}px，边界哈希 ${r.boundaryHash.slice(0,12)}…`, "good");
    refresh();
  } catch (e) {
    toast((e.payload ? e.payload.reasons.join("\n") : e.message), "bad");
  }
};

$("exportBtn").onclick = () => {
  if (!state.wsId) return;
  window.location = state.base + `/api/workspaces/${state.wsId}/export`;
};

$("importFile").addEventListener("change", async (evt) => {
  const file = evt.target.files[0];
  if (!file) return;
  const buf = await file.arrayBuffer();
  let binary = "";
  const bytes = new Uint8Array(buf);
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  const bundleBase64 = btoa(binary);
  const newId = "imported-" + Date.now();
  try {
    const r = await jsonPost("/api/import", {
      newWorkspaceId: newId, name: file.name, bundleBase64
    });
    toast(`再导入${r.accepted ? "成功" : "被拒"}：区域 ${r.regionPixels}px 边界哈希一致=${r.accepted}`,
      r.accepted ? "good" : "bad");
    await loadWorkspaceList();
    state.wsId = newId;
    $("workspaceSelect").value = newId;
    refresh();
  } catch (e) {
    toast((e.payload ? JSON.stringify(e.payload.reasons) : e.message), "bad");
  }
  evt.target.value = "";
});

// ---------- workspace create/select ----------

$("newWsBtn").onclick = () => {
  $("nwFinger").value = fp64("a");
  $("newWsDlg").showModal();
};
$("nwCancel").onclick = () => $("newWsDlg").close();
$("nwCreate").onclick = async () => {
  try {
    await jsonPost("/api/workspaces", {
      workspaceId: $("nwId").value,
      name: $("nwName").value,
      image: { imageId: $("nwId").value, fingerprintSha256: $("nwFinger").value },
      canonicalGeometry: { width: parseInt($("nwW").value), height: parseInt($("nwH").value),
        spacingX: 0.5, spacingY: 0.5, orientation: "L|A", originX: 0, originY: 0 }
    });
    $("newWsDlg").close();
    state.wsId = $("nwId").value;
    await loadWorkspaceList();
    $("workspaceSelect").value = state.wsId;
    refresh();
  } catch (e) { toast(e.message, "bad"); }
};

$("workspaceSelect").onchange = (e) => selectWorkspace(e.target.value);
$("refreshBtn").onclick = () => { loadWorkspaceList(); refresh(); };

// ---------- zoom & layer ----------
$("zoomIn").onclick = () => { state.scale = Math.min(96, state.scale * 1.4); };
$("zoomOut").onclick = () => { state.scale = Math.max(2, state.scale / 1.4); };
$("fitBtn").onclick = () => {
  const vp = $("viewport").getBoundingClientRect();
  const { w, h } = canonicalSize();
  state.scale = Math.min((vp.width - 260) / w, (vp.height - 40) / h);
  state.scale = Math.max(2, state.scale);
};
$("layerSelect").onchange = renderTiles;
$("sourceSelect").onchange = (e) => { state.selectedSource = e.target.value; renderTiles(); };
$("cmpLayer").onchange = renderTiles;

// ---------- boot ----------
(async function boot() {
  const data = await loadWorkspaceList();
  if (data.workspaces.length) {
    state.wsId = data.workspaces[0].workspaceId;
    $("workspaceSelect").value = state.wsId;
    await refresh();
    $("fitBtn").click();
  } else {
    toast("没有工作区：点击「新建工作区」开始（演示网格 8×6，已预填一致指纹）", "warn");
    $("newWsBtn").click();
  }
})();
