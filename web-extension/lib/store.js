// UI Mapper Web - chrome.storage.local persistence.
//
// Layout in chrome.storage.local:
//   "ui"                          settings object.
//   "sessions"                    Session[] (screen summaries + edges, NO full trees).
//   "screen:<sessionId>:<id>"     the full Screen (with its WNode root) for one screen.
//
// Full trees live under their own keys so the "sessions" value stays small. All mutations go
// through a promise chain so concurrent read-modify-write calls in the service worker cannot
// lose updates. Every read is defensive: missing or corrupt values degrade to empty, never throw.

import {
  START_NODE,
  isExternalNode,
  flatten,
  clip,
} from './model.js';
import { features as computeFeatures, signature as computeSignature, similarity, deriveTitle, defaultLabel, routeToken } from './signature.js';

const SETTINGS_KEY = 'ui';
const SESSIONS_KEY = 'sessions';
const SCREEN_PREFIX = 'screen:';

export const DEFAULT_THRESHOLD = 0.82;

const DEFAULT_SETTINGS = {
  threshold: DEFAULT_THRESHOLD,
};

function screenKey(sessionId, screenId) {
  return SCREEN_PREFIX + sessionId + ':' + screenId;
}

// --------------------------------------------------------------------------- low-level storage

function rawGet(keys) {
  return new Promise((resolve) => {
    try {
      chrome.storage.local.get(keys, (result) => {
        if (chrome.runtime.lastError) resolve({});
        else resolve(result || {});
      });
    } catch {
      resolve({});
    }
  });
}

function rawSet(obj) {
  return new Promise((resolve, reject) => {
    try {
      chrome.storage.local.set(obj, () => {
        const err = chrome.runtime.lastError;
        if (err) reject(new Error(err.message));
        else resolve();
      });
    } catch (e) {
      reject(e);
    }
  });
}

function rawRemove(keys) {
  return new Promise((resolve) => {
    try {
      chrome.storage.local.remove(keys, () => resolve());
    } catch {
      resolve();
    }
  });
}

// A single promise chain serialises mutations; reads may run concurrently.
let chain = Promise.resolve();
function withLock(fn) {
  const next = chain.then(() => fn());
  chain = next.then(
    () => {},
    () => {},
  );
  return next;
}

// --------------------------------------------------------------------------- settings

export async function getSettings() {
  const r = await rawGet(SETTINGS_KEY);
  const s = r[SETTINGS_KEY];
  return Object.assign({}, DEFAULT_SETTINGS, s && typeof s === 'object' ? s : {});
}

export async function setSettings(patch) {
  return withLock(async () => {
    const cur = await getSettings();
    const next = Object.assign({}, cur, patch && typeof patch === 'object' ? patch : {});
    await rawSet({ [SETTINGS_KEY]: next });
    return next;
  });
}

// --------------------------------------------------------------------------- sessions

/** @returns {Promise<import('./model.js').Session[]>} newest updated first. */
export async function getSessions() {
  const r = await rawGet(SESSIONS_KEY);
  const list = Array.isArray(r[SESSIONS_KEY]) ? r[SESSIONS_KEY] : [];
  return list
    .filter((s) => s && typeof s === 'object' && s.id)
    .map(normalizeSession)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

export async function getSession(id) {
  const all = await getSessions();
  return all.find((s) => s.id === id) || null;
}

function normalizeSession(s) {
  return {
    id: String(s.id),
    name: s.name || 'Sesi',
    origin: s.origin || '',
    startUrl: s.startUrl || '',
    createdAt: s.createdAt || 0,
    updatedAt: s.updatedAt || 0,
    screens: Array.isArray(s.screens) ? s.screens : [],
    edges: Array.isArray(s.edges) ? s.edges : [],
    nextScreenNo: s.nextScreenNo || (Array.isArray(s.screens) ? s.screens.length + 1 : 1),
    nextEdgeNo: s.nextEdgeNo || (Array.isArray(s.edges) ? s.edges.length + 1 : 1),
  };
}

async function writeSessions(list) {
  await rawSet({ [SESSIONS_KEY]: list });
}

/** Reads, replaces the session with the same id (or appends), writes. Assumes the lock is held. */
async function upsertLocked(session) {
  const all = await getSessions();
  const idx = all.findIndex((s) => s.id === session.id);
  if (idx >= 0) all[idx] = session;
  else all.push(session);
  await writeSessions(all);
  return session;
}

export async function createSession(name, origin, startUrl) {
  return withLock(async () => {
    const now = Date.now();
    const session = {
      id: 's' + now.toString(36) + Math.floor(100 + Math.random() * 900),
      name: name || sessionNameFor(origin),
      origin: origin || '',
      startUrl: startUrl || '',
      createdAt: now,
      updatedAt: now,
      screens: [],
      edges: [],
      nextScreenNo: 1,
      nextEdgeNo: 1,
    };
    await upsertLocked(session);
    return session;
  });
}

function sessionNameFor(origin) {
  let host = origin || 'situs';
  try {
    host = new URL(origin).host || origin;
  } catch {
    /* keep raw */
  }
  const stamp = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return host + ' · ' + stamp.getFullYear() + '-' + pad(stamp.getMonth() + 1) + '-' + pad(stamp.getDate());
}

export async function saveSession(session) {
  return withLock(async () => {
    const next = Object.assign({}, normalizeSession(session), { updatedAt: Date.now() });
    return upsertLocked(next);
  });
}

export async function renameSession(id, name) {
  return withLock(async () => {
    const all = await getSessions();
    const s = all.find((x) => x.id === id);
    if (!s) return null;
    s.name = name;
    s.updatedAt = Date.now();
    await writeSessions(all);
    return s;
  });
}

export async function deleteSession(id) {
  return withLock(async () => {
    const all = await getSessions();
    const next = all.filter((s) => s.id !== id);
    await writeSessions(next);
    // Remove every stored screen tree of this session.
    const everything = await rawGet(null);
    const prefix = SCREEN_PREFIX + id + ':';
    const keys = Object.keys(everything).filter((k) => k.startsWith(prefix));
    if (keys.length) await rawRemove(keys);
    return true;
  });
}

// --------------------------------------------------------------------------- screens

/** @returns {Promise<import('./model.js').Screen|null>} */
export async function getScreen(sessionId, screenId) {
  const key = screenKey(sessionId, screenId);
  const r = await rawGet(key);
  const scr = r[key];
  return scr && typeof scr === 'object' ? scr : null;
}

export async function putScreen(sessionId, screen) {
  await rawSet({ [screenKey(sessionId, screen.id)]: screen });
}

/**
 * Normalise a raw capture into a stored {@link Screen}: (re)compute features/signature/title and
 * the node counts from the tree so matching is always consistent with this library.
 * @param {any} snapshot raw capture with at least { url, root }.
 * @returns {import('./model.js').Screen}
 */
export function normalizeSnapshot(snapshot) {
  const root = snapshot.root || { idx: 0, depth: 0, tag: 'html', cls: [], attrs: {}, rect: { l: 0, t: 0, r: 0, b: 0 }, children: [] };
  const nodes = flatten(root);
  const feats = computeFeatures(root);
  const url = snapshot.url || '';
  let path = snapshot.path;
  if (!path) {
    try {
      path = new URL(url).pathname;
    } catch {
      path = '';
    }
  }
  const title = (snapshot.title && String(snapshot.title).trim()) || deriveTitle(root) || '';
  return {
    id: snapshot.id || '',
    url,
    path: path || '',
    title,
    signature: computeSignature(url, feats),
    features: feats,
    capturedAt: snapshot.capturedAt || Date.now(),
    w: snapshot.w | 0,
    h: snapshot.h | 0,
    nodeCount: nodes.length,
    clickableCount: nodes.filter((n) => n.clickable).length,
    root,
  };
}

function bestMatch(session, snapshot) {
  let best = null;
  let bestScore = 0;
  for (const sc of session.screens) {
    const score = sc.signature === snapshot.signature ? 1 : similarity(sc.features, snapshot.features);
    if (score > bestScore) {
      best = sc;
      bestScore = score;
    }
  }
  return { best, score: bestScore };
}

function uniqueLabel(session, base) {
  const taken = new Set(session.screens.map((s) => s.label));
  if (!taken.has(base)) return base;
  let i = 2;
  while (taken.has(base + ' (' + i + ')')) i++;
  return base + ' (' + i + ')';
}

function summaryOf(screen) {
  return {
    id: screen.id,
    label: screen.label || screen.id,
    url: screen.url,
    path: screen.path,
    title: screen.title,
    signature: screen.signature,
    features: screen.features,
    w: screen.w,
    h: screen.h,
    nodeCount: screen.nodeCount,
    clickableCount: screen.clickableCount,
    firstSeen: screen.capturedAt,
    lastSeen: screen.capturedAt,
    visits: 1,
  };
}

/**
 * Match a normalised [snapshot] against the session's known screens (signature exact OR feature
 * Jaccard >= [threshold]). A match bumps visits/lastSeen; otherwise a new screen "S<n>" (with its
 * full tree) is stored.
 * @returns {Promise<{summary:import('./model.js').ScreenSummary, isNew:boolean, score:number}|null>}
 */
export async function recordScreen(sessionId, rawSnapshot, threshold = DEFAULT_THRESHOLD) {
  // Always normalise so features/signature/counts come from this library and matching stays
  // consistent no matter what a caller (or the content script) computed. normalizeSnapshot is
  // deterministic, so re-normalising an already-normalised snapshot is a no-op in effect.
  const snapshot = normalizeSnapshot(rawSnapshot);
  return withLock(async () => {
    const all = await getSessions();
    const session = all.find((s) => s.id === sessionId);
    if (!session) return null;
    const now = Date.now();
    const { best, score } = bestMatch(session, snapshot);
    if (best && score >= threshold) {
      best.lastSeen = now;
      best.visits = (best.visits || 1) + 1;
      session.updatedAt = now;
      await writeSessions(all);
      return { summary: best, isNew: false, score };
    }
    const no = session.nextScreenNo;
    const id = 'S' + no;
    const label = uniqueLabel(session, defaultLabel(snapshot.title, snapshot.path, no));
    const screen = Object.assign({}, snapshot, { id, label, capturedAt: now });
    await putScreen(sessionId, screen);
    const summary = summaryOf(screen);
    session.screens.push(summary);
    session.nextScreenNo = no + 1;
    session.updatedAt = now;
    await writeSessions(all);
    return { summary, isNew: true, score: 1 };
  });
}

// --------------------------------------------------------------------------- edges

function isKnownNode(session, id) {
  return id === START_NODE || isExternalNode(id) || session.screens.some((s) => s.id === id);
}

function edgeElementKey(element) {
  if (!element) return '';
  return element.selector || element.xpath || (element.id ? '#' + element.id : '') || element.label || element.text || element.tag || '';
}

function dedupeKey(from, to, action, element) {
  return from + '|' + to + '|' + action + '|' + edgeElementKey(element);
}

/**
 * Add a navigation edge, or bump the count of an identical existing one. Returns null (storing
 * nothing) when [from] or [to] is not START, an external site or a screen of the session.
 * @returns {Promise<import('./model.js').Edge|null>}
 */
export async function recordEdge(sessionId, from, to, action, element, source = 'manual') {
  return withLock(async () => {
    const all = await getSessions();
    const session = all.find((s) => s.id === sessionId);
    if (!session) return null;
    if (!isKnownNode(session, from) || !isKnownNode(session, to)) return null;
    const now = Date.now();
    const key = dedupeKey(from, to, action, element);
    const existing = session.edges.find((e) => dedupeKey(e.from, e.to, e.action, e.element) === key);
    let edge;
    if (existing) {
      existing.count = (existing.count || 1) + 1;
      existing.lastAt = now;
      edge = existing;
    } else {
      edge = {
        id: 'E' + session.nextEdgeNo,
        from,
        to,
        action,
        element: element || null,
        source,
        count: 1,
        firstAt: now,
        lastAt: now,
      };
      session.edges.push(edge);
      session.nextEdgeNo += 1;
    }
    session.updatedAt = now;
    await writeSessions(all);
    return edge;
  });
}

export async function deleteEdge(sessionId, edgeId) {
  return withLock(async () => {
    const all = await getSessions();
    const session = all.find((s) => s.id === sessionId);
    if (!session) return null;
    session.edges = session.edges.filter((e) => e.id !== edgeId);
    session.updatedAt = Date.now();
    await writeSessions(all);
    return true;
  });
}

export async function renameScreen(sessionId, screenId, label) {
  return withLock(async () => {
    const all = await getSessions();
    const session = all.find((s) => s.id === sessionId);
    if (!session) return null;
    const sc = session.screens.find((s) => s.id === screenId);
    if (!sc) return null;
    sc.label = label;
    session.updatedAt = Date.now();
    await writeSessions(all);
    // Keep the stored full screen's label in sync when present.
    const screen = await getScreen(sessionId, screenId);
    if (screen) {
      screen.label = label;
      await putScreen(sessionId, screen);
    }
    return sc;
  });
}

/** Deletes a screen, its stored tree and every edge touching it. */
export async function deleteScreen(sessionId, screenId) {
  return withLock(async () => {
    const all = await getSessions();
    const session = all.find((s) => s.id === sessionId);
    if (!session) return null;
    session.screens = session.screens.filter((s) => s.id !== screenId);
    session.edges = session.edges.filter((e) => e.from !== screenId && e.to !== screenId);
    session.updatedAt = Date.now();
    await writeSessions(all);
    await rawRemove(screenKey(sessionId, screenId));
    return true;
  });
}

/** All stored full screens of a session, keyed by screen id (for exporters). */
export async function getAllScreens(sessionId) {
  const session = await getSession(sessionId);
  const map = {};
  if (!session) return map;
  const keys = session.screens.map((s) => screenKey(sessionId, s.id));
  if (!keys.length) return map;
  const r = await rawGet(keys);
  for (const s of session.screens) {
    const scr = r[screenKey(sessionId, s.id)];
    if (scr && typeof scr === 'object') map[s.id] = scr;
  }
  return map;
}

// Kept exported for callers that want the same normalised route grouping used by signatures.
export { routeToken };

// Rough size accounting used by the side panel's storage indicator (best effort).
export async function storageBytes() {
  return new Promise((resolve) => {
    try {
      if (chrome.storage.local.getBytesInUse) {
        chrome.storage.local.getBytesInUse(null, (bytes) => resolve(bytes || 0));
      } else {
        resolve(0);
      }
    } catch {
      resolve(0);
    }
  });
}
