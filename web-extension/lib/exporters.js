// UI Mapper Web - exporters.
//
// Turns a recorded session into shareable files. Pure string building: no chrome.* APIs, no
// network, no external assets. The caller supplies the full screen trees (from store) so the
// exporters stay side-effect free.
//
// Every string that comes from a page is untrusted and is escaped (escapeHtml / csvCell / the
// mermaid & DOT escapers) before it is placed into any output. The HTML report also carries a
// strict CSP that forbids scripts and network access as a second line of defence.

import {
  START_NODE,
  isExternalNode,
  externalOrigin,
  escapeHtml,
  escapeAttr,
  csvCell,
  clip,
  clean,
  flatten,
  labelOf,
  safeText,
  rectStr,
  rectEmpty,
  toElementRef,
  elementKey,
  refDisplay,
  attr,
} from './model.js';
import {
  routesFromStart,
  depths,
  startScreenId,
  stepText,
  edgeLabel,
  actionVerb,
  describe,
  isDashed,
  nodeLabel,
  layout,
} from './routeGraph.js';

export const FORMAT = 'uimapper-web/1';

const FORMATS = {
  json: { mime: 'application/json', ext: 'json' },
  mermaid: { mime: 'text/plain', ext: 'mmd' },
  dot: { mime: 'text/vnd.graphviz', ext: 'dot' },
  csv: { mime: 'text/csv', ext: 'csv' },
  html: { mime: 'text/html', ext: 'html' },
};

/**
 * Build one export file for a session.
 * @param {import('./model.js').Session} session
 * @param {Object.<string,import('./model.js').Screen>|Map} screensMap  screenId -> full Screen.
 * @param {('json'|'mermaid'|'dot'|'csv'|'html')} format
 * @param {number} [exportedAt]
 * @returns {{filename:string, mime:string, text:string}}
 */
export function exportSession(session, screensMap, format, exportedAt = Date.now()) {
  const spec = FORMATS[format];
  if (!spec) throw new Error('Format ekspor tidak dikenal: ' + format);
  const getScreen = screenGetter(screensMap);
  let text;
  switch (format) {
    case 'json': text = jsonBundle(session, getScreen, exportedAt); break;
    case 'mermaid': text = mermaid(session); break;
    case 'dot': text = dot(session); break;
    case 'csv': text = elementsCsv(session, getScreen); break;
    case 'html': text = htmlReport(session, getScreen, exportedAt); break;
    default: throw new Error('Format ekspor tidak dikenal: ' + format);
  }
  return { filename: fileBase(session) + '_' + stamp(exportedAt) + '.' + spec.ext, mime: spec.mime, text };
}

function screenGetter(screensMap) {
  if (screensMap instanceof Map) return (id) => screensMap.get(id) || null;
  return (id) => (screensMap && screensMap[id]) || null;
}

// String-returning aliases (session, screensMap) -> text. These let callers that pick an
// exporter by format name (e.g. the side panel) get the full-featured output of this module.
// `screensMap` may be a plain object keyed by screen id or a Map of the full Screen trees.

/** Complete JSON bundle as a string. */
export function json(session, screensMap, exportedAt = Date.now()) {
  return jsonBundle(session, screenGetter(screensMap), exportedAt);
}

/** Elements CSV (RFC 4180, UTF-8 BOM) as a string. */
export function csv(session, screensMap) {
  return elementsCsv(session, screenGetter(screensMap));
}

/** Self-contained offline HTML report as a string. */
export function html(session, screensMap, exportedAt = Date.now()) {
  return htmlReport(session, screenGetter(screensMap), exportedAt);
}

// --------------------------------------------------------------------------- JSON

function jsonBundle(session, getScreen, exportedAt) {
  const screens = [];
  for (const s of session.screens || []) {
    const scr = getScreen(s.id);
    if (scr) screens.push(scr);
  }
  const routeIds = {};
  const routes = routesFromStart(session);
  for (const id of Object.keys(routes)) routeIds[id] = routes[id].map((e) => e.id);
  const bundle = {
    format: FORMAT,
    exportedAt,
    session,
    screens,
    routesFromStart: routeIds,
  };
  return JSON.stringify(bundle, null, 2) + '\n';
}

// --------------------------------------------------------------------------- Mermaid

/** Mermaid "flowchart TD" source of the navigation map. */
export function mermaid(session) {
  const out = ['flowchart TD'];
  const screens = session.screens || [];
  const edges = session.edges || [];
  if (!screens.length && !edges.length) {
    out.push('    kosong["Belum ada layar yang direkam"]');
    return out.join('\n') + '\n';
  }
  const ids = new MermaidIds();
  const screenIds = new Set(screens.map((s) => s.id));
  const referenced = new Set();
  for (const e of edges) {
    referenced.add(e.from);
    referenced.add(e.to);
  }
  const reachable = new Set(Object.keys(depths(session)));
  const hasStart = referenced.has(START_NODE);
  if (hasStart) out.push('    ' + ids.of(START_NODE) + '((Mulai))');

  const unreached = [];
  for (const s of screens) {
    const id = ids.of(s.id);
    let line = '    ' + id + '["' + mmd(s.id) + ' · ' + mmd(clip(s.label, 48));
    if (s.path) line += '<br/><small>' + mmd(clip(s.path, 48)) + '</small>';
    line += '"]';
    out.push(line);
    if (!reachable.has(s.id)) unreached.push(id);
  }

  const externals = [...referenced].filter(isExternalNode);
  for (const x of externals) {
    out.push('    ' + ids.of(x) + '[/"Situs lain: ' + mmd(externalOrigin(x)) + '"/]');
  }
  const unknown = [...referenced].filter((id) => id !== START_NODE && !isExternalNode(id) && !screenIds.has(id));
  for (const u of unknown) out.push('    ' + ids.of(u) + '["' + mmd(u) + '"]');

  if (edges.length) out.push('');
  for (const e of edges) {
    out.push(
      '    ' + ids.of(e.from) + (isDashed(e) ? ' -.->|"' : ' -->|"') + mmd(edgeLabel(e, 60)) + '"| ' + ids.of(e.to),
    );
  }

  out.push('');
  out.push('    classDef startNode fill:#1f4bd8,stroke:#1f4bd8,color:#ffffff');
  out.push('    classDef extNode fill:#fff4e5,stroke:#c77d00,color:#4a3000,stroke-dasharray: 4 3');
  out.push('    classDef unreached stroke-dasharray: 5 4');
  if (hasStart) out.push('    class ' + ids.of(START_NODE) + ' startNode');
  if (externals.length) out.push('    class ' + externals.map((x) => ids.of(x)).join(',') + ' extNode');
  if (unreached.length) out.push('    class ' + unreached.join(',') + ' unreached');
  return out.join('\n') + '\n';
}

/** Mermaid label escaping: entity codes for # " < > |, control/newlines become spaces. */
function mmd(s) {
  let out = '';
  for (const ch of String(s == null ? '' : s)) {
    switch (ch) {
      case '#': out += '#35;'; break;
      case '"': out += '#quot;'; break;
      case '<': out += '#lt;'; break;
      case '>': out += '#gt;'; break;
      case '|': out += '#124;'; break;
      default:
        out += ch < ' ' || ch === '\u007f' ? ' ' : ch;
    }
  }
  return out;
}

class MermaidIds {
  constructor() {
    this.byRaw = new Map();
    this.used = new Set();
  }

  of(raw) {
    if (this.byRaw.has(raw)) return this.byRaw.get(raw);
    let base;
    if (raw === START_NODE) base = 'START';
    else if (isExternalNode(raw)) base = 'ext_' + sanitizeId(externalOrigin(raw));
    else base = sanitizeId(raw);
    if (!base || /^[0-9]/.test(base) || RESERVED.has(base.toLowerCase())) base = 'n_' + base;
    let id = base;
    let i = 2;
    while (this.used.has(id)) id = base + '_' + i++;
    this.used.add(id);
    this.byRaw.set(raw, id);
    return id;
  }
}

const RESERVED = new Set([
  'end', 'graph', 'flowchart', 'subgraph', 'style', 'classdef', 'class', 'click', 'call',
  'href', 'default', 'linkstyle', 'interpolate', 'direction', 'accdescr', 'acctitle',
]);

function sanitizeId(s) {
  let out = '';
  for (const ch of String(s == null ? '' : s)) {
    out += /[A-Za-z0-9_]/.test(ch) ? ch : '_';
  }
  return out;
}

// --------------------------------------------------------------------------- DOT

/** Graphviz DOT source (render with `dot -Tsvg graph.dot -o graph.svg`). */
export function dot(session) {
  const out = ['digraph UiMap {', '    rankdir=TB;'];
  const title = [originHost(session.origin), session.name].filter(Boolean).join(' — ');
  if (title) out.push('    graph [label="' + dotEsc(title) + '", labelloc=t, fontsize=16, fontname="Helvetica"];');
  out.push('    node [shape=box, style="rounded,filled", fillcolor="#eef4fb", fontname="Helvetica"];');
  out.push('    edge [fontname="Helvetica", fontsize=10, color="#5b6b80", fontcolor="#394656"];');

  const screens = session.screens || [];
  const edges = session.edges || [];
  const screenIds = new Set(screens.map((s) => s.id));
  const referenced = new Set();
  for (const e of edges) {
    referenced.add(e.from);
    referenced.add(e.to);
  }
  const reachable = new Set(Object.keys(depths(session)));
  const startId = startScreenId(session);

  if (referenced.has(START_NODE)) {
    out.push('    "' + dotEsc(START_NODE) + '" [label="Mulai", shape=circle, style=filled, fillcolor="#1f4bd8", fontcolor="white"];');
  }
  for (const s of screens) {
    let line = '    "' + dotEsc(s.id) + '" [label="' + dotEsc(s.id + ' · ' + clip(s.label, 48));
    if (s.path) line += '\\n' + dotEsc(clip(s.path, 48));
    line += '"';
    if (s.id === startId) line += ', penwidth=2, color="#1f4bd8"';
    if (!reachable.has(s.id)) line += ', style="rounded,filled,dashed"';
    line += '];';
    out.push(line);
  }
  for (const x of referenced) {
    if (isExternalNode(x)) {
      out.push(
        '    "' + dotEsc(x) + '" [label="Situs lain\\n' + dotEsc(externalOrigin(x)) +
          '", shape=parallelogram, style="filled,dashed", fillcolor="#fff4e5", color="#c77d00"];',
      );
    } else if (x !== START_NODE && !screenIds.has(x)) {
      out.push('    "' + dotEsc(x) + '" [label="' + dotEsc(x) + '", style="rounded,dashed"];');
    }
  }
  for (const e of edges) {
    let line = '    "' + dotEsc(e.from) + '" -> "' + dotEsc(e.to) + '" [label="' + dotEsc(edgeLabel(e, 60)) + '"';
    if (isDashed(e)) line += ', style=dashed';
    line += '];';
    out.push(line);
  }
  out.push('}');
  return out.join('\n') + '\n';
}

function dotEsc(s) {
  let out = '';
  for (const ch of String(s == null ? '' : s)) {
    if (ch === '\\') out += '\\\\';
    else if (ch === '"') out += '\\"';
    else out += ch < ' ' || ch === '\u007f' ? ' ' : ch;
  }
  return out;
}

// --------------------------------------------------------------------------- CSV (elements)

const ELEMENT_COLUMNS = [
  'screen_id', 'screen_label', 'url', 'path', 'idx', 'depth', 'tag', 'id', 'classes', 'role',
  'name', 'text', 'href', 'clickable', 'editable', 'password', 'visible', 'rect', 'selector', 'xpath', 'label',
];

/** One row per captured node of every screen (RFC 4180, UTF-8 with BOM, CRLF). */
export function elementsCsv(session, getScreen) {
  const rows = [ELEMENT_COLUMNS.join(',')];
  for (const summary of session.screens || []) {
    const screen = getScreen(summary.id);
    if (!screen || !screen.root) continue;
    for (const n of flatten(screen.root)) {
      rows.push(
        [
          csvCell(summary.id),
          csvCell(summary.label),
          csvCell(screen.url),
          csvCell(screen.path),
          csvCell(String(n.idx)),
          csvCell(String(n.depth)),
          csvCell(n.tag),
          csvCell(n.id),
          csvCell((n.cls || []).join(' ')),
          csvCell(n.role),
          csvCell(n.name),
          csvCell(safeText(n)),
          csvCell(n.href),
          csvCell(String(!!n.clickable)),
          csvCell(String(!!n.editable)),
          csvCell(String(!!n.password)),
          csvCell(String(n.visible !== false)),
          csvCell(rectStr(n.rect)),
          csvCell(n.selector),
          csvCell(n.xpath),
          csvCell(labelOf(n)),
        ].join(','),
      );
    }
  }
  return '﻿' + rows.join('\r\n') + '\r\n';
}

// --------------------------------------------------------------------------- filenames

function fileBase(session) {
  const raw = session.name || originHost(session.origin) || 'uimapper';
  let out = '';
  for (const ch of raw.normalize ? raw.normalize('NFKD') : raw) {
    if (/[A-Za-z0-9_-]/.test(ch)) out += ch;
    else if (out && out[out.length - 1] !== '_') out += '_';
  }
  out = out.replace(/^_+|_+$/g, '').slice(0, 40).replace(/^_+|_+$/g, '');
  return out || 'uimapper';
}

function stamp(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '_' + p(d.getHours()) + p(d.getMinutes());
}

function originHost(origin) {
  if (!origin) return '';
  try {
    return new URL(origin).host || origin;
  } catch {
    return origin;
  }
}

// --------------------------------------------------------------------------- HTML report

const MAX_ELEMENT_ROWS = 250;
const TREE_TOTAL_BUDGET = 30000;
const TREE_MIN_NODES = 150;
const TREE_MAX_NODES = 800;
const TREE_MAX_DEPTH = 32;

/** Self-contained, offline HTML report of a session. */
export function htmlReport(session, getScreen, exportedAt) {
  const ctx = {
    s: session,
    getScreen,
    exportedAt,
    startId: startScreenId(session),
    depths: depths(session),
    routes: routesFromStart(session),
    screenById: new Map((session.screens || []).map((s) => [s.id, s])),
  };
  ctx.treeBudget = Math.min(TREE_MAX_NODES, Math.max(TREE_MIN_NODES, Math.floor(TREE_TOTAL_BUDGET / Math.max(1, session.screens.length))));
  const out = [];
  head(out, ctx);
  header(out, ctx);
  out.push('<main class="wrap">');
  mapSection(out, ctx);
  routesSection(out, ctx);
  transitionsSection(out, ctx);
  screensSection(out, ctx);
  out.push('</main>');
  footer(out, ctx);
  return out.join('\n');
}

function appName(s) {
  return originHost(s.origin) || 'Aplikasi web';
}

function fmtDate(ms) {
  if (!ms || ms <= 0) return '–';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function anchorSafe(id) {
  let out = '';
  for (const ch of String(id)) out += /[A-Za-z0-9_-]/.test(ch) ? ch : '_';
  return out;
}
const screenAnchor = (id) => 'screen-' + anchorSafe(id);
const routeAnchor = (id) => 'route-' + anchorSafe(id);
const edgeAnchor = (id) => 'edge-' + anchorSafe(id);
const elementAnchor = (sid, idx) => 'el-' + anchorSafe(sid) + '-' + idx;

function screenTitle(ctx, id) {
  const sc = ctx.screenById.get(id);
  return sc ? sc.id + ' · ' + sc.label : id;
}

/** Escaped (and, for screens, linked) label of any graph node id. */
function nodeRef(ctx, id) {
  if (id === START_NODE) return 'Mulai';
  if (isExternalNode(id)) return 'Situs lain (<code>' + escapeHtml(externalOrigin(id)) + '</code>)';
  if (ctx.screenById.has(id)) return '<a href="#' + screenAnchor(id) + '">' + escapeHtml(screenTitle(ctx, id)) + '</a>';
  return escapeHtml(id);
}

function head(out, ctx) {
  const s = ctx.s;
  out.push('<!DOCTYPE html>');
  out.push('<html lang="id">');
  out.push('<head>');
  out.push('<meta charset="utf-8">');
  out.push('<meta name="viewport" content="width=device-width, initial-scale=1">');
  out.push('<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src data:; style-src \'unsafe-inline\'">');
  out.push('<meta name="color-scheme" content="light dark">');
  out.push('<meta name="generator" content="UI Mapper Web">');
  out.push('<title>' + escapeHtml('Peta UI · ' + appName(s) + ' · ' + s.name) + '</title>');
  out.push('<style>' + REPORT_CSS + '</style>');
  out.push('</head>');
  out.push('<body>');
}

function header(out, ctx) {
  const s = ctx.s;
  const totalClickable = (s.screens || []).reduce((a, x) => a + (x.clickableCount || 0), 0);
  const totalNodes = (s.screens || []).reduce((a, x) => a + (x.nodeCount || 0), 0);
  const externalCount = new Set(
    (s.edges || []).flatMap((e) => [e.from, e.to]).filter(isExternalNode),
  ).size;

  out.push('<header class="top"><div class="wrap">');
  out.push('<p class="eyebrow">UI Mapper Web · Laporan peta UI &amp; navigasi</p>');
  out.push('<h1>' + escapeHtml(appName(s)) + '</h1>');
  if (s.origin) out.push('<p class="pkg"><code>' + escapeHtml(s.origin) + '</code></p>');
  out.push('<dl class="meta">');
  dt(out, 'Sesi', escapeHtml(s.name));
  dt(out, 'Origin', escapeHtml(s.origin || '–'));
  dt(out, 'URL awal', s.startUrl ? '<code>' + escapeHtml(clip(s.startUrl, 90)) + '</code>' : '–');
  dt(out, 'Dibuat', escapeHtml(fmtDate(s.createdAt)));
  dt(out, 'Diperbarui', escapeHtml(fmtDate(s.updatedAt)));
  dt(out, 'Diekspor', escapeHtml(fmtDate(ctx.exportedAt)));
  out.push('</dl>');
  out.push('<ul class="stats">');
  out.push('<li><b>' + (s.screens || []).length + '</b> layar</li>');
  out.push('<li><b>' + (s.edges || []).length + '</b> transisi</li>');
  out.push('<li><b>' + totalClickable + '</b> elemen dapat diklik</li>');
  out.push('<li><b>' + totalNodes + '</b> node DOM</li>');
  if (externalCount > 0) out.push('<li><b>' + externalCount + '</b> situs lain</li>');
  out.push('</ul>');
  out.push(
    '<nav class="toc"><a href="#peta">Peta navigasi</a><a href="#rute">Rute dari layar awal</a>' +
      '<a href="#transisi">Semua transisi</a><a href="#layar">Detail layar</a></nav>',
  );
  out.push('</div></header>');
}

function footer(out, ctx) {
  out.push('<footer class="wrap">Dibuat oleh UI Mapper Web pada ' + escapeHtml(fmtDate(ctx.exportedAt)) +
    '. Laporan ini dibuat sepenuhnya di perangkat dan dapat dibuka tanpa internet. Nilai kolom sandi dan teks ' +
    'yang diketik pengguna tidak pernah disimpan.</footer>');
  out.push('</body>');
  out.push('</html>');
}

function dt(out, label, valueHtml) {
  out.push('<div><dt>' + label + '</dt><dd>' + valueHtml + '</dd></div>');
}

// ---- map

function mapSection(out, ctx) {
  const s = ctx.s;
  out.push('<section id="peta">');
  out.push('<h2>Peta navigasi</h2>');
  if (!(s.screens || []).length && !(s.edges || []).length) {
    out.push('<p class="hint">Belum ada layar yang direkam pada sesi ini.</p></section>');
    return;
  }
  out.push('<p class="hint">Kotak = layar, panah = aksi yang terekam. Klik kotak untuk membuka detail layar. ' +
    'Geser peta ke samping bila lebih lebar dari layar.</p>');
  out.push('<input type="checkbox" id="fit" class="fit"><label for="fit">Sesuaikan ke lebar layar</label>');
  out.push('<div class="map">');
  out.push(mapSvg(s));
  out.push('</div>');
  out.push(
    '<ul class="legend">' +
      '<li><span class="lg"></span>Aksi (klik, kirim)</li>' +
      '<li><span class="lg d"></span>Kembali / pindah otomatis</li>' +
      '<li><span class="lg b h"></span>Layar awal</li>' +
      '<li><span class="lg b u"></span>Tak terjangkau dari layar awal</li>' +
      '<li><span class="lg b x"></span>Situs lain</li>' +
      '</ul>',
  );
  out.push('<details><summary>Sumber diagram Mermaid</summary>');
  out.push('<p class="hint">Salin ke editor Mermaid atau dokumen Markdown yang mendukung Mermaid.</p><pre><code>');
  out.push(escapeHtml(mermaid(s)));
  out.push('</code></pre></details></section>');
}

/** Inline SVG navigation map from the shared layout. */
function mapSvg(session) {
  const g = layout(session);
  if (!g.nodes.length) return '';
  const out = [];
  out.push(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + g.width + ' ' + g.height + '" width="' + g.width +
      '" height="' + g.height + '" role="img" aria-labelledby="map-title"><title id="map-title">' +
      escapeHtml('Peta navigasi: ' + session.screens.length + ' layar, ' + session.edges.length + ' transisi') + '</title>',
  );
  out.push(
    '<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">' +
      '<path class="ahf" d="M0,0 L10,5 L0,10 z"/></marker></defs>',
  );
  // Edges under the nodes.
  out.push('<g class="edges">');
  const labels = [];
  for (const e of g.edges) {
    out.push(
      '<path class="' + (e.dashed ? 'e dash' : 'e') + '" d="' + e.d + '" marker-end="url(#ah)"><title>' +
        escapeHtml(e.tip) + '</title></path>',
    );
    labels.push(
      '<text class="el" x="' + e.labelX + '" y="' + e.labelY + '" text-anchor="' + e.anchor + '">' +
        escapeHtml(e.label) + '</text>',
    );
  }
  out.push('</g>');
  // Nodes.
  out.push('<g class="nodes">');
  for (const n of g.nodes) out.push(svgNode(n));
  out.push('</g>');
  out.push('<g class="labels">' + labels.join('') + '</g></svg>');
  return out.join('');
}

function svgNode(n) {
  const cx = Math.round(n.cx);
  const cy = Math.round(n.cy);
  if (n.kind === 'start') {
    return (
      '<g class="st"><title>' + escapeHtml(n.tip) + '</title>' +
      '<circle cx="' + cx + '" cy="' + cy + '" r="' + Math.round(n.h / 2) + '"/>' +
      '<text x="' + cx + '" y="' + (cy + 4) + '" text-anchor="middle">Mulai</text></g>'
    );
  }
  if (n.kind === 'screen') {
    let cls = 'n';
    if (n.home) cls += ' home';
    if (n.unreached) cls += ' unr';
    return (
      '<a href="#' + screenAnchor(n.id) + '"><g class="' + cls + '"><title>' + escapeHtml(n.tip) + '</title>' +
      '<rect class="nb" x="' + n.x + '" y="' + n.y + '" width="' + n.w + '" height="' + n.h + '" rx="10"/>' +
      svgLines(n, cx, cy) + '</g></a>'
    );
  }
  if (n.kind === 'external') {
    const sk = 12;
    return (
      '<g class="n ext"><title>' + escapeHtml(n.tip) + '</title>' +
      '<polygon class="nb" points="' + (n.x + sk) + ',' + n.y + ' ' + (n.x + n.w) + ',' + n.y + ' ' +
      (n.x + n.w - sk) + ',' + (n.y + n.h) + ' ' + n.x + ',' + (n.y + n.h) + '"/>' +
      svgLines(n, cx, cy) + '</g>'
    );
  }
  return (
    '<g class="n unr"><title>' + escapeHtml(n.tip) + '</title>' +
    '<rect class="nb" x="' + n.x + '" y="' + n.y + '" width="' + n.w + '" height="' + n.h + '" rx="10"/>' +
    svgLines(n, cx, cy) + '</g>'
  );
}

function svgLines(n, cx, cy) {
  if (!n.line2) {
    return '<text class="t1" x="' + cx + '" y="' + (cy + 4) + '" text-anchor="middle">' + escapeHtml(n.line1) + '</text>';
  }
  return (
    '<text class="t1" x="' + cx + '" y="' + (cy - 5) + '" text-anchor="middle">' + escapeHtml(n.line1) + '</text>' +
    '<text class="t2" x="' + cx + '" y="' + (cy + 12) + '" text-anchor="middle">' + escapeHtml(n.line2) + '</text>'
  );
}

// ---- routes

function routesSection(out, ctx) {
  const s = ctx.s;
  out.push('<section id="rute"><h2>Rute dari layar awal</h2>');
  const start = ctx.startId ? ctx.screenById.get(ctx.startId) : null;
  if (!start) {
    out.push('<p class="hint">Belum ada layar.</p></section>');
    return;
  }
  out.push('<p class="hint">Layar awal: ' + nodeRef(ctx, start.id));
  if ((s.edges || []).some((e) => e.from === START_NODE && e.to === start.id)) out.push(' (tampil setelah situs dibuka)');
  out.push('. Tiap rute adalah urutan aksi terpendek yang terekam, tanpa tombol Kembali. Langkah "Pindah" adalah ' +
    'perpindahan tanpa klik yang terekam (mis. pengalihan otomatis).</p>');

  out.push('<ol class="routes">');
  for (const sc of s.screens || []) {
    const path = ctx.routes[sc.id];
    if (!path) continue;
    out.push('<li id="' + routeAnchor(sc.id) + '">' + nodeRef(ctx, sc.id));
    if (!path.length) {
      out.push(' <span class="muted">· layar awal</span></li>');
      continue;
    }
    out.push(' <span class="muted">· ' + path.length + ' langkah</span><ol class="steps">');
    for (const e of path) {
      out.push('<li>Di ' + nodeRef(ctx, e.from) + ': <b>' + escapeHtml(stepText(e)) + '</b> → ' + nodeRef(ctx, e.to) + '</li>');
    }
    out.push('</ol></li>');
  }
  out.push('</ol>');

  const unreachable = (s.screens || []).filter((sc) => !(sc.id in ctx.routes));
  if (unreachable.length) {
    out.push('<h3 class="sub">Tidak terjangkau dari layar awal (' + unreachable.length + ')</h3>');
    out.push('<p class="hint">Layar ini hanya tercapai lewat Kembali, dari situs lain, atau belum ada transisi ' +
      'terekam yang menuju ke sana.</p><ul class="plain">');
    for (const sc of unreachable) out.push('<li>' + nodeRef(ctx, sc.id) + '</li>');
    out.push('</ul>');
  }
  out.push('</section>');
}

// ---- transitions

function transitionsSection(out, ctx) {
  const s = ctx.s;
  out.push('<section id="transisi"><h2>Semua transisi (' + (s.edges || []).length + ')</h2>');
  if (!(s.edges || []).length) {
    out.push('<p class="hint">Belum ada transisi yang terekam.</p></section>');
    return;
  }
  out.push('<div class="tw"><table><thead><tr><th>ID</th><th>Dari</th><th>Aksi</th><th>Ke</th>' +
    '<th>Elemen</th><th>Jumlah</th><th>Terakhir</th></tr></thead><tbody>');
  for (const e of s.edges) {
    out.push('<tr id="' + edgeAnchor(e.id) + '"><td class="num">' + escapeHtml(e.id) + '</td><td>' + nodeRef(ctx, e.from) +
      '</td><td>' + escapeHtml(stepText(e)) + '</td><td>' + nodeRef(ctx, e.to) + '</td><td>' + elementCell(e) +
      '</td><td class="num">' + (e.count || 1) + '</td><td class="num">' + escapeHtml(fmtDate(e.lastAt)) + '</td></tr>');
  }
  out.push('</tbody></table></div></section>');
}

function elementCell(e) {
  const el = e.element;
  if (!el) return '<span class="muted">–</span>';
  const parts = [];
  if (el.label || el.text) parts.push(escapeHtml(clip(el.label || el.text, 60)));
  if (el.id) parts.push('<code title="' + escapeAttr(el.id) + '">#' + escapeHtml(clip(el.id, 40)) + '</code>');
  else if (el.tag) parts.push('<span class="muted">' + escapeHtml(el.tag) + '</span>');
  if (el.selector) parts.push('<br><small class="muted">' + escapeHtml(clip(el.selector, 120)) + '</small>');
  return parts.length ? parts.join(' ') : escapeHtml(refDisplay(el));
}

// ---- screens

function screensSection(out, ctx) {
  const s = ctx.s;
  out.push('<section id="layar"><h2>Detail layar (' + (s.screens || []).length + ')</h2>');
  if (!(s.screens || []).length) out.push('<p class="hint">Belum ada layar yang direkam.</p>');
  for (const sc of s.screens || []) screenCard(out, ctx, sc);
  out.push('</section>');
}

function outgoing(session, id) {
  return (session.edges || []).filter((e) => e.from === id);
}
function incoming(session, id) {
  return (session.edges || []).filter((e) => e.to === id);
}

function screenCard(out, ctx, sc) {
  const s = ctx.s;
  const screen = ctx.getScreen(sc.id);
  out.push('<article class="card" id="' + screenAnchor(sc.id) + '"><div class="ch">');
  out.push('<span class="sid">' + escapeHtml(sc.id) + '</span><h3>' + escapeHtml(sc.label) + '</h3>');
  if (sc.id === ctx.startId) out.push('<span class="tag">Layar awal</span>');
  if (!(sc.id in ctx.routes)) out.push('<span class="tag">Tak terjangkau dari awal</span>');
  out.push('<a class="up" href="#peta">↑ Peta</a></div>');

  const sw = (screen && screen.w) || sc.w || 0;
  const sh = (screen && screen.h) || sc.h || 0;

  out.push('<dl class="kv">');
  dt(out, 'URL', screen && screen.url ? '<code>' + escapeHtml(clip(screen.url, 90)) + '</code>' : (sc.url ? '<code>' + escapeHtml(clip(sc.url, 90)) + '</code>' : '–'));
  dt(out, 'Path', sc.path ? '<code>' + escapeHtml(sc.path) + '</code>' : '–');
  if (sc.title) dt(out, 'Judul', escapeHtml(sc.title));
  if (sw > 0 && sh > 0) dt(out, 'Ukuran viewport', sw + ' × ' + sh + ' px');
  dt(out, 'Node', String(sc.nodeCount || (screen ? screen.nodeCount : 0) || 0));
  dt(out, 'Dapat diklik', String(sc.clickableCount || (screen ? screen.clickableCount : 0) || 0));
  dt(out, 'Kunjungan', String(sc.visits || 1));
  dt(out, 'Pertama terlihat', escapeHtml(fmtDate(sc.firstSeen)));
  dt(out, 'Terakhir terlihat', escapeHtml(fmtDate(sc.lastSeen)));
  const route = ctx.routes[sc.id];
  dt(out, 'Rute dari awal', !route ? 'tidak ada' : route.length === 0 ? 'layar awal' : '<a href="#' + routeAnchor(sc.id) + '">' + route.length + ' langkah</a>');
  out.push('</dl>');

  if (!screen || !screen.root) {
    out.push('<p class="hint">Data pohon DOM untuk layar ini tidak ditemukan.</p>');
    navLists(out, ctx, sc.id);
    out.push('</article>');
    return;
  }

  const outgoingByKey = new Map();
  for (const e of outgoing(s, sc.id)) {
    if (!e.element) continue;
    const k = elementKey(e.element);
    if (!outgoingByKey.has(k)) outgoingByKey.set(k, []);
    outgoingByKey.get(k).push(e);
  }

  const clickable = flatten(screen.root)
    .filter((n) => n.clickable && n.visible && !rectEmpty(n.rect))
    .sort((a, b) => a.rect.t - b.rect.t || a.rect.l - b.rect.l);
  const items = clickable.slice(0, MAX_ELEMENT_ROWS).map((n, i) => ({ no: i + 1, node: n, label: labelOf(n) || n.tag, key: elementKey(toElementRef(n)) }));

  out.push('<div class="cb">');
  wireframe(out, sc.id, items, sw, sh);
  out.push('<div class="side">');
  out.push('<h4>Elemen yang dapat diklik (' + clickable.length + ')</h4>');
  if (!items.length) {
    out.push('<p class="hint">Tidak ada elemen yang dapat diklik pada layar ini.</p>');
  } else {
    elementsTable(out, ctx, sc.id, items, outgoingByKey);
    if (clickable.length > items.length) {
      out.push('<p class="hint">Ditampilkan ' + items.length + ' dari ' + clickable.length + ' elemen. Daftar lengkap ada di ekspor CSV/JSON.</p>');
    }
  }
  navLists(out, ctx, sc.id);
  out.push('</div></div>');
  tree(out, ctx, screen, sc);
  out.push('</article>');
}

function wireframe(out, screenId, items, sw, sh) {
  out.push('<figure class="shot">');
  if (!(sw > 0 && sh > 0)) {
    out.push('<figcaption>Tanpa geometri layar</figcaption></figure>');
    return;
  }
  out.push('<div class="frame wire" style="padding-top:' + pct((sh * 100) / sw) + '%">');
  for (const item of items) outlineBox(out, screenId, item, sw, sh);
  out.push('</div><figcaption>Kerangka posisi elemen yang dapat diklik</figcaption></figure>');
}

function outlineBox(out, screenId, item, sw, sh) {
  const b = item.node.rect;
  const l = clamp01(b.l / sw);
  const t = clamp01(b.t / sh);
  const r = clamp01(b.r / sw);
  const btm = clamp01(b.b / sh);
  if (r <= l || btm <= t) return;
  const title = item.node.id ? item.label + ' · #' + item.node.id : item.label;
  out.push(
    '<a class="box" href="#' + elementAnchor(screenId, item.node.idx) + '" title="' + escapeAttr(title) + '" style="left:' +
      pct(l * 100) + '%;top:' + pct(t * 100) + '%;width:' + pct((r - l) * 100) + '%;height:' + pct((btm - t) * 100) +
      '%"><span>' + item.no + '</span></a>',
  );
}

function elementsTable(out, ctx, screenId, items, outgoingByKey) {
  out.push('<div class="tw"><table class="els"><thead><tr><th>#</th><th>Label</th><th>id</th><th>Tag</th>' +
    '<th>Peran</th><th>Bounds</th><th>Menuju</th></tr></thead><tbody>');
  for (const item of items) {
    const n = item.node;
    out.push('<tr id="' + elementAnchor(screenId, n.idx) + '"><td class="num">' + item.no + '</td><td>' + escapeHtml(item.label) + '</td><td>');
    out.push(n.id ? '<code title="' + escapeAttr(n.id) + '">' + escapeHtml(clip(n.id, 30)) + '</code>' : '<span class="muted">–</span>');
    out.push('</td><td>' + escapeHtml(n.tag) + '</td><td>' + (n.role ? escapeHtml(n.role) : '<span class="muted">–</span>') +
      '</td><td class="num">' + escapeHtml(rectStr(n.rect)) + '</td><td>');
    out.push(targetsCell(ctx, outgoingByKey.get(item.key) || []));
    out.push('</td></tr>');
  }
  out.push('</tbody></table></div>');
}

function targetsCell(ctx, edges) {
  if (!edges.length) return '<span class="muted">–</span>';
  const grouped = new Map();
  for (const e of edges) {
    const k = e.to + '|' + e.action;
    const prev = grouped.get(k);
    grouped.set(k, prev ? { e: prev.e, count: prev.count + (e.count || 1) } : { e, count: e.count || 1 });
  }
  const parts = [];
  for (const { e, count } of grouped.values()) {
    let str = nodeRef(ctx, e.to);
    if (e.action !== 'click') str += ' <span class="muted">(' + escapeHtml(actionVerb(e.action).toLowerCase()) + ')</span>';
    if (count > 1) str += ' <span class="muted">×' + count + '</span>';
    parts.push(str);
  }
  return parts.join('<br>');
}

function navLists(out, ctx, screenId) {
  const s = ctx.s;
  const outg = outgoing(s, screenId);
  const inc = incoming(s, screenId);
  out.push('<h4>Navigasi</h4><ul class="nav"><li><b>Keluar (' + outg.length + '):</b> ');
  if (!outg.length) out.push('<span class="muted">belum ada</span>');
  outg.forEach((e, i) => {
    if (i > 0) out.push('; ');
    out.push(escapeHtml(stepText(e)) + ' → ' + nodeRef(ctx, e.to));
    if (e.count > 1) out.push(' <span class="muted">×' + e.count + '</span>');
  });
  out.push('</li><li><b>Masuk (' + inc.length + '):</b> ');
  if (!inc.length) out.push('<span class="muted">belum ada</span>');
  inc.forEach((e, i) => {
    if (i > 0) out.push('; ');
    out.push('dari ' + nodeRef(ctx, e.from) + ' (' + escapeHtml(stepText(e)) + ')');
  });
  out.push('</li></ul>');
}

// ---- hierarchy

function tree(out, ctx, screen, sc) {
  const total = screen.nodeCount || sc.nodeCount || flatten(screen.root).length;
  out.push('<details class="tree"><summary>Hierarki lengkap (' + total + ' node)</summary><div class="kids">');
  const st = { emitted: 0, budget: ctx.treeBudget, truncated: false };
  treeNode(out, screen.root, 0, st);
  out.push('</div>');
  if (st.truncated) {
    out.push('<p class="hint">Hierarki dipotong: ditampilkan ' + st.emitted + ' dari ' + total + ' node (maks. ' +
      ctx.treeBudget + ' node, kedalaman ' + TREE_MAX_DEPTH + '). Data lengkap ada di ekspor JSON/CSV.</p>');
  }
  out.push('</details>');
}

function treeNode(out, n, level, st) {
  if (st.emitted >= st.budget) {
    st.truncated = true;
    return;
  }
  st.emitted++;
  const kids = n.children || [];
  if (!kids.length) {
    out.push('<div class="lf">' + nodeLine(n) + '</div>');
    return;
  }
  if (level >= TREE_MAX_DEPTH) {
    out.push('<div class="lf">' + nodeLine(n) + ' <span class="cnt">(+' + (flatten(n).length - 1) + ' node lebih dalam)</span></div>');
    st.truncated = true;
    return;
  }
  out.push(level < 2 ? '<details open><summary>' : '<details><summary>');
  out.push(nodeLine(n) + ' <span class="cnt">' + kids.length + ' anak</span></summary><div class="kids">');
  for (const c of kids) {
    if (st.emitted >= st.budget) {
      st.truncated = true;
      break;
    }
    treeNode(out, c, level + 1, st);
  }
  out.push('</div></details>');
}

function nodeLine(n) {
  const parts = [];
  parts.push('<span class="ix">' + n.idx + '</span> ');
  parts.push('<code class="c">' + escapeHtml(n.tag) + '</code>');
  if (n.id) parts.push(' <span class="rid" title="id">#' + escapeHtml(clip(n.id, 40)) + '</span>');
  if (n.cls && n.cls.length) parts.push(' <span class="cl">.' + escapeHtml(clip(n.cls.join('.'), 60)) + '</span>');
  if (n.role) parts.push(' <span class="ds">role: ' + escapeHtml(n.role) + '</span>');
  const txt = clean(safeText(n), 80);
  if (txt) parts.push(' <q>' + escapeHtml(txt) + '</q>');
  const name = clean(n.name, 80);
  if (name && name !== txt) parts.push(' <span class="ds">nama: ' + escapeHtml(name) + '</span>');
  const ph = attr(n, 'placeholder');
  if (ph) parts.push(' <span class="ds">petunjuk: ' + escapeHtml(clip(ph, 60)) + '</span>');
  parts.push(' <span class="bd">' + escapeHtml(rectStr(n.rect)) + '</span>');
  for (const f of flagsOf(n)) parts.push(' <span class="fl">' + f + '</span>');
  return parts.join('');
}

function flagsOf(n) {
  const out = [];
  if (n.clickable) out.push('klik');
  if (n.editable) out.push('input');
  if (n.password) out.push('sandi');
  if (n.href) out.push('tautan');
  if (n.visible === false) out.push('tersembunyi');
  return out;
}

function pct(v) {
  return v.toFixed(3);
}
function clamp01(v) {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

const REPORT_CSS = `
:root{--bg:#f5f7fa;--fg:#18202b;--muted:#5b6778;--card:#ffffff;--line:#dde3ea;--accent:#1f4bd8;--node:#eef4fb;--ns:#7ea6d8;--ext:#fff4e5;--es:#c77d00;--edge:#6a7b91;--hl:#e5484d;--hlbg:rgba(229,72,77,.10);--hlbg2:rgba(229,72,77,.30);--code:#eef1f5;--ok:#1a7f37;color-scheme:light dark}
@media (prefers-color-scheme:dark){:root{--bg:#0e1318;--fg:#e2e8f0;--muted:#98a4b5;--card:#161c24;--line:#29323e;--accent:#6f9bff;--node:#1a2736;--ns:#406a9c;--ext:#33260f;--es:#e0a53a;--edge:#8795a8;--hl:#ff6b70;--hlbg:rgba(255,107,112,.14);--hlbg2:rgba(255,107,112,.34);--code:#1e2530;--ok:#56d364}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;overflow-wrap:anywhere}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
code{font:12.5px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code);padding:1px 4px;border-radius:4px}
.wrap{max-width:1120px;margin:0 auto;padding:0 16px}
.top{background:var(--card);border-bottom:1px solid var(--line);padding:20px 0 14px}
.eyebrow{margin:0;color:var(--muted);font-size:12px;letter-spacing:.06em;text-transform:uppercase}
h1{margin:4px 0 2px;font-size:26px;line-height:1.2}
h2{font-size:20px;margin:32px 0 8px}
h3{font-size:17px;margin:0}
h3.sub{font-size:15px;margin:18px 0 4px}
h4{font-size:12.5px;margin:14px 0 6px;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
.pkg{margin:0}
.meta{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:6px 18px;margin:14px 0}
.meta div,.kv div{min-width:0}
.meta dt,.kv dt{font-size:12px;color:var(--muted)}
.meta dd,.kv dd{margin:0}
.stats{display:flex;flex-wrap:wrap;gap:8px;list-style:none;padding:0;margin:10px 0}
.stats li{background:var(--code);border-radius:999px;padding:3px 12px;font-size:13px}
.toc{display:flex;flex-wrap:wrap;gap:4px 18px;font-size:14px;margin-top:6px}
.muted{color:var(--muted)}
.hint{color:var(--muted);font-size:13.5px;margin:4px 0 10px}
section{scroll-margin-top:12px}
.map{overflow:auto;border:1px solid var(--line);border-radius:12px;background:var(--card);margin-top:8px;-webkit-overflow-scrolling:touch}
.map svg{display:block;max-width:none;height:auto}
.fit{margin:0 6px 0 0;vertical-align:middle}
.fit+label{font-size:14px;vertical-align:middle}
.fit:checked~.map svg{width:100%}
.map text{fill:var(--fg);font-size:12px;font-family:inherit}
.map .nb{fill:var(--node);stroke:var(--ns);stroke-width:1.4}
.map .home .nb{stroke:var(--accent);stroke-width:2.6}
.map .unr .nb{stroke-dasharray:5 4}
.map .ext .nb{fill:var(--ext);stroke:var(--es);stroke-dasharray:4 3}
.map .t1{font-size:11px;font-weight:700;fill:var(--accent)}
.map .ext .t1{fill:var(--es)}
.map .st circle{fill:var(--accent)}
.map .st text{fill:#ffffff;font-weight:600}
.map a:hover .nb,.map a:focus .nb{stroke:var(--accent);stroke-width:2.6}
.map .e{fill:none;stroke:var(--edge);stroke-width:1.4}
.map .e.dash{stroke-dasharray:5 4}
.map .ahf{fill:var(--edge)}
.map .el{font-size:10px;fill:var(--muted);paint-order:stroke;stroke:var(--card);stroke-width:3px;stroke-linejoin:round}
.legend{display:flex;flex-wrap:wrap;gap:6px 18px;list-style:none;padding:0;margin:10px 0;font-size:13px;color:var(--muted)}
.lg{display:inline-block;width:26px;height:0;border-top:2px solid var(--edge);vertical-align:middle;margin-right:6px}
.lg.d{border-top-style:dashed}
.lg.b{width:18px;height:12px;border:1.5px solid var(--ns);border-radius:3px;background:var(--node)}
.lg.h{border:2.5px solid var(--accent)}
.lg.u{border-style:dashed}
.lg.x{background:var(--ext);border:1.5px dashed var(--es);border-radius:1px;transform:skewX(-15deg)}
details{margin:8px 0}
summary{cursor:pointer}
pre{background:var(--code);border-radius:8px;padding:12px;overflow:auto;font-size:12.5px;max-height:420px}
pre code{background:none;padding:0}
.tw{overflow-x:auto;border:1px solid var(--line);border-radius:10px;background:var(--card)}
table{border-collapse:collapse;width:100%;font-size:13.5px}
th,td{text-align:left;vertical-align:top;padding:6px 10px;border-bottom:1px solid var(--line)}
th{font-size:12px;color:var(--muted);font-weight:600;background:var(--code);white-space:nowrap}
tr:last-child td{border-bottom:0}
td.num{font-variant-numeric:tabular-nums;white-space:nowrap}
tr:target{background:var(--hlbg)}
.routes>li{margin:8px 0}
.steps{margin:4px 0 0;padding-left:22px;font-size:14px}
.plain{padding-left:20px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin:16px 0;scroll-margin-top:12px}
.card:target{outline:2px solid var(--accent)}
.ch{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.sid{background:var(--accent);color:#ffffff;border-radius:6px;padding:1px 8px;font-weight:700;font-size:13px}
.tag{font-size:12px;border:1px solid var(--line);border-radius:999px;padding:0 8px;color:var(--muted)}
.up{margin-left:auto;font-size:13px}
.kv{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:4px 16px;margin:10px 0;font-size:13.5px}
.cb{display:grid;grid-template-columns:minmax(0,300px) minmax(0,1fr);gap:18px;align-items:start}
.shot{margin:0;width:100%}
.frame{position:relative;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:var(--code)}
.frame.wire{background:repeating-linear-gradient(45deg,transparent,transparent 9px,rgba(120,130,150,.06) 9px,rgba(120,130,150,.06) 10px)}
.box{position:absolute;display:block;border:1.5px solid var(--hl);border-radius:3px;background:var(--hlbg)}
.box span{position:absolute;left:0;top:0;background:var(--hl);color:#ffffff;font-size:9px;line-height:1;padding:1px 2px;border-radius:0 0 3px 0}
.box:hover,.box:focus{background:var(--hlbg2);z-index:2;text-decoration:none}
figcaption{font-size:12px;color:var(--muted);margin-top:4px;text-align:center}
.nav{padding-left:18px;margin:4px 0;font-size:14px}
.nav li{margin:4px 0}
.tree{font-size:13px;margin-top:14px}
.tree .kids{padding-left:12px;border-left:1px dashed var(--line);margin-left:5px}
.tree .lf{padding:1px 0 1px 16px}
.tree summary{padding:1px 0}
.tree .c{color:var(--accent);background:none;padding:0}
.tree .ix{color:var(--muted);font-size:11px;font-variant-numeric:tabular-nums}
.rid{color:var(--ok)}
.cl{color:var(--muted);font-size:11.5px}
.ds{font-style:italic;color:var(--muted)}
.bd{color:var(--muted);font-size:11.5px}
.fl{display:inline-block;font-size:10.5px;border:1px solid var(--line);border-radius:4px;padding:0 4px;color:var(--muted)}
.cnt{font-size:11px;color:var(--muted)}
footer{color:var(--muted);font-size:12.5px;padding:24px 16px 40px}
@media (max-width:760px){.cb{grid-template-columns:minmax(0,1fr)}.shot{max-width:340px;margin:0 auto}h1{font-size:22px}}
`;
