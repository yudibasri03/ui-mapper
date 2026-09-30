// UI Mapper Web - navigation graph queries and layout.
//
// Pure graph helpers over a session's navigation edges, plus a layered layout used by both
// the side-panel map and the HTML export. No DOM, no chrome.* APIs.

import { START_NODE, isExternalNode, externalOrigin, refDisplay, clip } from './model.js';

/** Screen reached by the launch (target of the first START edge), else the first screen. */
export function startScreenId(session) {
  const launch = (session.edges || []).find((e) => e.from === START_NODE);
  if (launch) return launch.to;
  const first = (session.screens || [])[0];
  return first ? first.id : null;
}

/**
 * Edges a forward route may use: no self-loops, nothing leaving an external node, and BACK
 * edges only when [includeBack]. "nav" edges (address changes without a recorded click) stay
 * usable, otherwise a screen behind a redirect would be unreachable.
 */
function routeEdges(session, includeBack) {
  return (session.edges || []).filter(
    (e) => e.from !== e.to && !isExternalNode(e.from) && (includeBack || e.action !== 'back'),
  );
}

/** Automatic ("nav") transitions cost more, so a route of real clicks wins when one exists. */
function cost(e) {
  return e.action === 'nav' ? 2 : 1;
}

/**
 * Cheapest route [from] -> [to] (Dijkstra; a click costs 1, an automatic transition 2).
 * Returns [] when from === to, null when unreachable.
 */
export function shortestPath(session, from, to, includeBack = false) {
  if (from === to) return [];
  return shortestTree(session, from, includeBack)[to] || null;
}

function shortestTree(session, from, includeBack) {
  const edges = routeEdges(session, includeBack);
  const byFrom = new Map();
  for (const e of edges) {
    if (!byFrom.has(e.from)) byFrom.set(e.from, []);
    byFrom.get(e.from).push(e);
  }
  const dist = new Map([[from, 0]]);
  const steps = new Map([[from, 0]]);
  const prev = new Map();
  const done = new Set();
  // Graphs are small; a linear scan for the current minimum is enough.
  for (;;) {
    let cur = null;
    for (const [node, d] of dist) {
      if (done.has(node)) continue;
      if (
        cur === null ||
        d < dist.get(cur) ||
        (d === dist.get(cur) && steps.get(node) < steps.get(cur))
      ) {
        cur = node;
      }
    }
    if (cur === null) break;
    done.add(cur);
    const du = dist.get(cur);
    const su = steps.get(cur);
    for (const e of byFrom.get(cur) || []) {
      if (done.has(e.to)) continue;
      const nd = du + cost(e);
      const old = dist.has(e.to) ? dist.get(e.to) : null;
      if (old === null || nd < old || (nd === old && su + 1 < steps.get(e.to))) {
        dist.set(e.to, nd);
        steps.set(e.to, su + 1);
        prev.set(e.to, e);
      }
    }
  }
  const out = {};
  for (const node of done) {
    const path = [];
    let n = node;
    while (n !== from) {
      const edge = prev.get(n);
      if (!edge) break;
      path.push(edge);
      n = edge.from;
    }
    path.reverse();
    out[node] = path;
  }
  return out;
}

/** Cheapest route from the start screen to every reachable screen, keyed by screen id. */
export function routesFromStart(session) {
  const start = startScreenId(session);
  if (!start) return {};
  const tree = shortestTree(session, start, false);
  const out = {};
  for (const s of session.screens || []) {
    if (tree[s.id]) out[s.id] = tree[s.id];
  }
  return out;
}

/** BFS depth of each reachable node from the start screen (unreachable nodes absent). */
export function depths(session) {
  const start = startScreenId(session);
  if (!start) return {};
  const byFrom = new Map();
  for (const e of routeEdges(session, false)) {
    if (!byFrom.has(e.from)) byFrom.set(e.from, []);
    byFrom.get(e.from).push(e);
  }
  const depth = { [start]: 0 };
  const queue = [start];
  while (queue.length) {
    const cur = queue.shift();
    const d = depth[cur];
    for (const e of byFrom.get(cur) || []) {
      if (!(e.to in depth)) {
        depth[e.to] = d + 1;
        queue.push(e.to);
      }
    }
  }
  return depth;
}

/** Indonesian verb for an action, used in route descriptions. */
export function actionVerb(action) {
  switch (action) {
    case 'click': return 'Klik';
    case 'submit': return 'Kirim formulir';
    case 'nav': return 'Pindah';
    case 'back': return 'Kembali';
    case 'external': return 'Ke situs lain';
    case 'launch': return 'Buka situs';
    default: return 'Transisi';
  }
}

/** BACK and automatic NAV transitions are drawn dashed. */
export function isDashed(edge) {
  return edge.action === 'back' || edge.action === 'nav';
}

/** Human label of any graph node id ("Mulai", "Situs lain (host)", "S1 · Beranda", raw id). */
export function nodeLabel(session, id) {
  if (id === START_NODE) return 'Mulai';
  if (isExternalNode(id)) return 'Situs lain (' + hostOf(externalOrigin(id)) + ')';
  const sc = (session.screens || []).find((s) => s.id === id);
  return sc ? sc.id + ' · ' + sc.label : id;
}

function hostOf(origin) {
  try {
    return new URL(origin).host || origin;
  } catch {
    return origin;
  }
}

/** 'Klik "Masuk"' / 'Kembali' / 'Buka situs'. */
export function stepText(edge) {
  const verb = actionVerb(edge.action);
  const el = edge.element ? refDisplay(edge.element) : '';
  return el ? verb + ' "' + el + '"' : verb;
}

/** stepText plus " ×N" when the edge was seen more than once. */
export function edgeLabel(edge, max = 60) {
  const base = clip(stepText(edge), max);
  return edge.count > 1 ? base + ' ×' + edge.count : base;
}

/** "S1 · Beranda —[Klik "Masuk"]→ S2 · Login". */
export function describe(session, edge) {
  return nodeLabel(session, edge.from) + ' —[' + stepText(edge) + ']→ ' + nodeLabel(session, edge.to);
}

// --------------------------------------------------------------------------- layout

const NODE_W = 168;
const NODE_H = 50;
const GAP_X = 36;
const GAP_Y = 78;
const PAD_L = 24;
const PAD_R = 104;
const PAD_T = 36;
const PAD_B = 60;
const MAX_PER_ROW = 6;
const PAIR_SPREAD = 16;
const MAP_LABEL_CHARS = 24;
const MAP_EDGE_CHARS = 22;

/**
 * Layered navigation map: START on top, screens by BFS depth from the start screen, screens
 * unreachable from it in a final layer, external sites one layer below their source. Returns
 * positioned nodes and cubic-bezier edges ready to draw as SVG.
 *
 * @returns {{
 *   width:number, height:number,
 *   nodes: Array<{id:string,label:string,kind:('start'|'screen'|'external'|'other'),
 *                 x:number,y:number,w:number,h:number,cx:number,cy:number,
 *                 line1:string,line2:string,tip:string,unreached:boolean,home:boolean}>,
 *   edges: Array<{from:string,to:string,label:string,dashed:boolean,action:string,count:number,
 *                 selfLoop:boolean,points:Array<{x:number,y:number}>,d:string,
 *                 labelX:number,labelY:number,anchor:string,tip:string}>
 * }}
 */
export function layout(session) {
  const dpt = depths(session);
  const startId = startScreenId(session);
  const nodes = buildNodes(session, dpt, startId);
  const size = placeNodes(session, nodes);
  const byId = new Map();
  for (const n of nodes) byId.set(n.id, n);

  const pairTotals = new Map();
  for (const e of session.edges || []) {
    const k = pairKey(e);
    pairTotals.set(k, (pairTotals.get(k) || 0) + 1);
  }
  const pairSeen = new Map();
  const edges = [];
  for (const e of session.edges || []) {
    const a = byId.get(e.from);
    const b = byId.get(e.to);
    if (!a || !b) continue;
    const k = pairKey(e);
    const seen = pairSeen.get(k) || 0;
    pairSeen.set(k, seen + 1);
    const total = pairTotals.get(k) || 1;
    const off = (seen - (total - 1) / 2) * PAIR_SPREAD;
    const c = curve(a, b, off);
    const selfLoop = a === b;
    edges.push({
      from: e.from,
      to: e.to,
      label: edgeLabel(e, MAP_EDGE_CHARS),
      dashed: isDashed(e),
      action: e.action,
      count: e.count,
      selfLoop,
      points: c.points,
      d: c.d,
      labelX: Math.round(c.midX + (selfLoop ? 4 : 0)),
      labelY: Math.round(c.midY + 3),
      anchor: selfLoop ? 'start' : 'middle',
      tip: describe(session, e) + (e.count > 1 ? ' ×' + e.count : ''),
    });
  }

  return { width: size.width, height: size.height, nodes, edges };
}

function buildNodes(session, dpt, startId) {
  const map = new Map();
  const referenced = new Set();
  for (const e of session.edges || []) {
    referenced.add(e.from);
    referenced.add(e.to);
  }
  const hasStart = referenced.has(START_NODE);
  let maxLayer = -1;
  if (hasStart) {
    map.set(START_NODE, node(START_NODE, 'start', 'Mulai', '', 'Mulai · situs dibuka', false, false, 0));
    maxLayer = 0;
  }
  const base = hasStart ? 1 : 0;

  for (const sc of session.screens || []) {
    const d = dpt[sc.id];
    const unreached = d == null;
    const tip = sc.id + ' · ' + sc.label + (sc.path ? ' (' + sc.path + ')' : '');
    const n = node(sc.id, 'screen', sc.id, clip(sc.label, MAP_LABEL_CHARS), tip, unreached, sc.id === startId, 0);
    if (d != null) {
      n.layer = base + d;
      maxLayer = Math.max(maxLayer, n.layer);
    }
    map.set(sc.id, n);
  }

  const deferred = [];
  for (const id of referenced) {
    if (map.has(id)) continue;
    if (isExternalNode(id)) {
      const origin = externalOrigin(id);
      const n = node(id, 'external', 'Situs lain', clip(hostOf(origin), MAP_LABEL_CHARS), 'Situs lain: ' + origin, false, false, 0);
      let srcLayer = null;
      for (const e of session.edges || []) {
        if (e.to !== id || e.from === id) continue;
        const src = map.get(e.from);
        if (!src) continue;
        const placedSource = src.kind === 'start' || (src.kind === 'screen' && !src.unreached);
        if (placedSource) srcLayer = srcLayer == null ? src.layer : Math.min(srcLayer, src.layer);
      }
      if (srcLayer != null) {
        n.layer = srcLayer + 1;
        maxLayer = Math.max(maxLayer, n.layer);
      } else {
        deferred.push(n);
      }
      map.set(id, n);
    } else if (id !== START_NODE) {
      const n = node(id, 'other', id, '', id, true, false, 0);
      deferred.push(n);
      map.set(id, n);
    }
  }

  const finalLayer = maxLayer + 1;
  for (const n of map.values()) if (n.kind === 'screen' && n.unreached) n.layer = finalLayer;
  for (const n of deferred) {
    n.layer = n.kind === 'external' && (session.edges || []).some((e) => e.to === n.id) ? finalLayer + 1 : finalLayer;
  }
  return [...map.values()];
}

function node(id, kind, line1, line2, tip, unreached, home, layer) {
  return {
    id,
    kind,
    label: line2 ? line1 + ' · ' + line2 : line1,
    line1,
    line2,
    tip,
    unreached,
    home,
    layer,
    row: 0,
    x: 0,
    y: 0,
    cx: 0,
    cy: 0,
    w: kind === 'start' ? NODE_H : NODE_W,
    h: NODE_H,
    placed: false,
  };
}

function placeNodes(session, nodes) {
  const byLayer = new Map();
  for (const n of nodes) {
    if (!byLayer.has(n.layer)) byLayer.set(n.layer, []);
    byLayer.get(n.layer).push(n);
  }
  const layerKeys = [...byLayer.keys()].sort((a, b) => a - b);
  let cols = 1;
  for (const list of byLayer.values()) cols = Math.max(cols, Math.min(list.length, MAX_PER_ROW));
  const innerW = cols * NODE_W + (cols - 1) * GAP_X;

  const preds = new Map();
  for (const e of session.edges || []) {
    if (e.from === e.to) continue;
    if (!preds.has(e.to)) preds.set(e.to, []);
    preds.get(e.to).push(e.from);
  }
  const byId = new Map();
  for (const n of nodes) byId.set(n.id, n);

  let row = 0;
  for (const key of layerKeys) {
    const list = byLayer.get(key);
    const ordered = list
      .map((n, i) => ({ n, bc: barycenter(n, preds, byId), i }))
      .sort((a, b) => (a.bc - b.bc) || (a.i - b.i))
      .map((o) => o.n);
    for (let start = 0; start < ordered.length; start += MAX_PER_ROW) {
      const chunk = ordered.slice(start, start + MAX_PER_ROW);
      const rowW = chunk.length * NODE_W + (chunk.length - 1) * GAP_X;
      let cx = PAD_L + (innerW - rowW) / 2 + NODE_W / 2;
      const cy = PAD_T + row * (NODE_H + GAP_Y) + NODE_H / 2;
      for (const n of chunk) {
        n.row = row;
        n.cx = cx;
        n.cy = cy;
        n.x = Math.round(cx - n.w / 2);
        n.y = Math.round(cy - n.h / 2);
        n.placed = true;
        cx += NODE_W + GAP_X;
      }
      row++;
    }
  }
  const width = PAD_L + innerW + PAD_R;
  const height = PAD_T + row * NODE_H + Math.max(0, row - 1) * GAP_Y + PAD_B;
  return { width, height };
}

function barycenter(n, preds, byId) {
  let sum = 0;
  let count = 0;
  for (const p of preds.get(n.id) || []) {
    const pn = byId.get(p);
    if (pn && pn.placed) {
      sum += pn.cx;
      count++;
    }
  }
  return count === 0 ? Number.MAX_VALUE : sum / count;
}

function pairKey(e) {
  return e.from <= e.to ? e.from + '\u0000' + e.to : e.to + '\u0000' + e.from;
}

function curve(a, b, off) {
  const hw = NODE_W / 2;
  const hh = NODE_H / 2;
  let p;
  if (a === b) {
    const x = a.cx + hw;
    p = [
      { x, y: a.cy - 10 },
      { x: x + 46, y: a.cy - 34 },
      { x: x + 46, y: a.cy + 34 },
      { x, y: a.cy + 10 },
    ];
  } else if (b.row > a.row) {
    const x0 = a.cx + off;
    const y0 = a.cy + hh;
    const x3 = b.cx + off;
    const y3 = b.cy - hh;
    const dy = (y3 - y0) * 0.5;
    p = [
      { x: x0, y: y0 },
      { x: x0, y: y0 + dy },
      { x: x3, y: y3 - dy },
      { x: x3, y: y3 },
    ];
  } else if (b.row < a.row) {
    const x0 = a.cx + hw;
    const y0 = a.cy + off * 0.4;
    const x3 = b.cx + hw;
    const y3 = b.cy + off * 0.4;
    const bulge = Math.min(28 + (a.row - b.row) * 14 + Math.abs(off), PAD_R - 12);
    const xc = Math.max(x0, x3) + bulge;
    p = [
      { x: x0, y: y0 },
      { x: xc, y: y0 },
      { x: xc, y: y3 },
      { x: x3, y: y3 },
    ];
  } else {
    const lift = Math.min(18 + Math.abs(b.cx - a.cx) * 0.08 + Math.abs(off), GAP_Y * 0.8);
    if (b.cx > a.cx) {
      const y = a.cy - hh;
      p = [
        { x: a.cx + off, y },
        { x: a.cx + off, y: y - lift },
        { x: b.cx + off, y: y - lift },
        { x: b.cx + off, y },
      ];
    } else {
      const y = a.cy + hh;
      p = [
        { x: a.cx + off, y },
        { x: a.cx + off, y: y + lift },
        { x: b.cx + off, y: y + lift },
        { x: b.cx + off, y },
      ];
    }
  }
  const midX = (p[0].x + 3 * p[1].x + 3 * p[2].x + p[3].x) / 8;
  const midY = (p[0].y + 3 * p[1].y + 3 * p[2].y + p[3].y) / 8;
  const d =
    'M' + r(p[0].x) + ' ' + r(p[0].y) +
    ' C' + r(p[1].x) + ' ' + r(p[1].y) + ', ' + r(p[2].x) + ' ' + r(p[2].y) + ', ' + r(p[3].x) + ' ' + r(p[3].y);
  return { points: p, d, midX, midY };
}

function r(v) {
  return Math.round(v);
}
