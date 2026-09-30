// UI Mapper Web - shared data model.
//
// Pure JSDoc typedefs for the model shared across the extension, plus small pure
// helpers over a captured node tree. No chrome.* APIs, no DOM: safe to import from
// the background service worker, the side panel and (conceptually) the content script.
//
// Privacy note reflected in the model: WNode.text is never the value typed into a
// password field, and the editor never persists text the user types.

/**
 * Viewport-space rectangle in integer CSS pixels (l = left, t = top, r = right, b = bottom).
 * @typedef {{ l:number, t:number, r:number, b:number }} Rect
 */

/**
 * One captured DOM element. `idx` is the pre-order index inside its snapshot (root = 0)
 * and is the stable way to refer to a node within a single {@link Screen}.
 * @typedef {Object} WNode
 * @property {number} idx           Pre-order index, root = 0.
 * @property {number} depth         Distance from the root.
 * @property {string} tag           Lowercase tag name (e.g. "div", "button").
 * @property {string|null} id       Element id, or null.
 * @property {string[]} cls         Class tokens.
 * @property {string|null} role     ARIA role (explicit or implicit), or null.
 * @property {string|null} name     Accessible / label text, or null.
 * @property {string|null} text     Own short visible text (never a password value), or null.
 * @property {Object.<string,string>} attrs  Small safe allowlist of attributes.
 * @property {Rect} rect            Bounds in viewport pixels.
 * @property {boolean} clickable    Interactive (link/button/role/onclick/tabindex).
 * @property {boolean} editable     Text input / textarea / contenteditable.
 * @property {boolean} password     True for password inputs.
 * @property {string|null} href     Resolved href for links, or null.
 * @property {boolean} visible      Rendered and non-zero sized.
 * @property {string} selector      Stable-ish unique CSS selector.
 * @property {string} xpath         XPath locator.
 * @property {WNode[]} children     Child nodes in document order.
 */

/**
 * Full capture of one screen, stored under "screen:<sessionId>:<screenId>".
 * @typedef {Object} Screen
 * @property {string} id
 * @property {string} url
 * @property {string} path
 * @property {string} title
 * @property {string} signature
 * @property {string[]} features
 * @property {number} capturedAt   ms epoch.
 * @property {number} w            Viewport width.
 * @property {number} h            Viewport height.
 * @property {number} nodeCount
 * @property {number} clickableCount
 * @property {WNode} root
 * @property {string} [label]      Human label (mirrors the summary).
 */

/**
 * Light-weight per-screen entry kept inside a {@link Session} (no UI tree).
 * @typedef {Object} ScreenSummary
 * @property {string} id
 * @property {string} label
 * @property {string} url
 * @property {string} path
 * @property {string} title
 * @property {string} signature
 * @property {string[]} features
 * @property {number} w
 * @property {number} h
 * @property {number} nodeCount
 * @property {number} clickableCount
 * @property {number} firstSeen
 * @property {number} lastSeen
 * @property {number} visits
 */

/**
 * Reference to the element that triggered a navigation edge.
 * @typedef {Object} ElementRef
 * @property {string|null} selector
 * @property {string|null} xpath
 * @property {string|null} label
 * @property {string|null} text
 * @property {string|null} tag
 * @property {string|null} id
 */

/**
 * Directed navigation edge. `from`/`to` are screen ids ("S1"..), the string "START",
 * or "ext:" + origin.
 * @typedef {Object} Edge
 * @property {string} id
 * @property {string} from
 * @property {string} to
 * @property {("click"|"nav"|"back"|"external"|"submit"|"launch")} action
 * @property {ElementRef|null} element
 * @property {"manual"} source
 * @property {number} count
 * @property {number} firstAt
 * @property {number} lastAt
 */

/**
 * A recorded browsing session for one origin.
 * @typedef {Object} Session
 * @property {string} id
 * @property {string} name
 * @property {string} origin
 * @property {string} startUrl
 * @property {number} createdAt
 * @property {number} updatedAt
 * @property {ScreenSummary[]} screens
 * @property {Edge[]} edges
 * @property {number} nextScreenNo
 * @property {number} nextEdgeNo
 */

export const START_NODE = 'START';
export const EXTERNAL_PREFIX = 'ext:';

/** @param {string} origin @returns {string} */
export function externalNodeId(origin) {
  return EXTERNAL_PREFIX + origin;
}

/** @param {string} id @returns {boolean} */
export function isExternalNode(id) {
  return typeof id === 'string' && id.startsWith(EXTERNAL_PREFIX);
}

/** Origin part of an external node id ("ext:https://x.com" -> "https://x.com"). */
export function externalOrigin(id) {
  return isExternalNode(id) ? id.slice(EXTERNAL_PREFIX.length) : id;
}

// --------------------------------------------------------------------------- text helpers

/** Collapse whitespace, trim and clip; returns null for empty input. */
export function clean(value, maxLen = 200) {
  if (value == null) return null;
  const one = String(value).replace(/\s+/g, ' ').trim();
  if (!one) return null;
  return one.length > maxLen ? one.slice(0, maxLen) : one;
}

/** Single line, at most [max] chars with an ellipsis; safe on empty input. */
export function clip(value, max) {
  if (value == null) return '';
  const one = String(value).replace(/[\r\n]+/g, ' ').trim();
  if (one.length <= max) return one;
  return one.slice(0, Math.max(0, max - 1)).trimEnd() + '…';
}

/**
 * HTML-escapes untrusted text (& < > " ') and turns control characters into spaces.
 * Use this (or textContent) for every page-derived string put into markup.
 */
export function escapeHtml(value) {
  if (value == null || value === '') return '';
  const s = String(value);
  let out = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    switch (ch) {
      case '&': out += '&amp;'; break;
      case '<': out += '&lt;'; break;
      case '>': out += '&gt;'; break;
      case '"': out += '&quot;'; break;
      case "'": out += '&#39;'; break;
      default:
        out += isControl(ch) ? ' ' : ch;
    }
  }
  return out;
}

/** Attribute-context escaping (adds backtick/equals neutralisation for old IE-style sinks). */
export function escapeAttr(value) {
  return escapeHtml(value).replace(/`/g, '&#96;').replace(/=/g, '&#61;');
}

function isControl(ch) {
  const c = ch.charCodeAt(0);
  return (c < 0x20 && ch !== '\n' && ch !== '\t') || c === 0x7f;
}

/**
 * One RFC-4180 CSV cell for untrusted text: quotes when needed, doubles inner quotes,
 * and guards against spreadsheet formula injection by prefixing a leading = + - @ tab CR.
 */
export function csvCell(value) {
  if (value == null || value === '') return '';
  let s = String(value);
  const first = s.charAt(0);
  if (first === '=' || first === '+' || first === '-' || first === '@' || first === '\t' || first === '\r') {
    s = "'" + s;
  }
  if (/[",\r\n]/.test(s)) {
    return '"' + s.replace(/"/g, '""') + '"';
  }
  return s;
}

// --------------------------------------------------------------------------- tree helpers

/** @param {Rect} r @returns {boolean} */
export function rectEmpty(r) {
  if (!r) return true;
  return (r.r - r.l) <= 0 || (r.b - r.t) <= 0;
}

/** "[l,t][r,b]" for a rect. */
export function rectStr(r) {
  if (!r) return '';
  return `[${r.l | 0},${r.t | 0}][${r.r | 0},${r.b | 0}]`;
}

/** A single attribute value, or null. */
export function attr(node, key) {
  return node && node.attrs && node.attrs[key] != null ? node.attrs[key] : null;
}

/** Pre-order flatten of a node tree (children keep document order). */
export function flatten(root) {
  const out = [];
  if (!root) return out;
  const stack = [root];
  while (stack.length) {
    const n = stack.pop();
    out.push(n);
    const kids = n.children;
    if (kids && kids.length) {
      for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]);
    }
  }
  return out;
}

/** Node with the given pre-order index, or null. */
export function findByIdx(root, idx) {
  if (!root || idx == null) return null;
  const stack = [root];
  while (stack.length) {
    const n = stack.pop();
    if (n.idx === idx) return n;
    const kids = n.children;
    if (kids && kids.length) {
      for (let i = kids.length - 1; i >= 0; i--) {
        // Pre-order: only descend where the target index can live.
        if (idx >= kids[i].idx) stack.push(kids[i]);
      }
    }
  }
  return null;
}

export function countNodes(root) {
  return flatten(root).length;
}

export function countClickable(root) {
  let n = 0;
  for (const node of flatten(root)) if (node.clickable) n++;
  return n;
}

/**
 * Human-readable label for a node: accessible name, then own visible text, then the first
 * text/name found in its subtree (interactive containers usually wrap a label), then a few
 * useful attributes, then the id or tag.
 */
export function labelOf(node, maxLen = 60) {
  if (!node) return null;
  const own = clean(node.name, maxLen) || clean(node.text, maxLen);
  if (own) return own;
  const sub = firstSubtreeText(node, maxLen);
  if (sub) return sub;
  return (
    clean(attr(node, 'aria-label'), maxLen) ||
    clean(attr(node, 'placeholder'), maxLen) ||
    clean(attr(node, 'title'), maxLen) ||
    clean(attr(node, 'alt'), maxLen) ||
    (node.id ? clean(node.id, maxLen) : null) ||
    (node.tag || null)
  );
}

function firstSubtreeText(node, maxLen) {
  const queue = [...(node.children || [])];
  let guard = 0;
  while (queue.length && guard++ < 4000) {
    const n = queue.shift();
    const t = clean(n.name, maxLen) || clean(n.text, maxLen);
    if (t) return t;
    if (n.children && n.children.length) queue.push(...n.children);
  }
  return null;
}

/** Captured text, but never for password fields (defence in depth; capture drops it too). */
export function safeText(node) {
  if (!node || node.password) return null;
  return node.text;
}

/**
 * Element reference for an edge: the parts a downstream tool needs to point at the element
 * again ({selector, xpath, label, text, tag, id}).
 * @returns {ElementRef}
 */
export function toElementRef(node) {
  if (!node) return { selector: null, xpath: null, label: null, text: null, tag: null, id: null };
  return {
    selector: node.selector || null,
    xpath: node.xpath || null,
    label: labelOf(node),
    text: clean(safeText(node), 120),
    tag: node.tag || null,
    id: node.id || null,
  };
}

/** Best human-readable text for an element reference. */
export function refDisplay(ref) {
  if (!ref) return '';
  return (
    clean(ref.label, 80) ||
    clean(ref.text, 80) ||
    (ref.id ? '#' + ref.id : null) ||
    ref.selector ||
    ref.tag ||
    ''
  );
}

/** Identity used to de-duplicate edges and remember which elements were already seen. */
export function elementKey(ref) {
  if (!ref) return '';
  return ref.selector || ref.xpath || (ref.id ? '#' + ref.id : '') || ref.label || ref.text || ref.tag || '?';
}
