// UI Mapper Web - screen signatures.
//
// Structural fingerprint of a screen. Two captures of the "same" screen with different
// data (list rows, counters, user names) should produce nearly identical feature sets;
// different screens should not. Pure functions only.
//
// Feature kinds (mirrors the Android app, adapted to the DOM):
//   V:<tag>|<role>|<key>   every visible element (Set semantics collapses repeated rows)
//   C:<tag>|<key>|<label>  clickable element; label kept only when it looks static
//   E:<key>|<placeholder>  editable field
//   T:<text>               heading / title text

import { flatten, labelOf, rectEmpty, attr, clip } from './model.js';

const MAX_DEPTH = 60;
const HEADING_TAGS = new Set(['h1', 'h2', 'h3', 'h4', 'h5', 'h6']);

/**
 * Sorted, de-duplicated structural features of a captured tree.
 * @param {import('./model.js').WNode} root
 * @returns {string[]}
 */
export function features(root) {
  const out = new Set();
  walk(root, out);
  return [...out].sort();
}

function walk(node, out) {
  if (!node) return;
  if ((node.depth || 0) <= MAX_DEPTH && node.visible && !rectEmpty(node.rect)) {
    const key = stableKey(node);
    out.add('V:' + node.tag + '|' + (node.role || '') + '|' + key);
    if (node.clickable) {
      out.add('C:' + node.tag + '|' + key + '|' + staticLabel(labelOf(node, 40)));
    }
    if (node.editable) {
      out.add('E:' + key + '|' + (clip(attr(node, 'placeholder'), 40) || ''));
    }
    if (isTitleNode(node)) {
      const t = clip(node.text || node.name, 50);
      if (t) out.add('T:' + t);
    }
  }
  const kids = node.children;
  if (kids) for (const c of kids) walk(c, out);
}

function isTitleNode(node) {
  return HEADING_TAGS.has(node.tag) || node.role === 'heading' || node.tag === 'title';
}

/**
 * A stable-ish key for a node used inside features. Prefers data-testid, then a static id,
 * then a couple of non-hashed class tokens. Auto-generated ids/classes are dropped so that
 * framework churn does not change the signature.
 */
function stableKey(node) {
  const a = node.attrs || {};
  if (a['data-testid']) return 't:' + clip(a['data-testid'], 40);
  if (a['data-test']) return 't:' + clip(a['data-test'], 40);
  if (node.id && isStaticId(node.id)) return '#' + clip(node.id, 40);
  if (node.cls && node.cls.length) {
    const tokens = node.cls.filter(isStaticClass).slice(0, 2);
    if (tokens.length) return '.' + tokens.join('.');
  }
  return '';
}

function isStaticId(s) {
  if (!s || s.length > 40) return false;
  if (s.includes(':')) return false; // React useId, e.g. ":r1:"
  if (/^[0-9a-f]{8,}$/i.test(s)) return false; // hex hash
  const digits = (s.match(/\d/g) || []).length;
  return digits / s.length <= 0.4;
}

function isStaticClass(c) {
  if (!c || c.length > 40) return false;
  if (/^css-[0-9a-z]{4,}$/i.test(c)) return false; // emotion / styled-components
  if (/^sc-[0-9a-z]{4,}$/i.test(c)) return false;
  if (/[_-][0-9a-z]{5,}$/i.test(c) && /\d/.test(c)) return false; // hashed CSS-module tail
  const digits = (c.match(/\d/g) || []).length;
  return digits / c.length <= 0.4;
}

/** Short static-looking labels only; dynamic strings (prices, counters, dates) are dropped. */
function staticLabel(s) {
  if (!s || s.length > 40) return '';
  const digits = (s.match(/\d/g) || []).length;
  return digits / s.length < 0.3 ? s : '';
}

/**
 * FNV-1a hash of a string as 16 hex chars (two 32-bit passes with different offset bases,
 * concatenated) for a low-collision, dependency-free digest.
 */
export function hash(str) {
  return fnv1a(str, 0x811c9dc5) + fnv1a(str, 0x84222325);
}

function fnv1a(str, seed) {
  let h = seed >>> 0;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i) & 0xff;
    h = Math.imul(h, 0x01000193);
    // Mix in the high byte of multi-byte code units so non-ASCII text still spreads.
    const hi = str.charCodeAt(i) >>> 8;
    if (hi) {
      h ^= hi;
      h = Math.imul(h, 0x01000193);
    }
  }
  return (h >>> 0).toString(16).padStart(8, '0');
}

/**
 * Signature of a screen: hash of a normalised route token plus the feature set. Two captures
 * that share a route and features get an identical signature.
 * @param {string} url
 * @param {string[]} featureList
 * @returns {string}
 */
export function signature(url, featureList) {
  return hash(routeToken(url) + '\n' + (featureList || []).join('\n'));
}

/** origin + normalised path (dynamic id segments folded to ":id"); falls back to the raw url. */
export function routeToken(url) {
  try {
    const u = new URL(url);
    let path = u.pathname || '/';
    if ((path === '/' || path === '') && u.hash && u.hash.startsWith('#/')) {
      path = u.hash.slice(1); // hash routing
    }
    return u.origin + '|' + normalizePath(path);
  } catch {
    return String(url || '');
  }
}

function normalizePath(path) {
  return path
    .split('/')
    .map((seg) => {
      if (!seg) return seg;
      if (/^\d+$/.test(seg)) return ':id';
      if (/^[0-9a-f]{8,}$/i.test(seg)) return ':id';
      if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(seg)) return ':id';
      return seg;
    })
    .join('/');
}

/** Jaccard similarity of two feature collections, 0..1. */
export function similarity(a, b) {
  const sa = a instanceof Set ? a : new Set(a || []);
  const sb = b instanceof Set ? b : new Set(b || []);
  if (sa.size === 0 && sb.size === 0) return 1;
  let inter = 0;
  for (const x of sa) if (sb.has(x)) inter++;
  const union = sa.size + sb.size - inter;
  return union === 0 ? 1 : inter / union;
}

/**
 * Best-effort screen title from the tree: first heading with text, else a short non-interactive
 * text near the top of the page. Callers usually prefer document.title and use this as a fallback.
 */
export function deriveTitle(root) {
  const all = flatten(root).filter((n) => n.visible && !rectEmpty(n.rect));
  const heading = all.find((n) => (HEADING_TAGS.has(n.tag) || n.role === 'heading') && textOf(n));
  if (heading) return clip(textOf(heading), 60);
  let topLimit = Infinity;
  const h = root && root.rect ? root.rect.b - root.rect.t : 0;
  if (h > 0) topLimit = h / 4;
  const top = all.find((n) => {
    const t = textOf(n);
    return !n.clickable && !n.editable && t && t.length >= 2 && t.length <= 40 && n.rect.t < topLimit;
  });
  return top ? clip(textOf(top), 40) : null;
}

function textOf(node) {
  const t = (node.name || node.text || '').replace(/\s+/g, ' ').trim();
  return t || null;
}

/** Default screen label: title, else the last path segment, else "Layar #<n>". */
export function defaultLabel(title, path, number) {
  const t = title ? String(title).trim() : '';
  if (t) return t;
  const seg = lastSegment(path);
  if (seg) return seg;
  return 'Layar #' + number;
}

function lastSegment(path) {
  if (!path) return '';
  const parts = String(path).split('/').filter(Boolean);
  return parts.length ? decodeSafe(parts[parts.length - 1]) : '';
}

function decodeSafe(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}
