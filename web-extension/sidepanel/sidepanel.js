// UI Mapper Web — Side Panel controller.
// All user-visible strings are Bahasa Indonesia; code/comments in English.
// MV3 CSP-safe: no inline handlers, no eval. Every page-derived string is
// inserted via textContent / createElement — never innerHTML with data.
//
// This module talks to the background service worker over chrome.runtime.
// It prefers helper functions from ../lib/routeGraph.js and ../lib/exporters.js
// (owned by another agent) and falls back to local implementations so the panel
// stays functional regardless of the exact exported names. See probe tables below.

'use strict';

/* ============================================================================
 * Optional lib modules (loaded dynamically so a missing/renamed export can
 * never break the whole panel). Populated by loadLibs() during init.
 * ==========================================================================*/
let routeGraphLib = null;
let exportersLib = null;

async function loadLibs() {
  try { routeGraphLib = await import('../lib/routeGraph.js'); }
  catch (e) { pushLocalLog('sp', 'routeGraph.js tidak dimuat — memakai tata letak internal.'); }
  try { exportersLib = await import('../lib/exporters.js'); }
  catch (e) { pushLocalLog('sp', 'exporters.js tidak dimuat — memakai ekspor internal.'); }
}

/** Return the first function found on `mod` matching one of `names`, else null. */
function pickFn(mod, names) {
  if (!mod) return null;
  for (const n of names) {
    if (typeof mod[n] === 'function') return mod[n];
  }
  return null;
}

/* ============================================================================
 * Small DOM helpers
 * ==========================================================================*/
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/**
 * Create an element. opts: {class, text, title, attrs:{}, on:{}, dataset:{}}.
 * Children may be nodes, strings, numbers, or null (skipped).
 */
function el(tag, opts = {}, ...children) {
  const node = document.createElement(tag);
  if (opts.class) node.className = opts.class;
  if (opts.text != null) node.textContent = String(opts.text);
  if (opts.title != null) node.title = String(opts.title);
  if (opts.attrs) for (const [k, v] of Object.entries(opts.attrs)) node.setAttribute(k, String(v));
  if (opts.dataset) for (const [k, v] of Object.entries(opts.dataset)) node.dataset[k] = String(v);
  if (opts.on) for (const [k, v] of Object.entries(opts.on)) node.addEventListener(k, v);
  for (const c of children) {
    if (c == null || c === false) continue;
    node.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return node;
}

function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }

function svgEl(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
}

/* ============================================================================
 * Utility formatting / escaping (escapers used only for string exporters)
 * ==========================================================================*/
function escHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
function csvCell(v) {
  const s = String(v == null ? '' : v);
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
function fmtTime(ms) {
  if (!ms && ms !== 0) return '—';
  try { return new Date(ms).toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short' }); }
  catch { return new Date(ms).toISOString(); }
}
function fmtClock(ms) {
  try { return new Date(ms).toLocaleTimeString('id-ID', { hour12: false }); }
  catch { return ''; }
}
function safeFileName(s) {
  return String(s || 'sesi').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 60) || 'sesi';
}
function shorten(s, n) {
  s = String(s == null ? '' : s);
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

/* ============================================================================
 * Messaging: side panel -> background (promise-wrapped)
 * ==========================================================================*/
function send(msg) {
  return new Promise((resolve) => {
    try {
      chrome.runtime.sendMessage(msg, (resp) => {
        // Swallow "no receiver" style errors — background may be waking up.
        void chrome.runtime.lastError;
        resolve(resp);
      });
    } catch (e) {
      resolve(undefined);
    }
  });
}

/* ============================================================================
 * Global panel state
 * ==========================================================================*/
const app = {
  state: {
    recording: false, inspecting: false, editing: false,
    tabId: null, origin: null, sessionId: null, currentScreenId: null,
  },
  sessions: [],            // Session[] (summaries + edges)
  pickedNode: null,        // WNode currently shown in properties
  inspectorScreen: null,   // full Screen whose tree is displayed
  inspectorPinned: false,  // true when user opened a screen from Sesi (don't auto-follow)
  openSessionId: null,     // Session detail currently open (or null)
  openSession: null,       // full Session for detail view
  detailSub: 'layar',      // active sub-tab
  graph: { transform: { x: 20, y: 20, k: 1 } },
  log: [],                 // {id,timeMs,tag,message}
  logSeen: new Set(),
  activeTab: 'kontrol',
};

/* ============================================================================
 * Toast + clipboard + download helpers
 * ==========================================================================*/
function toast(message) {
  const host = $('#toast-host');
  if (!host) return;
  const t = el('div', { class: 'toast', text: message });
  host.append(t);
  setTimeout(() => { t.style.opacity = '0'; t.style.transform = 'translateY(8px)'; }, 2100);
  setTimeout(() => t.remove(), 2500);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (e) {
    try {
      const ta = el('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.append(ta);
      ta.focus(); ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch (e2) { return false; }
  }
}

function downloadText(filename, text, mime) {
  const blob = new Blob([text], { type: (mime || 'text/plain') + ';charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = el('a', { attrs: { href: url, download: filename } });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

/* ============================================================================
 * Tab switching
 * ==========================================================================*/
function setTab(name) {
  app.activeTab = name;
  $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  $$('.panel').forEach((p) => p.classList.toggle('active', p.id === 'panel-' + name));
  if (name === 'log') scrollLogToBottom();
}

/* ============================================================================
 * Top bar / Kontrol reflection of live state
 * ==========================================================================*/
function activeSessionSummary() {
  if (!app.state.sessionId) return null;
  return app.sessions.find((s) => s.id === app.state.sessionId) || null;
}

function applyState() {
  const st = app.state;

  // Top bar status
  const dot = $('#statusDot');
  const line = $('#statusLine');
  dot.classList.remove('idle', 'insp', 'rec');
  if (st.recording) {
    dot.classList.add('rec');
    const sess = activeSessionSummary();
    const sc = sess ? sess.screens.length : 0;
    const rc = sess ? sess.edges.length : 0;
    const origin = st.origin || (sess && sess.origin) || '—';
    line.textContent = `Merekam · ${origin} · ${sc} layar · ${rc} rute`;
  } else if (st.inspecting) {
    dot.classList.add('insp');
    line.textContent = 'Mode inspeksi aktif';
  } else {
    dot.classList.add('idle');
    line.textContent = 'Siaga';
  }

  // Kontrol origin
  $('#k-origin').textContent = st.origin || '—';

  // Inspect button
  const bInspect = $('#btn-inspect');
  bInspect.classList.toggle('on', !!st.inspecting);
  $('#st-inspect').textContent = st.inspecting ? 'aktif' : 'nonaktif';

  // Record button
  const bRecord = $('#btn-record');
  bRecord.classList.toggle('on', !!st.recording);
  $('#lbl-record').textContent = st.recording ? 'Berhenti merekam' : 'Rekam rute';
  $('#st-record').textContent = st.recording ? 'merekam' : 'berhenti';

  // Edit button
  const bEdit = $('#btn-edit');
  bEdit.classList.toggle('on', !!st.editing);
  bEdit.classList.toggle('edit-on', !!st.editing);
  $('#st-edit').textContent = st.editing ? 'aktif' : 'nonaktif';

  // Edit sub-panel visibility in Inspektur
  refreshEditPanel();
}

/* ============================================================================
 * INSPEKTUR — properties + DOM tree
 * ==========================================================================*/
function showInspectorEmpty(show) {
  $('#insp-empty').classList.toggle('hidden', !show);
  $('#insp-content').classList.toggle('hidden', show);
}

function renderProps(node) {
  const box = $('#insp-props');
  clear(box);
  if (!node) return;

  const addRow = (label, value, full) => {
    box.append(el('div', { class: 'prop-label', text: label }));
    const v = el('div', { class: 'prop-value' + (full ? ' full' : '') });
    v.append(el('span', { class: 'selectable', text: value == null || value === '' ? '—' : String(value) }));
    box.append(v);
  };

  addRow('Tag', node.tag || '—');
  addRow('ID', node.id || '—');
  addRow('Kelas', Array.isArray(node.cls) && node.cls.length ? node.cls.join(' ') : '—');
  addRow('Peran', node.role || '—');
  addRow('Nama', node.name || '—');
  addRow('Teks', node.text || '—');
  if (node.href) addRow('Tautan', node.href, true);

  // Attributes
  box.append(el('div', { class: 'prop-label', text: 'Atribut' }));
  const attrs = node.attrs && typeof node.attrs === 'object' ? node.attrs : {};
  const keys = Object.keys(attrs);
  if (keys.length) {
    const list = el('div', { class: 'attr-list' });
    for (const k of keys) {
      const item = el('div', { class: 'attr-item' });
      item.append(el('span', { class: 'ak', text: k }));
      item.append(document.createTextNode('="'));
      item.append(el('span', { class: 'av', text: String(attrs[k]) }));
      item.append(document.createTextNode('"'));
      list.append(item);
    }
    box.append(list);
  } else {
    box.append(el('div', { class: 'prop-value', text: '—' }));
  }

  // Rect + size
  const r = node.rect || {};
  const w = (typeof r.r === 'number' && typeof r.l === 'number') ? r.r - r.l : null;
  const h = (typeof r.b === 'number' && typeof r.t === 'number') ? r.b - r.t : null;
  addRow('Posisi', (r.l != null) ? `L ${r.l} · T ${r.t} · R ${r.r} · B ${r.b}` : '—');
  addRow('Ukuran', (w != null) ? `${w} × ${h} px` : '—');

  addRow('Selektor', node.selector || '—', true);
  addRow('XPath', node.xpath || '—', true);

  // Flags
  const chips = el('div', { class: 'chips' });
  chips.append(flagChip('dapat diklik', node.clickable));
  chips.append(flagChip('dapat diedit', node.editable));
  chips.append(flagChip('kata sandi', node.password, true));
  chips.append(flagChip('terlihat', node.visible));
  box.append(chips);
}

function flagChip(label, on, warn) {
  const c = el('span', { class: 'chip' + (on ? ' on' : '') + (warn ? ' warn' : ''), text: (on ? '✓ ' : '· ') + label });
  return c;
}

function nodeToCopyObject(node) {
  const r = node.rect || {};
  return {
    tag: node.tag ?? null,
    id: node.id ?? null,
    cls: Array.isArray(node.cls) ? node.cls : [],
    role: node.role ?? null,
    name: node.name ?? null,
    text: node.text ?? null,
    attrs: node.attrs && typeof node.attrs === 'object' ? node.attrs : {},
    rect: r,
    size: (typeof r.r === 'number') ? { w: r.r - r.l, h: r.b - r.t } : null,
    selector: node.selector ?? null,
    xpath: node.xpath ?? null,
    clickable: !!node.clickable,
    editable: !!node.editable,
    password: !!node.password,
    visible: !!node.visible,
    href: node.href ?? null,
  };
}

function refreshEditPanel() {
  const card = $('#insp-edit');
  const node = app.pickedNode;
  const show = !!(app.state.editing && node && node.editable);
  card.classList.toggle('hidden', !show);
  if (!show) {
    const inp = $('#edit-input');
    if (inp) inp.value = ''; // never keep the field pre-filled
  }
}

function showPickedNode(node) {
  if (!node) return;
  app.pickedNode = node;
  showInspectorEmpty(false);
  renderProps(node);
  refreshEditPanel();
  // Highlight in the tree if the loaded screen contains this idx.
  if (app.inspectorScreen && typeof node.idx === 'number') {
    highlightTreeNode(node.idx, false);
  }
}

/* --- DOM hierarchy tree (lazy, collapsible) --- */
const AUTO_EXPAND_DEPTH = 2;
let treeRowByIdx = new Map();

function renderTree(screen) {
  const host = $('#insp-tree');
  clear(host);
  treeRowByIdx = new Map();
  const meta = $('#tree-meta');

  if (!screen || !screen.root) {
    host.append(el('div', { class: 'tree-empty', text: 'Tangkap layar (📸) atau buka sebuah layar dari tab Sesi untuk melihat hierarki DOM.' }));
    if (meta) meta.textContent = '';
    return;
  }
  if (meta) {
    const nc = screen.nodeCount != null ? screen.nodeCount : '?';
    const cc = screen.clickableCount != null ? screen.clickableCount : '?';
    meta.textContent = `${nc} node · ${cc} klik`;
  }
  host.append(buildTreeNode(screen.root, 0));
}

function buildTreeNode(node, depth) {
  const wrap = el('div', { class: 'tnode' });
  const hasKids = Array.isArray(node.children) && node.children.length > 0;
  const expanded = depth < AUTO_EXPAND_DEPTH;

  const row = el('div', { class: 'trow', dataset: { idx: node.idx } });
  const toggle = el('span', { class: 'ttoggle' + (hasKids ? '' : ' leaf'), text: hasKids ? (expanded ? '▾' : '▸') : '·' });
  row.append(toggle);

  row.append(el('span', { class: 'ttag', text: node.tag || 'node' }));
  if (node.id) row.append(el('span', { class: 'tid', text: '#' + node.id }));
  if (Array.isArray(node.cls) && node.cls.length) {
    row.append(el('span', { class: 'tcls', text: '.' + node.cls.slice(0, 3).join('.') }));
  }
  if (node.clickable) row.append(el('span', { class: 'tmark click', text: '•', title: 'dapat diklik' }));
  if (node.editable) row.append(el('span', { class: 'tmark edit', text: '✎', title: 'dapat diedit' }));

  const kids = el('div', { class: 'tchildren' });
  kids.classList.toggle('hidden', !expanded);
  let built = false;

  const buildKids = () => {
    if (built || !hasKids) return;
    built = true;
    const frag = document.createDocumentFragment();
    for (const child of node.children) frag.append(buildTreeNode(child, depth + 1));
    kids.append(frag);
  };
  if (expanded) buildKids();

  toggle.addEventListener('click', (ev) => {
    ev.stopPropagation();
    if (!hasKids) return;
    const nowHidden = !kids.classList.contains('hidden');
    if (nowHidden) {
      kids.classList.add('hidden');
      toggle.textContent = '▸';
    } else {
      buildKids();
      kids.classList.remove('hidden');
      toggle.textContent = '▾';
    }
  });

  row.addEventListener('click', () => {
    selectTreeRow(node);
  });

  treeRowByIdx.set(node.idx, { row, expand: () => { buildKids(); kids.classList.remove('hidden'); toggle.textContent = hasKids ? '▾' : '·'; } });

  wrap.append(row, kids);
  return wrap;
}

function selectTreeRow(node) {
  // Highlight visually
  $$('.trow.selected', $('#insp-tree')).forEach((r) => r.classList.remove('selected'));
  const entry = treeRowByIdx.get(node.idx);
  if (entry) entry.row.classList.add('selected');
  // Show props from the node we already have
  showPickedNode(node);
  // Ask the page to highlight & scroll to it
  send({ type: 'sp.selectNode', idx: node.idx });
}

function highlightTreeNode(idx, doScroll) {
  const entry = treeRowByIdx.get(idx);
  if (!entry) return;
  $$('.trow.selected', $('#insp-tree')).forEach((r) => r.classList.remove('selected'));
  entry.row.classList.add('selected');
  if (doScroll) entry.row.scrollIntoView({ block: 'center' });
}

async function loadScreenIntoInspector(sessionId, screenId, { pin } = {}) {
  if (!sessionId || !screenId) return;
  // Skip a redundant refetch when the same screen is already shown and this is
  // not an explicit user open (pin === true) — keeps tree expand-state intact.
  if (app.inspectorScreen && app.inspectorScreen.id === screenId && pin !== true) {
    if (pin != null) app.inspectorPinned = !!pin;
    $('#btn-follow').classList.toggle('hidden', !app.inspectorPinned);
    return;
  }
  const screen = await send({ type: 'sp.getScreen', sessionId, screenId });
  if (!screen || !screen.root) {
    pushLocalLog('sp', `Layar ${screenId} tidak dapat dimuat.`);
    return;
  }
  app.inspectorScreen = screen;
  if (pin != null) app.inspectorPinned = !!pin;
  showInspectorEmpty(false);
  renderTree(screen);
  $('#btn-follow').classList.toggle('hidden', !app.inspectorPinned);
}

/* ============================================================================
 * SESI — list + detail
 * ==========================================================================*/
function screenLabel(summary) {
  return summary.label || summary.title || summary.path || summary.url || summary.id;
}

function renderSessionList() {
  const host = $('#sesi-list');
  clear(host);
  if (!app.sessions.length) {
    host.append(el('div', { class: 'empty' },
      el('div', { class: 'empty-mark', text: '🗂️' }),
      el('p', { class: 'empty-title', text: 'Belum ada sesi' }),
      el('p', { class: 'empty-sub', text: 'Mulai “Rekam rute” di tab Kontrol lalu jelajahi aplikasi web untuk membangun peta navigasi.' }),
    ));
    return;
  }
  // Newest first by updatedAt
  const items = app.sessions.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
  for (const s of items) {
    const card = el('div', { class: 'sesi-card', on: { click: () => openSessionDetail(s.id) } });
    const top = el('div', { class: 'sc-top' });
    top.append(el('div', { class: 'sc-name', text: s.name || s.id }));
    if (app.state.recording && app.state.sessionId === s.id) {
      top.append(el('span', { class: 'badge accent', text: 'merekam' }));
    }
    card.append(top);
    card.append(el('div', { class: 'sc-origin', text: s.origin || s.startUrl || '' }));
    const meta = el('div', { class: 'sc-meta' });
    meta.append(el('span', {}, el('b', { text: String((s.screens || []).length) }), ' layar'));
    meta.append(el('span', {}, el('b', { text: String((s.edges || []).length) }), ' rute'));
    meta.append(el('span', { class: 'muted', text: fmtTime(s.updatedAt) }));
    card.append(meta);
    host.append(card);
  }
}

function showSessionList() {
  app.openSessionId = null;
  app.openSession = null;
  $('#sesi-list-view').classList.remove('hidden');
  $('#sesi-detail-view').classList.add('hidden');
  renderSessionList();
}

async function openSessionDetail(id) {
  const session = await send({ type: 'sp.getSession', id });
  if (!session) { toast('Sesi tidak ditemukan'); return; }
  app.openSessionId = id;
  app.openSession = session;
  $('#sesi-list-view').classList.add('hidden');
  const view = $('#sesi-detail-view');
  view.classList.remove('hidden');
  renderSessionDetail();
}

async function refreshOpenSession() {
  if (!app.openSessionId) return;
  const session = await send({ type: 'sp.getSession', id: app.openSessionId });
  if (!session) { showSessionList(); return; }
  app.openSession = session;
  renderSessionDetail();
}

function renderSessionDetail() {
  const view = $('#sesi-detail-view');
  const s = app.openSession;
  clear(view);
  if (!s) return;

  // Header
  const head = el('div', { class: 'detail-head' });
  head.append(el('button', { class: 'btn-sm ghost', text: '‹ Kembali', on: { click: showSessionList } }));
  const nameEl = el('div', { class: 'dh-name', text: s.name || s.id });
  head.append(nameEl);
  head.append(el('button', {
    class: 'btn-sm', text: 'Ganti nama',
    on: { click: () => beginRenameSession(nameEl, s) },
  }));
  head.append(el('button', {
    class: 'btn-sm danger', text: 'Hapus',
    on: { click: () => deleteSession(s) },
  }));
  view.append(head);

  view.append(el('div', { class: 'detail-sub', text: (s.origin || '') + (s.startUrl ? ' · ' + s.startUrl : '') }));

  // Sub-tabs
  const subs = el('div', { class: 'subtabs' });
  const subDefs = [['layar', 'Layar'], ['peta', 'Peta'], ['rute', 'Rute'], ['ekspor', 'Ekspor']];
  for (const [key, label] of subDefs) {
    subs.append(el('button', {
      class: 'subtab' + (app.detailSub === key ? ' active' : ''),
      text: label,
      on: { click: () => { app.detailSub = key; renderSessionDetail(); } },
    }));
  }
  view.append(subs);

  const body = el('div', { class: 'detail-body' });
  view.append(body);

  if (app.detailSub === 'layar') renderLayarSub(body, s);
  else if (app.detailSub === 'peta') renderPetaSub(body, s);
  else if (app.detailSub === 'rute') renderRuteSub(body, s);
  else if (app.detailSub === 'ekspor') renderEksporSub(body, s);
}

function beginRenameSession(nameEl, session) {
  const parent = nameEl.parentNode;
  const input = el('input', { class: 'inline-input' });
  input.value = session.name || '';
  let done = false;
  const commit = async () => {
    if (done) return;
    done = true;
    const v = input.value.trim();
    if (v && v !== session.name) {
      await send({ type: 'sp.renameSession', id: session.id, name: v });
      await refreshSessions();
      await refreshOpenSession();
    } else {
      renderSessionDetail();
    }
  };
  const cancel = () => { if (done) return; done = true; renderSessionDetail(); };
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') commit();
    else if (e.key === 'Escape') cancel();
  });
  input.addEventListener('blur', commit);
  parent.replaceChild(input, nameEl);
  input.focus();
  input.select();
}

async function deleteSession(session) {
  const ok = window.confirm(`Hapus sesi “${session.name || session.id}”? Tindakan ini tidak dapat dibatalkan.`);
  if (!ok) return;
  await send({ type: 'sp.deleteSession', id: session.id });
  await refreshSessions();
  showSessionList();
  toast('Sesi dihapus');
}

/* --- Layar sub-tab --- */
function renderLayarSub(body, session) {
  const screens = session.screens || [];
  if (!screens.length) {
    body.append(el('div', { class: 'empty' },
      el('p', { class: 'empty-title', text: 'Belum ada layar' }),
      el('p', { class: 'empty-sub', text: 'Tangkap layar saat merekam untuk mengisi daftar ini.' })));
    return;
  }
  const list = el('div', { class: 'screen-list' });
  for (const sc of screens) {
    const row = el('div', { class: 'screen-row' });
    row.append(el('span', { class: 'sr-id', text: sc.id }));
    const b = el('div', {
      class: 'sr-body',
      on: {
        click: async () => {
          await loadScreenIntoInspector(session.id, sc.id, { pin: true });
          setTab('inspektur');
          toast('Layar dimuat ke Inspektur');
        },
      },
    });
    b.append(el('div', { class: 'sr-label', text: screenLabel(sc) }));
    b.append(el('div', { class: 'sr-url', text: sc.url || sc.path || '' }));
    const nc = sc.nodeCount != null ? sc.nodeCount : '?';
    const cc = sc.clickableCount != null ? sc.clickableCount : '?';
    const vs = sc.visits != null ? sc.visits : 1;
    b.append(el('div', { class: 'sr-meta', text: `${nc} node · ${cc} klik · ${vs}× kunjungan` }));
    row.append(b);
    const tools = el('div', { class: 'sr-tools' });
    tools.append(el('button', {
      class: 'btn-xs', text: 'Nama',
      on: { click: () => beginRenameScreen(session, sc) },
    }));
    row.append(tools);
    list.append(row);
  }
  body.append(list);
}

function beginRenameScreen(session, sc) {
  const current = sc.label || '';
  const name = window.prompt('Nama layar baru:', current);
  if (name == null) return;
  const v = name.trim();
  (async () => {
    await send({ type: 'sp.renameScreen', sessionId: session.id, screenId: sc.id, label: v });
    await refreshSessions();
    await refreshOpenSession();
  })();
}

/* --- Peta sub-tab (navigation graph) --- */
function buildGraphModel(session) {
  const screens = session.screens || [];
  const edges = session.edges || [];
  const labelFor = (id) => {
    if (id === 'START') return 'MULAI';
    if (typeof id === 'string' && id.startsWith('ext:')) return id.slice(4);
    const sc = screens.find((x) => x.id === id);
    return sc ? shorten(screenLabel(sc), 22) : id;
  };
  const kindFor = (id) => id === 'START' ? 'start' : (typeof id === 'string' && id.startsWith('ext:') ? 'ext' : 'screen');

  const nodeIds = new Set(['START']);
  screens.forEach((s) => nodeIds.add(s.id));
  edges.forEach((e) => { nodeIds.add(e.from); nodeIds.add(e.to); });

  const adj = new Map();
  nodeIds.forEach((id) => adj.set(id, new Set()));
  edges.forEach((e) => { if (adj.has(e.from)) adj.get(e.from).add(e.to); });

  // BFS distance from START (or first screen) => layer index.
  const dist = new Map();
  const start = nodeIds.has('START') ? 'START' : (screens[0] ? screens[0].id : null);
  const queue = [];
  if (start) { dist.set(start, 0); queue.push(start); }
  while (queue.length) {
    const cur = queue.shift();
    const d = dist.get(cur);
    for (const nx of adj.get(cur) || []) {
      if (!dist.has(nx)) { dist.set(nx, d + 1); queue.push(nx); }
    }
  }
  let maxD = 0;
  dist.forEach((v) => { if (v > maxD) maxD = v; });
  const orphanLayer = maxD + 1;
  nodeIds.forEach((id) => { if (!dist.has(id)) dist.set(id, orphanLayer); });

  const layers = new Map();
  nodeIds.forEach((id) => {
    const d = dist.get(id);
    if (!layers.has(d)) layers.set(d, []);
    layers.get(d).push(id);
  });

  const NODE_W = 148, NODE_H = 42, GAP_X = 96, GAP_Y = 26, PAD = 30;
  const nodes = [];
  const sortedLayers = [...layers.keys()].sort((a, b) => a - b);
  sortedLayers.forEach((d) => {
    const ids = layers.get(d);
    ids.forEach((id, i) => {
      nodes.push({
        id, label: labelFor(id), kind: kindFor(id),
        x: PAD + d * (NODE_W + GAP_X),
        y: PAD + i * (NODE_H + GAP_Y),
        w: NODE_W, h: NODE_H,
      });
    });
  });
  const pos = new Map(nodes.map((n) => [n.id, n]));

  const gedges = edges.map((e) => ({
    id: e.id,
    from: e.from, to: e.to,
    label: edgeShortLabel(e),
  }));

  return { nodes, edges: gedges, pos };
}

function edgeShortLabel(e) {
  const elx = e.element || {};
  const raw = elx.label || elx.text || (elx.tag ? elx.tag : '') || e.action || '';
  return shorten(raw, 18);
}

/** Prefer routeGraph lib layout if it returns a usable {nodes,edges} shape. */
function computeGraph(session) {
  const fn = pickFn(routeGraphLib, ['layout', 'layoutGraph', 'buildGraph', 'computeLayout', 'graph', 'toGraph']);
  if (fn) {
    try {
      const g = fn(session);
      if (g && Array.isArray(g.nodes) && g.nodes.length &&
          g.nodes.every((n) => n && typeof n.x === 'number' && typeof n.y === 'number')) {
        if (!(g.pos instanceof Map)) g.pos = new Map(g.nodes.map((n) => [n.id, n]));
        if (!Array.isArray(g.edges)) g.edges = [];
        return g;
      }
    } catch (e) { /* fall back */ }
  }
  return buildGraphModel(session);
}

function renderPetaSub(body, session) {
  const g = computeGraph(session);
  if (!g.nodes.length) {
    body.append(el('div', { class: 'empty' }, el('p', { class: 'empty-title', text: 'Peta kosong' })));
    return;
  }

  const wrap = el('div', { class: 'graph-wrap' });
  const svg = svgEl('svg', { xmlns: 'http://www.w3.org/2000/svg' });

  // arrow marker
  const defs = svgEl('defs');
  const marker = svgEl('marker', {
    id: 'uim-arrow', viewBox: '0 0 10 10', refX: '9', refY: '5',
    markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse',
  });
  const markerPath = svgEl('path', { d: 'M0,0 L10,5 L0,10 z' });
  markerPath.style.fill = 'var(--muted)'; // CSS property context so var() resolves
  marker.append(markerPath);
  defs.append(marker);
  svg.append(defs);

  const root = svgEl('g', { class: 'graph-root' });
  svg.append(root);

  // Edges (under nodes)
  const edgeLayer = svgEl('g', {});
  root.append(edgeLayer);
  for (const e of g.edges) {
    const a = g.pos.get(e.from);
    const b = g.pos.get(e.to);
    if (!a || !b) continue;
    const x1 = a.x + a.w, y1 = a.y + a.h / 2;
    const x2 = b.x, y2 = b.y + b.h / 2;
    const midx = (x1 + x2) / 2;
    const path = svgEl('path', {
      d: `M ${x1} ${y1} C ${midx} ${y1}, ${midx} ${y2}, ${x2} ${y2}`,
      fill: 'none', 'marker-end': 'url(#uim-arrow)',
    });
    // Themed colors via CSS-property context so var() resolves in Chrome.
    path.style.stroke = 'var(--muted)';
    path.style.strokeWidth = '1.4';
    path.style.opacity = '0.75';
    edgeLayer.append(path);
    if (e.label) {
      const lx = (x1 + x2) / 2, ly = (y1 + y2) / 2 - 4;
      const lbl = svgEl('text', { class: 'gedge-label', x: lx, y: ly, 'text-anchor': 'middle' });
      lbl.style.fill = 'var(--muted)';
      lbl.textContent = e.label; // textContent — safe
      const tw = Math.max(14, e.label.length * 5.4);
      const bg = svgEl('rect', { x: lx - tw / 2 - 3, y: ly - 10, width: tw + 6, height: 13, rx: 3 });
      bg.style.fill = 'var(--card)';
      bg.style.opacity = '0.85';
      edgeLayer.append(bg, lbl);
    }
  }

  // Nodes
  const nodeLayer = svgEl('g', {});
  root.append(nodeLayer);
  for (const n of g.nodes) {
    const grp = svgEl('g', { class: 'gnode', transform: `translate(${n.x},${n.y})` });
    let fill = 'var(--card)', stroke = 'var(--border-strong)', ink = 'var(--text)';
    if (n.kind === 'start') { fill = 'color-mix(in srgb, var(--accent) 16%, var(--card))'; stroke = 'var(--accent)'; ink = 'var(--accent)'; }
    else if (n.kind === 'ext') { fill = 'color-mix(in srgb, var(--amber) 26%, var(--card))'; stroke = 'var(--amber)'; ink = 'var(--amber-ink)'; }
    const rect = svgEl('rect', { class: 'gnode-rect', width: n.w, height: n.h, rx: 8 });
    // Themed colors via CSS-property context so var()/color-mix() resolve in Chrome.
    rect.style.fill = fill;
    rect.style.stroke = stroke;
    rect.style.strokeWidth = '1.5';
    grp.append(rect);
    const idTxt = svgEl('text', { x: 10, y: 16, class: 'gnode-label', 'font-weight': '700', 'font-size': '10' });
    idTxt.style.fill = ink;
    idTxt.textContent = n.kind === 'screen' ? n.id : (n.kind === 'start' ? '▶' : 'EXT');
    grp.append(idTxt);
    const lblTxt = svgEl('text', { x: 10, y: 31, class: 'gnode-label' });
    lblTxt.style.fill = ink;
    lblTxt.textContent = n.label;
    grp.append(lblTxt);
    const title = svgEl('title');
    title.textContent = n.id + ' — ' + n.label;
    grp.append(title);

    if (n.kind === 'screen') {
      grp.style.cursor = 'pointer';
      grp.addEventListener('click', async () => {
        await loadScreenIntoInspector(session.id, n.id, { pin: true });
        setTab('inspektur');
        toast('Layar ' + n.id + ' dimuat');
      });
    }
    nodeLayer.append(grp);
  }

  wrap.append(svg);

  // Toolbar
  const tb = el('div', { class: 'graph-toolbar' });
  const applyTransform = () => {
    const t = app.graph.transform;
    root.setAttribute('transform', `translate(${t.x},${t.y}) scale(${t.k})`);
  };
  tb.append(el('button', { class: 'gbtn', text: '＋', title: 'Perbesar', on: { click: () => { app.graph.transform.k = Math.min(3, app.graph.transform.k * 1.2); applyTransform(); } } }));
  tb.append(el('button', { class: 'gbtn', text: '－', title: 'Perkecil', on: { click: () => { app.graph.transform.k = Math.max(0.25, app.graph.transform.k / 1.2); applyTransform(); } } }));
  tb.append(el('button', { class: 'gbtn', text: '⟲', title: 'Setel ulang', on: { click: () => { app.graph.transform = { x: 20, y: 20, k: 1 }; applyTransform(); } } }));
  wrap.append(tb);

  // Legend
  const legend = el('div', { class: 'graph-legend' });
  const lg = (color, text) => el('span', { class: 'lg' }, el('span', { class: 'sw', attrs: { style: `background:${color}` } }), text);
  legend.append(lg('var(--accent)', 'Mulai'));
  legend.append(lg('var(--card)', 'Layar'));
  legend.append(lg('var(--amber)', 'Eksternal'));
  wrap.append(legend);

  body.append(wrap);
  applyTransform();

  // Pan + zoom interactions
  let dragging = false, lastX = 0, lastY = 0;
  svg.addEventListener('pointerdown', (e) => {
    dragging = true; lastX = e.clientX; lastY = e.clientY;
    svg.classList.add('dragging');
    svg.setPointerCapture(e.pointerId);
  });
  svg.addEventListener('pointermove', (e) => {
    if (!dragging) return;
    app.graph.transform.x += (e.clientX - lastX);
    app.graph.transform.y += (e.clientY - lastY);
    lastX = e.clientX; lastY = e.clientY;
    applyTransform();
  });
  const endDrag = (e) => {
    dragging = false;
    svg.classList.remove('dragging');
    try { svg.releasePointerCapture(e.pointerId); } catch (err) { /* ignore */ }
  };
  svg.addEventListener('pointerup', endDrag);
  svg.addEventListener('pointercancel', endDrag);
  svg.addEventListener('wheel', (e) => {
    e.preventDefault();
    const rect = svg.getBoundingClientRect();
    const px = e.clientX - rect.left, py = e.clientY - rect.top;
    const t = app.graph.transform;
    const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12;
    const nk = Math.min(3, Math.max(0.25, t.k * factor));
    // zoom around cursor
    t.x = px - (px - t.x) * (nk / t.k);
    t.y = py - (py - t.y) * (nk / t.k);
    t.k = nk;
    applyTransform();
  }, { passive: false });
}

/* --- Rute sub-tab --- */
function nodeLabelFor(session, id) {
  if (id === 'START') return 'MULAI';
  if (typeof id === 'string' && id.startsWith('ext:')) return id.slice(4);
  const sc = (session.screens || []).find((x) => x.id === id);
  return sc ? screenLabel(sc) : id;
}
function nodeKindFor(id) {
  return id === 'START' ? 'start' : (typeof id === 'string' && id.startsWith('ext:') ? 'ext' : 'screen');
}

function localRoutesFromStart(session, maxRoutes = 120, maxDepth = 14) {
  const edges = session.edges || [];
  const adj = new Map();
  edges.forEach((e) => { if (!adj.has(e.from)) adj.set(e.from, []); adj.get(e.from).push(e); });
  const start = edges.some((e) => e.from === 'START') ? 'START' : (((session.screens || [])[0]) || {}).id;
  const routes = [];
  if (!start) return routes;
  const dfs = (node, path, visited) => {
    if (routes.length >= maxRoutes) return;
    const outs = adj.get(node) || [];
    if (!outs.length || path.length >= maxDepth) {
      if (path.length) routes.push(path.slice());
      return;
    }
    let advanced = false;
    for (const e of outs) {
      if (visited.has(e.to)) continue;
      advanced = true;
      visited.add(e.to);
      path.push(e);
      dfs(e.to, path, visited);
      path.pop();
      visited.delete(e.to);
      if (routes.length >= maxRoutes) return;
    }
    if (!advanced && path.length) routes.push(path.slice());
  };
  dfs(start, [], new Set([start]));
  return routes;
}

function localShortestPath(session, from, to) {
  if (from === to) return [];
  const edges = session.edges || [];
  const adj = new Map();
  edges.forEach((e) => { if (!adj.has(e.from)) adj.set(e.from, []); adj.get(e.from).push(e); });
  const prev = new Map();
  const seen = new Set([from]);
  const queue = [from];
  while (queue.length) {
    const cur = queue.shift();
    for (const e of adj.get(cur) || []) {
      if (seen.has(e.to)) continue;
      seen.add(e.to);
      prev.set(e.to, e);
      if (e.to === to) {
        const path = [];
        let c = to;
        while (prev.has(c)) { const pe = prev.get(c); path.unshift(pe); c = pe.from; }
        return path;
      }
      queue.push(e.to);
    }
  }
  return null;
}

function routesFromStart(session) {
  const fn = pickFn(routeGraphLib, ['routesFromStart', 'routes', 'enumerateRoutes', 'allRoutes', 'pathsFromStart']);
  if (fn) {
    try {
      const r = fn(session);
      if (Array.isArray(r)) return normalizeRoutes(session, r);
    } catch (e) { /* fall back */ }
  }
  return localRoutesFromStart(session);
}

/** Accept either arrays of edges or arrays of ids and normalize to edge arrays. */
function normalizeRoutes(session, raw) {
  const byId = new Map((session.edges || []).map((e) => [e.id, e]));
  return raw.map((route) => {
    if (!Array.isArray(route)) {
      if (route && Array.isArray(route.edges)) route = route.edges;
      else return [];
    }
    return route.map((step) => {
      if (step && typeof step === 'object' && (step.from || step.to)) return step;
      if (typeof step === 'string' && byId.has(step)) return byId.get(step);
      return step;
    }).filter((s) => s && typeof s === 'object' && s.from);
  }).filter((r) => r.length);
}

function shortestPath(session, from, to) {
  const fn = pickFn(routeGraphLib, ['shortestPath', 'findPath', 'path', 'route']);
  if (fn) {
    try {
      const r = fn(session, from, to);
      if (Array.isArray(r)) return normalizeRoutes(session, [r])[0] || (r.length ? null : []);
      if (r === null) return null;
    } catch (e) { /* fall back */ }
  }
  return localShortestPath(session, from, to);
}

function renderRouteChain(session, edges) {
  const item = el('div', { class: 'route-item' });
  if (!edges || !edges.length) {
    item.append(el('span', { class: 'muted', text: 'Rute langsung tanpa transisi.' }));
    return item;
  }
  const first = edges[0];
  item.append(el('span', { class: 'rnode ' + nodeKindFor(first.from), text: nodeLabelFor(session, first.from) }));
  for (const e of edges) {
    const seg = el('span', { class: 'rseg' });
    seg.append(el('span', { class: 'rarrow', text: '→' }));
    const lbl = edgeShortLabel(e) || e.action || '';
    if (lbl) seg.append(el('span', { class: 'redge-label', text: lbl }));
    seg.append(el('span', { class: 'rarrow', text: '→' }));
    seg.append(el('span', { class: 'rnode ' + nodeKindFor(e.to), text: nodeLabelFor(session, e.to) }));
    item.append(seg);
  }
  return item;
}

function renderRuteSub(body, session) {
  // 1) Path finder
  const pfBlock = el('div', { class: 'route-block' });
  pfBlock.append(el('div', { class: 'rb-title', text: 'Cari rute terpendek' }));
  const finder = el('div', { class: 'path-finder' });
  const ids = ['START', ...(session.screens || []).map((s) => s.id),
    ...uniqueExtIds(session)];
  const selFrom = el('select');
  const selTo = el('select');
  for (const id of ids) {
    selFrom.append(el('option', { attrs: { value: id }, text: `${id === 'START' ? 'MULAI' : id} · ${shorten(nodeLabelFor(session, id), 20)}` }));
    selTo.append(el('option', { attrs: { value: id }, text: `${id === 'START' ? 'MULAI' : id} · ${shorten(nodeLabelFor(session, id), 20)}` }));
  }
  if (ids.length > 1) selTo.value = ids[1];
  finder.append(selFrom, el('span', { class: 'rarrow', text: '→' }), selTo);
  const findBtn = el('button', { class: 'btn-sm primary', text: 'Cari' });
  finder.append(findBtn);
  pfBlock.append(finder);
  const pfResult = el('div', { class: 'pf-result' });
  pfBlock.append(pfResult);
  findBtn.addEventListener('click', () => {
    clear(pfResult);
    const from = selFrom.value, to = selTo.value;
    const path = shortestPath(session, from, to);
    if (path === null) {
      pfResult.append(el('div', { class: 'muted', text: 'Tidak ada rute dari sana ke sana.' }));
    } else {
      pfResult.append(renderRouteChain(session, path));
    }
  });
  body.append(pfBlock);

  // 2) Routes from start
  const rfsBlock = el('div', { class: 'route-block' });
  rfsBlock.append(el('div', { class: 'rb-title', text: 'Rute dari layar awal' }));
  const routes = routesFromStart(session);
  if (!routes.length) {
    rfsBlock.append(el('div', { class: 'muted', text: 'Belum ada transisi terekam.' }));
  } else {
    for (const r of routes) rfsBlock.append(renderRouteChain(session, r));
  }
  body.append(rfsBlock);

  // 3) All transitions (with delete)
  const trBlock = el('div', { class: 'route-block' });
  trBlock.append(el('div', { class: 'rb-title', text: `Semua transisi (${(session.edges || []).length})` }));
  const edges = (session.edges || []).slice().sort((a, b) => (a.firstAt || 0) - (b.firstAt || 0));
  if (!edges.length) {
    trBlock.append(el('div', { class: 'muted', text: 'Belum ada transisi.' }));
  }
  for (const e of edges) {
    const row = el('div', { class: 'trans-row' });
    const b = el('div', { class: 'tr-body' });
    const path = el('div', { class: 'tr-path' });
    path.append(el('span', { class: 'rnode ' + nodeKindFor(e.from), text: nodeLabelFor(session, e.from) }));
    path.append(document.createTextNode('  →  '));
    path.append(el('span', { class: 'rnode ' + nodeKindFor(e.to), text: nodeLabelFor(session, e.to) }));
    b.append(path);
    const elx = e.element || {};
    const desc = [e.action, elx.label || elx.text || '', elx.selector || ''].filter(Boolean).join(' · ');
    if (desc) b.append(el('div', { class: 'tr-el', text: desc }));
    row.append(b);
    row.append(el('span', { class: 'tr-count', text: '×' + (e.count || 1) }));
    row.append(el('button', {
      class: 'btn-xs danger', text: 'Hapus',
      on: {
        click: async () => {
          await send({ type: 'sp.deleteEdge', sessionId: session.id, edgeId: e.id });
          await refreshSessions();
          await refreshOpenSession();
        },
      },
    }));
    trBlock.append(row);
  }
  body.append(trBlock);
}

function uniqueExtIds(session) {
  const set = new Set();
  (session.edges || []).forEach((e) => {
    if (typeof e.to === 'string' && e.to.startsWith('ext:')) set.add(e.to);
    if (typeof e.from === 'string' && e.from.startsWith('ext:')) set.add(e.from);
  });
  return [...set];
}

/* --- Ekspor sub-tab --- */
const EXPORT_FORMATS = [
  { key: 'json', fmt: 'JSON', desc: 'Data lengkap sesi + pohon', ext: 'json', mime: 'application/json' },
  { key: 'mermaid', fmt: 'Mermaid', desc: 'Diagram graph TD', ext: 'mmd', mime: 'text/plain' },
  { key: 'dot', fmt: 'Graphviz DOT', desc: 'digraph untuk Graphviz', ext: 'dot', mime: 'text/vnd.graphviz' },
  { key: 'csv', fmt: 'CSV', desc: 'Tabel transisi', ext: 'csv', mime: 'text/csv' },
  { key: 'html', fmt: 'HTML', desc: 'Laporan offline', ext: 'html', mime: 'text/html' },
];

function renderEksporSub(body, session) {
  const grid = el('div', { class: 'export-grid' });
  for (const f of EXPORT_FORMATS) {
    grid.append(el('button', {
      class: 'export-btn',
      on: { click: () => doExport(session, f) },
    },
      el('span', { class: 'eb-fmt', text: f.fmt }),
      el('span', { class: 'eb-desc', text: f.desc }),
    ));
  }
  body.append(grid);

  const noteRow = el('div', { class: 'export-note' });
  noteRow.append(el('button', {
    class: 'btn-sm', text: 'Salin Mermaid',
    on: {
      click: async () => {
        const screens = await gatherScreens(session);
        const text = await produceExport('mermaid', session, screens);
        const ok = await copyText(text);
        toast(ok ? 'Mermaid disalin' : 'Gagal menyalin');
      },
    },
  }));
  body.append(noteRow);
  body.append(el('p', { class: 'hint', text: 'Ekspor dibuat dari data lokal. JSON dan HTML memuat pohon hierarki setiap layar.' }));
}

async function gatherScreens(session) {
  // Load full Screen trees for JSON/HTML/CSV completeness.
  const map = {};
  for (const sc of session.screens || []) {
    const full = await send({ type: 'sp.getScreen', sessionId: session.id, screenId: sc.id });
    if (full && full.root) map[sc.id] = full;
  }
  return map;
}

async function doExport(session, f) {
  try {
    const needsScreens = (f.key === 'json' || f.key === 'html' || f.key === 'csv');
    const screens = needsScreens ? await gatherScreens(session) : {};
    const text = await produceExport(f.key, session, screens);
    const name = `uimapper-${safeFileName(session.name || session.id)}.${f.ext}`;
    downloadText(name, text, f.mime);
    toast(`Ekspor ${f.fmt} dibuat`);
    pushLocalLog('sp', `Ekspor ${f.fmt} untuk sesi ${session.id}.`);
  } catch (e) {
    toast('Ekspor gagal');
    pushLocalLog('err', `Ekspor ${f.fmt} gagal: ${e && e.message ? e.message : e}`);
  }
}

/** Prefer exporters lib; fall back to local generators. Returns a string. */
async function produceExport(key, session, screens) {
  const probe = {
    json: ['toJSON', 'exportJSON', 'json', 'asJSON'],
    mermaid: ['toMermaid', 'exportMermaid', 'mermaid', 'asMermaid'],
    dot: ['toDOT', 'toDot', 'exportDOT', 'dot', 'asDot', 'graphviz'],
    csv: ['toCSV', 'exportCSV', 'csv', 'asCSV'],
    html: ['toHTML', 'exportHTML', 'html', 'asHTML', 'report'],
  }[key];
  const fn = pickFn(exportersLib, probe);
  if (fn) {
    try {
      const out = await fn(session, screens);
      if (typeof out === 'string' && out.length) return out;
    } catch (e) {
      pushLocalLog('sp', `exporters.${key} gagal — memakai ekspor internal.`);
    }
  }
  return localExport(key, session, screens);
}

/* --- Local export fallbacks --- */
function localExport(key, session, screens) {
  if (key === 'json') return localJSON(session, screens);
  if (key === 'mermaid') return localMermaid(session);
  if (key === 'dot') return localDOT(session);
  if (key === 'csv') return localCSV(session);
  if (key === 'html') return localHTML(session, screens);
  return '';
}

function localJSON(session, screens) {
  const out = {
    id: session.id,
    name: session.name,
    origin: session.origin,
    startUrl: session.startUrl,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    screens: (session.screens || []).map((s) => screens[s.id] || s),
    edges: session.edges || [],
  };
  return JSON.stringify(out, null, 2);
}

function mermaidId(id) {
  if (id === 'START') return 'START';
  if (typeof id === 'string' && id.startsWith('ext:')) return 'EXT_' + id.slice(4).replace(/[^A-Za-z0-9]/g, '_');
  return String(id).replace(/[^A-Za-z0-9]/g, '_');
}
function mermaidLabel(s) {
  return String(s == null ? '' : s).replace(/"/g, '&quot;').replace(/[\r\n]+/g, ' ');
}

function localMermaid(session) {
  const lines = ['graph TD'];
  const seen = new Set();
  const declare = (id) => {
    const mid = mermaidId(id);
    if (seen.has(mid)) return;
    seen.add(mid);
    if (id === 'START') lines.push(`  ${mid}(["MULAI"])`);
    else if (typeof id === 'string' && id.startsWith('ext:')) lines.push(`  ${mid}[/"${mermaidLabel(id.slice(4))}"/]`);
    else lines.push(`  ${mid}["${mermaidLabel(nodeLabelFor(session, id))}"]`);
  };
  declare('START');
  (session.screens || []).forEach((s) => declare(s.id));
  (session.edges || []).forEach((e) => { declare(e.from); declare(e.to); });
  (session.edges || []).forEach((e) => {
    const lbl = mermaidLabel(edgeShortLabel(e) || e.action || '');
    lines.push(`  ${mermaidId(e.from)} -->|"${lbl}"| ${mermaidId(e.to)}`);
  });
  return lines.join('\n') + '\n';
}

function dotStr(s) { return String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/[\r\n]+/g, ' '); }

function localDOT(session) {
  const lines = ['digraph UIMap {', '  rankdir=LR;', '  node [shape=box, style="rounded,filled", fontsize=10, fillcolor="#ffffff"];', '  edge [fontsize=9];'];
  const seen = new Set();
  const declare = (id) => {
    if (seen.has(id)) return;
    seen.add(id);
    if (id === 'START') lines.push(`  "START" [label="MULAI", shape=ellipse, fillcolor="#0F3D5E", fontcolor="#ffffff"];`);
    else if (typeof id === 'string' && id.startsWith('ext:')) lines.push(`  "${dotStr(id)}" [label="${dotStr(id.slice(4))}", fillcolor="#FFC940"];`);
    else lines.push(`  "${dotStr(id)}" [label="${dotStr(id + ': ' + nodeLabelFor(session, id))}"];`);
  };
  declare('START');
  (session.screens || []).forEach((s) => declare(s.id));
  (session.edges || []).forEach((e) => { declare(e.from); declare(e.to); });
  (session.edges || []).forEach((e) => {
    const lbl = dotStr(edgeShortLabel(e) || e.action || '');
    lines.push(`  "${dotStr(e.from)}" -> "${dotStr(e.to)}" [label="${lbl}"];`);
  });
  lines.push('}');
  return lines.join('\n') + '\n';
}

function localCSV(session) {
  const rows = [['edge_id', 'from', 'from_label', 'to', 'to_label', 'action', 'element_label', 'element_selector', 'count', 'first_at', 'last_at']];
  for (const e of session.edges || []) {
    const elx = e.element || {};
    rows.push([
      e.id, e.from, nodeLabelFor(session, e.from), e.to, nodeLabelFor(session, e.to),
      e.action || '', elx.label || elx.text || '', elx.selector || '',
      e.count || 1, e.firstAt || '', e.lastAt || '',
    ]);
  }
  return rows.map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

function localHTML(session, screens) {
  const g = buildGraphModel(session);
  let maxX = 0, maxY = 0;
  g.nodes.forEach((n) => { maxX = Math.max(maxX, n.x + n.w); maxY = Math.max(maxY, n.y + n.h); });
  const svgParts = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${maxX + 30} ${maxY + 30}" width="100%">`];
  svgParts.push('<defs><marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#888"/></marker></defs>');
  for (const e of g.edges) {
    const a = g.pos.get(e.from), b = g.pos.get(e.to);
    if (!a || !b) continue;
    const x1 = a.x + a.w, y1 = a.y + a.h / 2, x2 = b.x, y2 = b.y + b.h / 2, mx = (x1 + x2) / 2;
    svgParts.push(`<path d="M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}" fill="none" stroke="#888" stroke-width="1.3" marker-end="url(#ar)" opacity="0.7"/>`);
    if (e.label) svgParts.push(`<text x="${mx}" y="${(y1 + y2) / 2 - 3}" font-size="9" fill="#666" text-anchor="middle" font-family="sans-serif">${escHtml(e.label)}</text>`);
  }
  for (const n of g.nodes) {
    const fill = n.kind === 'start' ? '#0F3D5E' : (n.kind === 'ext' ? '#FFC940' : '#ffffff');
    const ink = n.kind === 'start' ? '#ffffff' : '#172230';
    svgParts.push(`<g transform="translate(${n.x},${n.y})"><rect width="${n.w}" height="${n.h}" rx="8" fill="${fill}" stroke="#0F3D5E" stroke-width="1.4"/><text x="10" y="17" font-size="10" font-weight="700" fill="${ink}" font-family="sans-serif">${escHtml(n.kind === 'screen' ? n.id : (n.kind === 'start' ? 'MULAI' : 'EXT'))}</text><text x="10" y="31" font-size="10" fill="${ink}" font-family="sans-serif">${escHtml(n.label)}</text></g>`);
  }
  svgParts.push('</svg>');

  const screenRows = (session.screens || []).map((s) => {
    const full = screens[s.id];
    const nc = (full && full.nodeCount != null) ? full.nodeCount : (s.nodeCount != null ? s.nodeCount : '?');
    const cc = (full && full.clickableCount != null) ? full.clickableCount : (s.clickableCount != null ? s.clickableCount : '?');
    return `<tr><td>${escHtml(s.id)}</td><td>${escHtml(screenLabel(s))}</td><td class="mono">${escHtml(s.url || s.path || '')}</td><td>${escHtml(nc)}</td><td>${escHtml(cc)}</td></tr>`;
  }).join('');

  const edgeRows = (session.edges || []).map((e) => {
    const elx = e.element || {};
    return `<tr><td>${escHtml(nodeLabelFor(session, e.from))}</td><td>${escHtml(nodeLabelFor(session, e.to))}</td><td>${escHtml(e.action || '')}</td><td>${escHtml(elx.label || elx.text || '')}</td><td>${escHtml(e.count || 1)}</td></tr>`;
  }).join('');

  return `<!doctype html>
<html lang="id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>UI Mapper Web — ${escHtml(session.name || session.id)}</title>
<style>
  :root{--accent:#0F3D5E;--amber:#FFC940}
  body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;color:#172230;background:#f5f7fa}
  header{background:var(--accent);color:#fff;padding:16px 20px}
  header h1{margin:0;font-size:18px}
  header .sub{opacity:.85;font-size:13px;font-family:ui-monospace,monospace}
  main{max-width:960px;margin:0 auto;padding:18px 20px}
  h2{font-size:14px;text-transform:uppercase;letter-spacing:.05em;color:#62748a;border-bottom:2px solid var(--amber);padding-bottom:4px;margin-top:26px}
  table{border-collapse:collapse;width:100%;font-size:13px;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.08)}
  th,td{border:1px solid #e2e8f0;padding:6px 9px;text-align:left;vertical-align:top}
  th{background:#eef2f6}
  .mono{font-family:ui-monospace,monospace;font-size:12px;word-break:break-all}
  .map{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:10px;overflow:auto}
  footer{color:#8497a9;font-size:11px;padding:20px;text-align:center}
</style></head>
<body>
<header>
  <h1>UI Mapper Web — ${escHtml(session.name || session.id)}</h1>
  <div class="sub">${escHtml(session.origin || '')} · ${escHtml((session.screens || []).length)} layar · ${escHtml((session.edges || []).length)} rute · dibuat ${escHtml(fmtTime(session.createdAt))}</div>
</header>
<main>
  <h2>Peta navigasi</h2>
  <div class="map">${svgParts.join('')}</div>
  <h2>Layar</h2>
  <table><thead><tr><th>ID</th><th>Label</th><th>URL</th><th>Node</th><th>Klik</th></tr></thead><tbody>${screenRows || '<tr><td colspan="5">—</td></tr>'}</tbody></table>
  <h2>Transisi</h2>
  <table><thead><tr><th>Dari</th><th>Ke</th><th>Aksi</th><th>Elemen</th><th>Jumlah</th></tr></thead><tbody>${edgeRows || '<tr><td colspan="5">—</td></tr>'}</tbody></table>
</main>
<footer>Dihasilkan oleh UI Mapper Web · ${escHtml(fmtTime(Date.now()))} · semua data lokal</footer>
</body></html>`;
}

/* ============================================================================
 * LOG
 * ==========================================================================*/
const TAG_PALETTE = ['#5aa9dd', '#3fbd85', '#e0a23c', '#c78bff', '#ff7a9c', '#5fd0c8', '#f0a05a'];
function tagColor(tag) {
  const t = String(tag || '').toLowerCase();
  if (/err|fail|gagal/.test(t)) return '#ef6b60';
  if (/warn|peringat/.test(t)) return '#e0a23c';
  if (/rec|rekam|route|rute/.test(t)) return '#5aa9dd';
  if (/click|klik|edit/.test(t)) return '#c78bff';
  if (/capture|tangkap|screen|layar/.test(t)) return '#3fbd85';
  let h = 0;
  for (let i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) >>> 0;
  return TAG_PALETTE[h % TAG_PALETTE.length];
}

function appendLogEntry(entry, { batch } = {}) {
  if (!entry) return;
  const id = entry.id != null ? String(entry.id) : (entry.timeMs + ':' + entry.message);
  if (app.logSeen.has(id)) return;
  app.logSeen.add(id);
  app.log.push(entry);
  if (app.log.length > 600) {
    const removed = app.log.shift();
    if (removed) app.logSeen.delete(removed.id != null ? String(removed.id) : (removed.timeMs + ':' + removed.message));
  }
  if (!batch) renderLogLine(entry);
}

function renderLogLine(entry) {
  const feed = $('#log-feed');
  const emptyEl = $('.log-empty', feed);
  if (emptyEl) emptyEl.remove();
  const line = el('div', { class: 'log-line' });
  line.append(el('span', { class: 'log-time', text: fmtClock(entry.timeMs || Date.now()) }));
  line.append(el('span', { class: 'log-tag', attrs: { style: `color:${tagColor(entry.tag)}` }, text: '[' + (entry.tag || 'log') + ']' }));
  line.append(el('span', { class: 'log-msg', text: entry.message || '' }));
  feed.append(line);
  scrollLogToBottom();
}

function renderLogAll() {
  const feed = $('#log-feed');
  clear(feed);
  if (!app.log.length) {
    feed.append(el('div', { class: 'log-empty', text: 'Belum ada log. Aktivitas akan muncul di sini.' }));
    return;
  }
  const frag = document.createDocumentFragment();
  for (const entry of app.log) {
    const line = el('div', { class: 'log-line' });
    line.append(el('span', { class: 'log-time', text: fmtClock(entry.timeMs || Date.now()) }));
    line.append(el('span', { class: 'log-tag', attrs: { style: `color:${tagColor(entry.tag)}` }, text: '[' + (entry.tag || 'log') + ']' }));
    line.append(el('span', { class: 'log-msg', text: entry.message || '' }));
    frag.append(line);
  }
  feed.append(frag);
  scrollLogToBottom();
}

function scrollLogToBottom() {
  const feed = $('#log-feed');
  if (feed && app.activeTab === 'log') feed.scrollTop = feed.scrollHeight;
}

let localLogSeq = 0;
function pushLocalLog(tag, message) {
  appendLogEntry({ id: 'local-' + (Date.now()) + '-' + (localLogSeq++), timeMs: Date.now(), tag, message });
}

function logToText() {
  return app.log.map((e) => `${fmtClock(e.timeMs)} [${e.tag || 'log'}] ${e.message || ''}`).join('\n');
}

/* ============================================================================
 * Background event handling
 * ==========================================================================*/
function onRuntimeMessage(msg) {
  if (!msg || typeof msg.type !== 'string' || !msg.type.startsWith('evt.')) return;
  switch (msg.type) {
    case 'evt.state': {
      const prev = app.state;
      app.state = Object.assign({}, prev, msg.state || {});
      applyState();
      // Auto-follow the live current screen unless the user pinned one.
      if (!app.inspectorPinned && app.state.sessionId && app.state.currentScreenId &&
          (!app.inspectorScreen || app.inspectorScreen.id !== app.state.currentScreenId)) {
        loadScreenIntoInspector(app.state.sessionId, app.state.currentScreenId, { pin: false });
      }
      break;
    }
    case 'evt.sessions':
      refreshSessions();
      break;
    case 'evt.log':
      if (msg.entry) appendLogEntry(msg.entry);
      break;
    case 'evt.picked': {
      if (msg.node) {
        app.inspectorPinned = !!(msg.sessionId && msg.screenId);
        setTab('inspektur');
        if (msg.sessionId && msg.screenId) {
          loadScreenIntoInspector(msg.sessionId, msg.screenId, { pin: true })
            .then(() => showPickedNode(msg.node));
        } else {
          showPickedNode(msg.node);
        }
      }
      break;
    }
    case 'evt.flash':
      if (msg.message) toast(msg.message);
      break;
    default:
      break;
  }
}

/* ============================================================================
 * Data refresh
 * ==========================================================================*/
async function refreshSessions() {
  const list = await send({ type: 'sp.getSessions' });
  app.sessions = Array.isArray(list) ? list : [];
  applyState(); // counts in status line may depend on sessions
  if (app.openSessionId) {
    // keep detail in sync
    const stillThere = app.sessions.some((s) => s.id === app.openSessionId);
    if (!stillThere) { showSessionList(); return; }
  }
  if (!app.openSessionId) renderSessionList();
}

/* ============================================================================
 * Button wiring
 * ==========================================================================*/
function wireKontrol() {
  $('#btn-inspect').addEventListener('click', async () => {
    await send({ type: 'sp.toggleInspect', on: !app.state.inspecting });
  });
  $('#btn-record').addEventListener('click', async () => {
    if (app.state.recording) await send({ type: 'sp.stopRecord' });
    else await send({ type: 'sp.startRecord' });
    await refreshSessions();
  });
  $('#btn-capture').addEventListener('click', async () => {
    await send({ type: 'sp.captureNow' });
  });
  $('#btn-edit').addEventListener('click', async () => {
    await send({ type: 'sp.toggleEdit', on: !app.state.editing });
  });
}

function wireInspektur() {
  $('#btn-copy-node').addEventListener('click', async () => {
    if (!app.pickedNode) { toast('Belum ada elemen'); return; }
    const ok = await copyText(JSON.stringify(nodeToCopyObject(app.pickedNode), null, 2));
    toast(ok ? 'Properti disalin' : 'Gagal menyalin');
  });
  $('#edit-apply').addEventListener('click', async () => {
    const node = app.pickedNode;
    if (!node || !node.editable) return;
    const text = $('#edit-input').value;
    await send({ type: 'sp.setText', selector: node.selector, xpath: node.xpath, text });
    toast('Teks diterapkan');
  });
  $('#edit-clear').addEventListener('click', async () => {
    const node = app.pickedNode;
    if (!node || !node.editable) return;
    await send({ type: 'sp.setText', selector: node.selector, xpath: node.xpath, text: '' });
    $('#edit-input').value = '';
    toast('Kolom dikosongkan');
  });
  $('#btn-follow').addEventListener('click', () => {
    app.inspectorPinned = false;
    $('#btn-follow').classList.add('hidden');
    if (app.state.sessionId && app.state.currentScreenId) {
      loadScreenIntoInspector(app.state.sessionId, app.state.currentScreenId, { pin: false });
    }
  });
}

function wireSesi() {
  $('#btn-refresh-sessions').addEventListener('click', refreshSessions);
}

function wireLog() {
  $('#log-clear').addEventListener('click', () => {
    app.log = [];
    app.logSeen = new Set();
    renderLogAll();
  });
  $('#log-copy').addEventListener('click', async () => {
    const ok = await copyText(logToText());
    toast(ok ? 'Log disalin' : 'Gagal menyalin');
  });
  $('#log-share').addEventListener('click', () => {
    downloadText('uimapper-log.txt', logToText(), 'text/plain');
    toast('Log diunduh');
  });
}

function wireTabs() {
  $('#tabbar').addEventListener('click', (e) => {
    const btn = e.target.closest('.tab');
    if (btn && btn.dataset.tab) setTab(btn.dataset.tab);
  });
}

/* ============================================================================
 * Init
 * ==========================================================================*/
async function init() {
  wireTabs();
  wireKontrol();
  wireInspektur();
  wireSesi();
  wireLog();

  // Empty states
  showInspectorEmpty(true);
  renderTree(null);
  renderLogAll();

  // Subscribe to background broadcasts
  if (typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.onMessage) {
    chrome.runtime.onMessage.addListener((msg) => { onRuntimeMessage(msg); });
  }

  await loadLibs();

  // Initial pull
  const st = await send({ type: 'sp.getState' });
  if (st && typeof st === 'object') app.state = Object.assign({}, app.state, st);
  applyState();

  await refreshSessions();

  // If a screen is already current, load it into the inspector.
  if (app.state.sessionId && app.state.currentScreenId) {
    loadScreenIntoInspector(app.state.sessionId, app.state.currentScreenId, { pin: false });
  }

  pushLocalLog('sp', 'Panel siap.');
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
