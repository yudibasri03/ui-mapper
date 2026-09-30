// UI Mapper Web - MV3 background service worker (ES module).
//
// Owns the live inspect/record state, injects the content script on demand, translates the
// side-panel commands and content-script events, and persists everything through lib/store.js.
// Read-only by default: the page is only touched when the user explicitly inspects, records or
// edits. No network requests are ever made; nothing leaves chrome.storage.local.

import * as store from './lib/store.js';
import * as exporters from './lib/exporters.js';
import { START_NODE, externalNodeId } from './lib/model.js';

// --------------------------------------------------------------------------- live state

const state = {
  recording: false,
  inspecting: false,
  editing: false,
  tabId: null, // the working tab (inspect / edit / capture target)
  origin: null, // display origin (session origin while recording, else active tab origin)
  sessionId: null,
  currentScreenId: null,
  recordTabId: null,
  history: [], // stack of screen ids for back detection
  pending: null, // {action, element, back} from the latest cs.click / cs.route
  away: false, // true while the recording tab is on a foreign origin
  threshold: store.DEFAULT_THRESHOLD,
  picked: null,
};

const injected = new Set(); // tab ids known to have the content script
const eventLog = []; // capped in-memory log mirrored to the side panel
let logSeq = 0;
let lastCapture = { sig: null, at: 0 };

const LOG_LIMIT = 800;

// --------------------------------------------------------------------------- setup

chrome.runtime.onInstalled.addListener(() => {
  enablePanelOnClick();
  store.getSettings().then((s) => {
    state.threshold = s.threshold;
  });
});
chrome.runtime.onStartup && chrome.runtime.onStartup.addListener(enablePanelOnClick);
enablePanelOnClick();

function enablePanelOnClick() {
  try {
    if (chrome.sidePanel && chrome.sidePanel.setPanelBehavior) {
      chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
    }
  } catch {
    /* ignore */
  }
}

// Fallback for builds where openPanelOnActionClick does not auto-open.
chrome.action.onClicked.addListener((tab) => {
  try {
    if (tab && tab.id != null) chrome.sidePanel.open({ tabId: tab.id }).catch(() => {});
  } catch {
    /* ignore */
  }
});

// --------------------------------------------------------------------------- messaging helpers

function sendToTab(tabId, msg) {
  return new Promise((resolve) => {
    if (tabId == null) {
      resolve(undefined);
      return;
    }
    try {
      chrome.tabs.sendMessage(tabId, msg, (resp) => {
        void chrome.runtime.lastError; // swallow "no receiving end"
        resolve(resp);
      });
    } catch {
      resolve(undefined);
    }
  });
}

function broadcast(msg) {
  try {
    chrome.runtime.sendMessage(msg, () => {
      void chrome.runtime.lastError; // swallow "no receiving end" when the panel is closed
    });
  } catch {
    /* ignore */
  }
}

function publicState() {
  return {
    recording: state.recording,
    inspecting: state.inspecting,
    editing: state.editing,
    tabId: state.tabId,
    origin: state.origin,
    sessionId: state.sessionId,
    currentScreenId: state.currentScreenId,
  };
}

function emitState() {
  broadcast({ type: 'evt.state', state: publicState() });
}

function emitSessions() {
  broadcast({ type: 'evt.sessions' });
}

function flash(message) {
  broadcast({ type: 'evt.flash', message });
}

function pushLog(tag, message) {
  const entry = { id: ++logSeq, timeMs: Date.now(), tag, message };
  eventLog.push(entry);
  if (eventLog.length > LOG_LIMIT) eventLog.shift();
  broadcast({ type: 'evt.log', entry });
  return entry;
}

// --------------------------------------------------------------------------- tab helpers

function originOf(url) {
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

function scriptable(url) {
  if (!url) return false;
  if (!/^(https?|file|ftp):/i.test(url)) return false;
  if (/^https?:\/\/(chrome\.google\.com\/webstore|chromewebstore\.google\.com)/i.test(url)) return false;
  return true;
}

function pageBlockedMsg() {
  return 'Halaman ini tidak bisa diinspeksi (mis. chrome://, Chrome Web Store, atau halaman internal). Buka situs web biasa lalu coba lagi.';
}

function scriptErrorMessage(e) {
  const m = String((e && e.message) || e || '');
  if (/cannot be scripted|Cannot access|chrome:\/\/|extension gallery|error page|The extensions gallery/i.test(m)) {
    return pageBlockedMsg();
  }
  return 'Gagal menyuntik skrip: ' + m;
}

async function getActiveTab() {
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    return tabs && tabs[0] ? tabs[0] : null;
  } catch {
    return null;
  }
}

async function ensureInjected(tabId) {
  if (tabId == null) throw new Error('Tidak ada tab aktif');
  if (injected.has(tabId)) return true;
  try {
    await chrome.scripting.executeScript({ target: { tabId }, files: ['content/inspector.js'] });
    injected.add(tabId);
    return true;
  } catch (e) {
    throw new Error(scriptErrorMessage(e));
  }
}

/** The tab the panel acts on: the tracked working tab, else the active tab. */
async function workingTab() {
  let id = state.tabId;
  if (id == null) {
    const t = await getActiveTab();
    id = t && t.id != null ? t.id : null;
    state.tabId = id;
    if (t && !state.recording) state.origin = originOf(t.url);
  }
  if (id == null) throw new Error('Tidak ada tab aktif');
  return id;
}

// --------------------------------------------------------------------------- message router

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || typeof msg.type !== 'string') return undefined;
  if (msg.type.startsWith('cs.')) {
    Promise.resolve(handleContent(msg, sender)).catch((e) => pushLog('WARN', String((e && e.message) || e)));
    return undefined;
  }
  if (msg.type.startsWith('sp.')) {
    handlePanel(msg).then(
      (result) => sendResponse(result),
      (e) => {
        const m = String((e && e.message) || e);
        flash(m);
        pushLog('WARN', m);
        sendResponse({ error: m });
      },
    );
    return true; // async response
  }
  return undefined;
});

// --------------------------------------------------------------------------- content-script events

async function handleContent(msg, sender) {
  const tabId = sender && sender.tab ? sender.tab.id : null;
  switch (msg.type) {
    case 'cs.ready':
      if (tabId != null) injected.add(tabId);
      pushLog('INFO', 'Skrip siap · ' + (msg.url || ''));
      return;
    case 'cs.capture':
      await onCapture(tabId, msg.snapshot);
      return;
    case 'cs.route':
      await onRoute(tabId, msg);
      return;
    case 'cs.click':
      if (state.recording && tabId === state.recordTabId) {
        const tag = msg.element && msg.element.tag ? String(msg.element.tag).toLowerCase() : '';
        state.pending = { action: tag === 'form' ? 'submit' : 'click', element: msg.element || null, back: false };
        pushLog('CLICK', labelForLog(msg.element));
      }
      return;
    case 'cs.picked':
      state.picked = msg.node || null;
      broadcast({ type: 'evt.picked', node: msg.node || null, sessionId: state.sessionId, screenId: state.currentScreenId });
      return;
    case 'cs.hover':
      // Passive; the overlay handles its own highlight. No state change needed.
      return;
    case 'cs.log':
      pushLog(msg.tag || 'INFO', msg.message || '');
      return;
    default:
      return;
  }
}

function labelForLog(element) {
  if (!element) return 'Klik elemen';
  return 'Klik ' + (element.label || element.text || element.selector || element.tag || 'elemen');
}

async function onRoute(tabId, msg) {
  if (!state.recording || tabId !== state.recordTabId) return;
  const origin = originOf(msg.url);
  if (origin && state.origin && origin !== state.origin) {
    await recordExternal(origin);
    return;
  }
  const back = msg.via === 'pop';
  const prev = state.pending || {};
  state.pending = {
    action: prev.action || 'nav',
    element: msg.element || prev.element || null,
    back: back || prev.back || false,
  };
  pushLog('NAV', 'Rute ' + (msg.via || 'load') + ' · ' + (msg.url || ''));
  // Ask the page for a fresh snapshot; onCapture de-dupes bursts so an auto-capture is harmless.
  await sendToTab(tabId, { type: 'bg.captureNow' });
}

async function onCapture(tabId, rawSnapshot) {
  if (!state.recording || tabId !== state.recordTabId || !rawSnapshot) return;
  const snapshot = store.normalizeSnapshot(rawSnapshot);
  const now = Date.now();
  if (lastCapture.sig === snapshot.signature && now - lastCapture.at < 700) return; // burst guard
  lastCapture = { sig: snapshot.signature, at: now };

  const origin = originOf(snapshot.url);
  if (origin && state.origin && origin !== state.origin) {
    await recordExternal(origin);
    return;
  }

  const prevId = state.currentScreenId;
  const res = await store.recordScreen(state.sessionId, snapshot, state.threshold);
  if (!res) return;
  const toId = res.summary.id;
  pushLog(res.isNew ? 'NEW' : 'SEEN', (res.isNew ? 'Layar baru ' : 'Layar ') + toId + ' · ' + res.summary.label);

  if (prevId == null && !state.away) {
    await store.recordEdge(state.sessionId, START_NODE, toId, 'launch', null);
    pushLog('SNAP', 'Mulai → ' + toId);
    state.history = [toId];
  } else if (toId !== prevId) {
    const resolved = resolveAction(prevId, toId);
    await store.recordEdge(state.sessionId, resolved.from, toId, resolved.action, resolved.element);
    pushLog(actionTag(resolved.action), resolved.from + ' → ' + toId + ' (' + resolved.action + ')');
    updateHistory(toId, resolved.action);
  }

  state.currentScreenId = toId;
  state.away = false;
  state.pending = null;
  emitSessions();
  emitState();
}

function resolveAction(prevId, toId) {
  const pend = state.pending || {};
  const prevScreen = state.history[state.history.length - 2];
  if (pend.back || toId === prevScreen) {
    return { action: 'back', element: null, from: prevId };
  }
  if (pend.element) {
    return { action: pend.action === 'submit' ? 'submit' : 'click', element: pend.element, from: prevId };
  }
  return { action: 'nav', element: pend.element || null, from: prevId };
}

function updateHistory(toId, action) {
  if (action === 'back') {
    if (state.history[state.history.length - 2] === toId) {
      state.history.pop();
    } else {
      const i = state.history.lastIndexOf(toId);
      if (i >= 0) state.history = state.history.slice(0, i + 1);
      else state.history.push(toId);
    }
  } else if (state.history[state.history.length - 1] !== toId) {
    state.history.push(toId);
  }
}

async function recordExternal(origin) {
  const extId = externalNodeId(origin);
  if (state.currentScreenId && state.currentScreenId !== extId) {
    await store.recordEdge(state.sessionId, state.currentScreenId, extId, 'external', null);
    pushLog('EXT', state.currentScreenId + ' → ' + extId);
  }
  state.currentScreenId = extId;
  state.away = true;
  state.pending = null;
  emitSessions();
}

function actionTag(action) {
  switch (action) {
    case 'click': return 'CLICK';
    case 'submit': return 'CLICK';
    case 'nav': return 'NAV';
    case 'back': return 'BACK';
    case 'external': return 'EXT';
    case 'launch': return 'SNAP';
    default: return 'INFO';
  }
}

// --------------------------------------------------------------------------- side-panel commands

async function handlePanel(msg) {
  switch (msg.type) {
    case 'sp.getState': {
      if (state.tabId == null) {
        const t = await getActiveTab();
        if (t) {
          state.tabId = t.id;
          if (!state.recording) state.origin = originOf(t.url);
        }
      }
      return publicState();
    }
    case 'sp.getSessions':
      return store.getSessions();
    case 'sp.getSession':
      return store.getSession(msg.id);
    case 'sp.getScreen':
      return store.getScreen(msg.sessionId, msg.screenId);
    case 'sp.getLog':
      return eventLog.slice(-200);
    case 'sp.startRecord':
      return startRecord();
    case 'sp.stopRecord':
      return stopRecord();
    case 'sp.toggleInspect':
      return toggleInspect(!!msg.on);
    case 'sp.toggleEdit':
      return toggleEdit(!!msg.on);
    case 'sp.captureNow':
      return captureNow();
    case 'sp.renameSession': {
      const s = await store.renameSession(msg.id, msg.name);
      emitSessions();
      return s;
    }
    case 'sp.deleteSession': {
      await store.deleteSession(msg.id);
      if (state.sessionId === msg.id) {
        state.sessionId = null;
        state.currentScreenId = null;
      }
      emitSessions();
      emitState();
      return { ok: true };
    }
    case 'sp.deleteEdge': {
      await store.deleteEdge(msg.sessionId, msg.edgeId);
      emitSessions();
      return { ok: true };
    }
    case 'sp.renameScreen': {
      const sc = await store.renameScreen(msg.sessionId, msg.screenId, msg.label);
      emitSessions();
      return sc;
    }
    case 'sp.deleteScreen': {
      await store.deleteScreen(msg.sessionId, msg.screenId);
      emitSessions();
      return { ok: true };
    }
    case 'sp.selectNode': {
      const tabId = await workingTab();
      await ensureInjected(tabId);
      await sendToTab(tabId, { type: 'bg.selectNode', idx: msg.idx });
      await sendToTab(tabId, { type: 'bg.scrollTo', idx: msg.idx });
      return { ok: true };
    }
    case 'sp.setText': {
      const tabId = await workingTab();
      await ensureInjected(tabId);
      // Privacy: the typed value is applied to the page on this explicit action and never stored.
      await sendToTab(tabId, { type: 'bg.setText', selector: msg.selector, xpath: msg.xpath, text: msg.text });
      pushLog('EDIT', 'Set nilai elemen (tidak disimpan)');
      return { ok: true };
    }
    case 'sp.getSettings':
      return store.getSettings();
    case 'sp.setSettings': {
      const s = await store.setSettings(msg.patch || {});
      state.threshold = s.threshold;
      return s;
    }
    case 'sp.export': {
      const session = await store.getSession(msg.sessionId);
      if (!session) throw new Error('Sesi tidak ditemukan');
      const screens = await store.getAllScreens(msg.sessionId);
      const file = exporters.exportSession(session, screens, msg.format);
      pushLog('INFO', 'Ekspor ' + msg.format + ' · ' + file.filename);
      return file;
    }
    default:
      throw new Error('Perintah tidak dikenal: ' + msg.type);
  }
}

async function startRecord() {
  const tab = await getActiveTab();
  if (!tab || tab.id == null) throw new Error('Tidak ada tab aktif');
  if (!scriptable(tab.url)) throw new Error(pageBlockedMsg());
  const origin = originOf(tab.url);
  const session = await store.createSession(undefined, origin, tab.url);
  state.recording = true;
  state.sessionId = session.id;
  state.recordTabId = tab.id;
  state.tabId = tab.id;
  state.origin = origin;
  state.currentScreenId = null;
  state.history = [];
  state.pending = null;
  state.away = false;
  lastCapture = { sig: null, at: 0 };
  state.threshold = (await store.getSettings()).threshold;
  await ensureInjected(tab.id);
  await sendToTab(tab.id, { type: 'bg.startRecord', sessionId: session.id });
  await sendToTab(tab.id, { type: 'bg.captureNow' });
  pushLog('REC', 'Mulai merekam · ' + (origin || ''));
  emitSessions();
  emitState();
  return { session, state: publicState() };
}

async function stopRecord() {
  const sid = state.sessionId;
  if (state.recording && state.recordTabId != null) {
    await sendToTab(state.recordTabId, { type: 'bg.stopRecord' });
  }
  state.recording = false;
  state.recordTabId = null;
  state.pending = null;
  state.away = false;
  pushLog('STOP', 'Rekaman dihentikan');
  const t = await getActiveTab();
  if (t) state.origin = originOf(t.url);
  emitState();
  return { sessionId: sid, state: publicState() };
}

async function toggleInspect(on) {
  const tabId = await workingTab();
  await ensureInjected(tabId);
  state.inspecting = on;
  state.tabId = tabId;
  await sendToTab(tabId, { type: on ? 'bg.startInspect' : 'bg.stopInspect' });
  pushLog('INSPECT', on ? 'Inspeksi aktif' : 'Inspeksi mati');
  emitState();
  return publicState();
}

async function toggleEdit(on) {
  const tabId = await workingTab();
  await ensureInjected(tabId);
  state.editing = on;
  await sendToTab(tabId, { type: 'bg.setEdit', enabled: on });
  pushLog('EDIT', on ? 'Mode edit aktif' : 'Mode edit mati');
  emitState();
  return publicState();
}

async function captureNow() {
  if (!state.recording) throw new Error('Mulai rekam dulu untuk menyimpan layar');
  const tabId = state.recordTabId;
  await ensureInjected(tabId);
  await sendToTab(tabId, { type: 'bg.captureNow' });
  pushLog('SNAP', 'Ambil snapshot diminta');
  return { ok: true };
}

// --------------------------------------------------------------------------- tab lifecycle

chrome.tabs.onActivated.addListener(async ({ tabId }) => {
  const prev = state.tabId;
  if (state.inspecting && prev != null && prev !== tabId) {
    await sendToTab(prev, { type: 'bg.stopInspect' });
  }
  state.inspecting = false;
  state.tabId = tabId;
  if (!state.recording) {
    const t = await chrome.tabs.get(tabId).catch(() => null);
    if (t) state.origin = originOf(t.url);
  }
  emitState();
});

chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === 'loading') injected.delete(tabId);
  if (state.recording && tabId === state.recordTabId && changeInfo.url) {
    const origin = originOf(changeInfo.url);
    if (origin && state.origin && origin !== state.origin) {
      recordExternal(origin).catch(() => {});
    }
  }
  if (!state.recording && tabId === state.tabId && (changeInfo.url || changeInfo.status === 'complete')) {
    const o = originOf(tab && tab.url);
    if (o && o !== state.origin) {
      state.origin = o;
      emitState();
    }
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  injected.delete(tabId);
  if (state.recording && tabId === state.recordTabId) {
    state.recording = false;
    state.recordTabId = null;
    state.pending = null;
    pushLog('STOP', 'Tab rekaman ditutup, rekaman dihentikan');
    emitState();
  }
  if (tabId === state.tabId) state.tabId = null;
});
