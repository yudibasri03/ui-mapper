/*
 * UI Mapper Web - in-page content script (inspector overlay, DOM capture, route detection).
 *
 * Self-contained CLASSIC script. It is injected on demand into the active tab via
 * chrome.scripting.executeScript({ files: ["content/inspector.js"] }). It uses NO import/export
 * and defines nothing on the page global other than the injection guard.
 *
 * Design goals:
 *   - Never break the host page: every listener/handler is wrapped so a thrown error is swallowed.
 *   - No network of any kind (all data goes to the extension via chrome.runtime messages).
 *   - Passive by default: no overlay and no inspect listeners exist while inspect mode is OFF.
 *   - Privacy: never read or store the value of password inputs; never persist text set via the editor.
 *
 * Data model, message types and storage layout are shared with the rest of the extension
 * (see lib/model.js). This file produces the exact WNode / Screen shapes and speaks the exact
 * "cs.*" (content -> background) and "bg.*" (background -> content) message contract.
 *
 * User-visible strings are in Bahasa Indonesia; code and comments are in English.
 */
(function () {
  "use strict";

  // ---- Double-injection guard (isolated world persists per frame per extension) ----------------
  try {
    if (window.__uiMapperInjected) { return; }
    window.__uiMapperInjected = true;
  } catch (e) {
    // If we cannot even touch window, there is nothing safe to do.
    return;
  }

  // =============================================================================================
  //  State
  // =============================================================================================
  var recording = false;     // set via bg.startRecord / bg.stopRecord; routes are only SENT while true
  var inspecting = false;    // set via bg.startInspect / bg.stopInspect
  var editing = false;       // set via bg.setEdit; guards bg.setText
  var sessionId = null;

  // idx -> DOM Element and Element -> idx for the most recently SENT capture (kept in sync so that
  // bg.selectNode / bg.scrollTo / cs.hover / cs.picked reference the same tree the panel is showing).
  var currentNodeMap = [];
  var currentElementToIdx = new WeakMap();

  var selectedEl = null;     // currently selected element in inspect mode
  var lastHover = null;      // last hovered element (avoids redundant redraws)
  var lastPickPoint = null;  // last inspect-click point (used to cycle to parent on repeated clicks)

  // Route detection bookkeeping.
  var pendingTrigger = null; // { info: Edge.element, at: ms } - the actionable element the user clicked
  var pendingVia = null;     // one of push|replace|pop|hash|load
  var pendingViaAt = 0;
  var lastKnownHref = location.href;
  var lastSentUrl = "";
  var lastSentSignature = "";

  // Debounce timers.
  var lastCaptureAt = 0;
  var captureTimer = null;
  var settleTimer = null;
  var locTimer = null;
  var repoRAF = 0;
  var mo = null;             // MutationObserver (only connected while recording || inspecting)

  // Overlay (Shadow DOM) references.
  var overlayHost = null, shadow = null;
  var hoverBox = null, selBox = null, hoverChip = null, selChip = null;
  var actionsLayer = null, toolbar = null, breadcrumbEl = null, recBtn = null;
  var showActionables = false;
  var actBoxes = [];         // [{ el, div }] for the optional faint-actionables layer
  var draggingToolbar = false;

  // Caps to keep captures bounded on huge/pathological pages.
  var MAX_NODES = 4000;
  var MAX_DEPTH = 60;
  var MAX_FEATURES = 800;

  var SKIP_TAGS = { script: 1, style: 1, noscript: 1, template: 1, head: 1, meta: 1, link: 1, title: 1, base: 1 };
  var BLOCK_EVENTS = ["mousedown", "pointerdown", "mouseup", "pointerup", "dblclick", "auxclick", "contextmenu", "submit"];

  var inspectMove = null, inspectClick = null, inspectScroll = null, inspectKey = null;

  // =============================================================================================
  //  Small utilities
  // =============================================================================================
  function safeSend(msg) {
    try {
      chrome.runtime.sendMessage(msg, function () { void chrome.runtime.lastError; });
    } catch (e) {
      // Extension context invalidated (e.g. reloaded) - ignore; the page must keep working.
    }
  }

  function log(tag, message) {
    safeSend({ type: "cs.log", tag: String(tag), message: String(message) });
  }

  function trim(s, n) {
    s = String(s);
    return s.length > n ? s.slice(0, n) : s;
  }

  function esc(s) {
    try { return CSS.escape(String(s)); }
    catch (e) { return String(s).replace(/[^\w-]/g, function (c) { return "\\" + c; }); }
  }

  function attrVal(s) {
    return String(s).replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
  }

  // FNV-1a 32-bit -> 8 hex chars. Small, stable string hash for the screen signature.
  function fnv1a(str) {
    var h = 0x811c9dc5;
    for (var i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
    }
    return ("00000000" + h.toString(16)).slice(-8);
  }

  // =============================================================================================
  //  Element classification helpers
  // =============================================================================================
  function shouldSkip(el) {
    try {
      if (!el || el.nodeType !== 1) { return true; }
      var t = el.tagName ? el.tagName.toLowerCase() : "";
      if (SKIP_TAGS[t]) { return true; }
      if (el === overlayHost) { return true; }
      if (el.hasAttribute && el.hasAttribute("data-ui-mapper-overlay")) { return true; }
    } catch (e) { return true; }
    return false;
  }

  function isVisible(cs2, rect) {
    try {
      if (!cs2) { return (rect.width > 0 || rect.height > 0); }
      if (cs2.display === "none") { return false; }
      if (cs2.visibility === "hidden" || cs2.visibility === "collapse") { return false; }
      return (rect.width > 0 || rect.height > 0);
    } catch (e) { return false; }
  }

  function isEditable(el, tag, type) {
    try {
      if (tag === "textarea") { return true; }
      if (tag === "input") {
        var non = { button: 1, submit: 1, reset: 1, image: 1, checkbox: 1, radio: 1, range: 1, color: 1, file: 1, hidden: 1 };
        return !non[type || "text"];
      }
      if (el.isContentEditable) { return true; }
    } catch (e) {}
    return false;
  }

  function isClickable(el, tag, type, cs2) {
    try {
      if (tag === "a" && el.hasAttribute("href")) { return true; }
      if (tag === "button" || tag === "summary" || tag === "select") { return true; }
      if (tag === "input" && (type === "button" || type === "submit" || type === "reset" || type === "image" || type === "checkbox" || type === "radio")) { return true; }
      var role = (el.getAttribute("role") || "").toLowerCase();
      switch (role) {
        case "button": case "link": case "menuitem": case "menuitemcheckbox":
        case "menuitemradio": case "tab": case "checkbox": case "radio":
        case "switch": case "option": return true;
      }
      if (el.hasAttribute("onclick")) { return true; }
      var ti = el.getAttribute("tabindex");
      if (ti !== null && parseInt(ti, 10) >= 0) {
        if (cs2 && cs2.cursor === "pointer") { return true; }
      }
    } catch (e) {}
    return false;
  }

  // Implicit/explicit ARIA role (best-effort; explicit role wins).
  function ariaRole(el, tag) {
    try {
      var explicit = el.getAttribute("role");
      if (explicit && explicit.trim()) { return explicit.trim().split(/\s+/)[0]; }
      switch (tag) {
        case "a": return el.hasAttribute("href") ? "link" : null;
        case "button": return "button";
        case "nav": return "navigation";
        case "main": return "main";
        case "header": return "banner";
        case "footer": return "contentinfo";
        case "aside": return "complementary";
        case "form": return "form";
        case "ul": case "ol": return "list";
        case "li": return "listitem";
        case "table": return "table";
        case "img": return "img";
        case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": return "heading";
        case "select": return "combobox";
        case "textarea": return "textbox";
        case "input": {
          var t = (el.getAttribute("type") || "text").toLowerCase();
          if (t === "checkbox") { return "checkbox"; }
          if (t === "radio") { return "radio"; }
          if (t === "button" || t === "submit" || t === "reset" || t === "image") { return "button"; }
          if (t === "range") { return "slider"; }
          if (t === "search") { return "searchbox"; }
          return "textbox";
        }
      }
    } catch (e) {}
    return null;
  }

  // Accessible name (best-effort subset of the accname algorithm).
  function accessibleName(el, tag) {
    try {
      var al = el.getAttribute && el.getAttribute("aria-label");
      if (al && al.trim()) { return trim(al.trim(), 120); }

      var lb = el.getAttribute && el.getAttribute("aria-labelledby");
      if (lb) {
        var s = "";
        lb.split(/\s+/).forEach(function (id) {
          var t = document.getElementById(id);
          if (t) { s += " " + (t.textContent || ""); }
        });
        s = s.trim().replace(/\s+/g, " ");
        if (s) { return trim(s, 120); }
      }

      if (tag === "img" || tag === "area" || tag === "input") {
        var alt = el.getAttribute("alt");
        if (alt && alt.trim()) { return trim(alt.trim(), 120); }
      }

      var title = el.getAttribute && el.getAttribute("title");
      if (title && title.trim()) { return trim(title.trim(), 120); }

      if (tag === "input") {
        var type = (el.getAttribute("type") || "text").toLowerCase();
        if (type === "button" || type === "submit" || type === "reset") {
          var v = el.getAttribute("value");
          if (v && v.trim()) { return trim(v.trim(), 120); }
        }
        var ph = el.getAttribute("placeholder");
        if (ph && ph.trim()) { return trim(ph.trim(), 120); }
      }

      // Text-content fallback only for elements whose visible text is a natural label.
      if (/^(a|button|h[1-6]|label|legend|summary|option|li|td|th|caption|figcaption|dt|dd)$/.test(tag) ||
          (el.children && el.children.length === 0)) {
        var tc = (el.textContent || "").trim().replace(/\s+/g, " ");
        if (tc) { return trim(tc, 120); }
      }
    } catch (e) {}
    return null;
  }

  // Own direct text only (first ~120 chars). NEVER read a password field's value.
  function ownText(el, isPassword) {
    if (isPassword) { return null; }
    try {
      var s = "";
      var kids = el.childNodes;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].nodeType === 3) { s += kids[i].nodeValue; }
      }
      s = s.trim().replace(/\s+/g, " ");
      if (!s) { return null; }
      return trim(s, 120);
    } catch (e) { return null; }
  }

  // Safe attribute allowlist. `value` is included only for non-password form controls.
  function collectAttrs(el, tag, type, isPassword) {
    var out = {};
    try {
      var allow = ["type", "name", "href", "src", "alt", "title", "placeholder", "data-testid"];
      for (var i = 0; i < allow.length; i++) {
        var a = allow[i];
        if (el.hasAttribute && el.hasAttribute(a)) { out[a] = trim(el.getAttribute(a) || "", 300); }
      }
      if (el.attributes) {
        for (var j = 0; j < el.attributes.length; j++) {
          var at = el.attributes[j];
          if (at.name.indexOf("aria-") === 0) { out[at.name] = trim(at.value || "", 300); }
        }
      }
      if (!isPassword && (tag === "input" || tag === "textarea" || tag === "select")) {
        if (type !== "password") {
          try {
            var val = el.value;
            if (val != null && val !== "") { out.value = trim(String(val), 300); }
          } catch (e) {}
        }
      }
    } catch (e) {}
    return out;
  }

  // =============================================================================================
  //  Selector / XPath
  // =============================================================================================
  function pickStableClass(el) {
    try {
      if (!el.classList || !el.classList.length) { return null; }
      for (var i = 0; i < el.classList.length; i++) {
        var c = el.classList[i];
        if (/^[a-zA-Z][\w-]{0,40}$/.test(c) &&
            !/^(ng|css|sc|jsx|makestyles|emotion)-/i.test(c) &&
            !/[0-9a-f]{6,}/i.test(c)) {
          return c;
        }
      }
    } catch (e) {}
    return null;
  }

  function nthOfType(el) {
    try {
      var p = el.parentElement;
      if (!p) { return 0; }
      var count = 0, index = 0;
      for (var i = 0; i < p.children.length; i++) {
        if (p.children[i].tagName === el.tagName) {
          count++;
          if (p.children[i] === el) { index = count; }
        }
      }
      return count > 1 ? index : 0;
    } catch (e) { return 0; }
  }

  // Reasonably-unique CSS selector: prefer #id; otherwise a short tag/class/nth-of-type chain,
  // verified for uniqueness at each step so we stop as soon as the selector is unambiguous.
  function cssSelector(el) {
    try {
      if (!el || el.nodeType !== 1) { return ""; }
      if (el.id) {
        var byId = "#" + esc(el.id);
        try { if (document.querySelectorAll(byId).length === 1) { return byId; } } catch (e) {}
      }
      var parts = [];
      var cur = el, depth = 0;
      while (cur && cur.nodeType === 1 && depth < 12) {
        var part = cur.tagName.toLowerCase();
        if (cur.id) {
          part = "#" + esc(cur.id);
        } else {
          var testid = cur.getAttribute("data-testid");
          if (testid) {
            part += "[data-testid=\"" + attrVal(testid) + "\"]";
          } else {
            var cls = pickStableClass(cur);
            if (cls) { part += "." + esc(cls); }
            var nth = nthOfType(cur);
            if (nth) { part += ":nth-of-type(" + nth + ")"; }
          }
        }
        parts.unshift(part);
        var cand = parts.join(" > ");
        try { if (document.querySelectorAll(cand).length === 1) { return cand; } } catch (e2) {}
        if (cur === document.documentElement) { break; }
        cur = cur.parentElement;
        depth++;
      }
      return parts.join(" > ") || el.tagName.toLowerCase();
    } catch (e) {
      try { return el.tagName.toLowerCase(); } catch (e3) { return "*"; }
    }
  }

  function xpathOf(el) {
    try {
      if (el === document.documentElement) { return "/html[1]"; }
      var parts = [];
      var cur = el, depth = 0;
      while (cur && cur.nodeType === 1 && depth < 80) {
        var ix = 1;
        var sib = cur.previousElementSibling;
        while (sib) { if (sib.tagName === cur.tagName) { ix++; } sib = sib.previousElementSibling; }
        parts.unshift(cur.tagName.toLowerCase() + "[" + ix + "]");
        if (cur === document.documentElement) { break; }
        cur = cur.parentElement;
        depth++;
      }
      return "/" + parts.join("/");
    } catch (e) { return ""; }
  }

  // =============================================================================================
  //  Node + tree capture
  // =============================================================================================
  function makeNode(el, idx, depth) {
    var tag = el.tagName.toLowerCase();
    var cs2 = null;
    try { cs2 = getComputedStyle(el); } catch (e) {}
    var rect;
    try { rect = el.getBoundingClientRect(); }
    catch (e2) { rect = { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 }; }

    var type = "";
    try { type = (el.getAttribute("type") || "").toLowerCase(); } catch (e3) {}
    var isPassword = (tag === "input" && type === "password");

    var href = null;
    try {
      if ((tag === "a" || tag === "area") && el.hasAttribute("href")) {
        href = el.href || el.getAttribute("href");
      }
    } catch (e4) {}

    var cls = [];
    try { if (el.classList) { cls = Array.prototype.slice.call(el.classList); } } catch (e5) {}

    return {
      idx: idx,
      depth: depth,
      tag: tag,
      id: el.id || null,
      cls: cls,
      role: ariaRole(el, tag),
      name: accessibleName(el, tag),
      text: ownText(el, isPassword),
      attrs: collectAttrs(el, tag, type, isPassword),
      rect: {
        l: Math.round(rect.left),
        t: Math.round(rect.top),
        r: Math.round(rect.right),
        b: Math.round(rect.bottom)
      },
      clickable: isClickable(el, tag, type, cs2),
      editable: isEditable(el, tag, type),
      password: isPassword,
      href: href,
      visible: isVisible(cs2, rect),
      selector: cssSelector(el),
      xpath: xpathOf(el),
      children: []
    };
  }

  // Pre-order walk of document.documentElement -> WNode tree (+ idx maps). Iterative-friendly caps.
  function captureTree() {
    var map = [];
    var e2i = new WeakMap();
    var count = 0;

    function build(el, depth) {
      if (count >= MAX_NODES) { return null; }
      var idx = count++;
      map[idx] = el;
      try { e2i.set(el, idx); } catch (e) {}
      var node = makeNode(el, idx, depth);
      if (depth < MAX_DEPTH && el.children && el.children.length) {
        for (var i = 0; i < el.children.length; i++) {
          if (count >= MAX_NODES) { break; }
          var child = el.children[i];
          if (shouldSkip(child)) { continue; }
          var cn = build(child, depth + 1);
          if (cn) { node.children.push(cn); }
        }
      }
      return node;
    }

    var rootEl = document.documentElement || document.body;
    var root = rootEl ? build(rootEl, 0) : null;
    return { root: root, map: map, elementToIdx: e2i };
  }

  // Structural feature list (set semantics) + short signature hash. Background does the real matching.
  function buildFeatures(root) {
    var set = new Set();
    try {
      if (document.title) { set.add("T:" + document.title.slice(0, 60)); }
    } catch (e) {}
    (function walk(n) {
      if (set.size >= MAX_FEATURES) { return; }
      if (n.visible) {
        var idPart = n.id ? ("#" + n.id) : "";
        var clsHint = (n.cls && n.cls.length) ? ("." + n.cls[0]) : "";
        set.add("V:" + n.tag + idPart + clsHint);
      }
      if (n.clickable) {
        set.add("C:" + n.tag + "|" + ((n.name || n.text || "").slice(0, 40)));
      }
      if (/^h[1-6]$/.test(n.tag)) {
        var t = (n.text || n.name || "");
        if (t) { set.add("T:" + t.slice(0, 60)); }
      }
      if (n.editable) {
        var ph = (n.attrs && n.attrs.placeholder) || "";
        set.add("E:" + (n.selector || "").slice(0, 80) + "|" + ph.slice(0, 40));
      }
      if (n.children) {
        for (var i = 0; i < n.children.length && set.size < MAX_FEATURES; i++) { walk(n.children[i]); }
      }
    })(root);
    var arr = Array.from(set);
    arr.sort();
    return arr;
  }

  // Build the full Screen snapshot (does NOT mutate global maps - the caller decides when to adopt).
  function buildScreen() {
    var cap = captureTree();
    var root = cap.root || makeNode(document.documentElement || document.body || document.createElement("html"), 0, 0);
    var features = buildFeatures(root);
    var signature = fnv1a(features.join("\n"));

    var nodeCount = 0, clickableCount = 0;
    (function count(n) {
      nodeCount++;
      if (n.clickable) { clickableCount++; }
      if (n.children) { for (var i = 0; i < n.children.length; i++) { count(n.children[i]); } }
    })(root);

    var screen = {
      id: null, // background assigns the S-number
      url: location.href,
      path: location.pathname + (location.hash || ""),
      title: document.title || "",
      signature: signature,
      features: features,
      capturedAt: Date.now(),
      w: window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0,
      h: window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0,
      nodeCount: nodeCount,
      clickableCount: clickableCount,
      root: root
    };
    return { screen: screen, map: cap.map, elementToIdx: cap.elementToIdx };
  }

  function computeDepth(el) {
    var d = 0, c = el;
    try {
      while (c && c !== document.documentElement) { c = c.parentElement; d++; if (d > 200) { break; } }
    } catch (e) {}
    return d;
  }

  // Edge.element descriptor for a clicked / triggering element.
  function elementInfo(el) {
    var tag = el.tagName.toLowerCase();
    var label = accessibleName(el, tag) || "";
    var text = ownText(el, false);
    if (!text) {
      try { text = (el.textContent || "").trim().replace(/\s+/g, " ").slice(0, 120); }
      catch (e) { text = ""; }
    }
    return {
      selector: cssSelector(el),
      xpath: xpathOf(el),
      label: trim(label, 120),
      text: trim(text || "", 120),
      tag: tag,
      id: el.id || null
    };
  }

  function findActionable(start) {
    var el = start, n = 0;
    while (el && el.nodeType === 1 && n < 25) {
      var tag = el.tagName.toLowerCase();
      var type = "";
      try { type = (el.getAttribute("type") || "").toLowerCase(); } catch (e) {}
      var cs2 = null;
      try { cs2 = getComputedStyle(el); } catch (e2) {}
      if (isClickable(el, tag, type, cs2)) { return el; }
      el = el.parentElement;
      n++;
    }
    return null;
  }

  // =============================================================================================
  //  Capture dispatch (debounced) + settle reporting
  // =============================================================================================
  function adopt(built) {
    currentNodeMap = built.map;
    currentElementToIdx = built.elementToIdx;
    lastSentUrl = built.screen.url;
    lastSentSignature = built.screen.signature;
  }

  function captureNow() {
    try {
      var built = buildScreen();
      adopt(built);
      lastCaptureAt = Date.now();
      if (captureTimer) { clearTimeout(captureTimer); captureTimer = null; }
      safeSend({ type: "cs.capture", snapshot: built.screen });
      if (showActionables && inspecting) { drawActionables(built.screen.root); }
    } catch (e) { log("error", "captureNow: " + (e && e.message)); }
  }

  function consumeVia(urlChanged) {
    var via;
    if (pendingVia && (Date.now() - pendingViaAt) < 3000) { via = pendingVia; }
    else { via = urlChanged ? "load" : "replace"; }
    pendingVia = null;
    return via;
  }

  function maybeReportSettle() {
    try {
      if (!recording && !inspecting) { return; }
      var built = buildScreen();
      var screen = built.screen;
      var urlChanged = screen.url !== lastSentUrl;
      var sigChanged = screen.signature !== lastSentSignature;

      if (recording && (urlChanged || sigChanged)) {
        var via = consumeVia(urlChanged);
        var msg = { type: "cs.route", url: screen.url, title: screen.title, via: via };
        if (pendingTrigger && (Date.now() - pendingTrigger.at) < 2500) {
          msg.element = pendingTrigger.info;
          pendingTrigger = null;
        }
        safeSend(msg);
      }

      // Capture snapshot, debounced to >= 600ms apart.
      var now = Date.now();
      if (now - lastCaptureAt >= 600) {
        lastCaptureAt = now;
        adopt(built);
        safeSend({ type: "cs.capture", snapshot: screen });
        if (showActionables && inspecting) { drawActionables(screen.root); }
      } else {
        if (captureTimer) { clearTimeout(captureTimer); }
        captureTimer = setTimeout(function () {
          captureTimer = null;
          try {
            var b2 = buildScreen();
            lastCaptureAt = Date.now();
            adopt(b2);
            safeSend({ type: "cs.capture", snapshot: b2.screen });
            if (showActionables && inspecting) { drawActionables(b2.screen.root); }
          } catch (e) {}
        }, 600 - (now - lastCaptureAt));
      }

      lastSentUrl = screen.url;
      lastSentSignature = screen.signature;
    } catch (e) { log("error", "settle: " + (e && e.message)); }
  }

  function reportSettleNow() {
    if (settleTimer) { clearTimeout(settleTimer); settleTimer = null; }
    maybeReportSettle();
  }

  function scheduleSettle() {
    if (!recording && !inspecting) { return; }
    if (settleTimer) { clearTimeout(settleTimer); }
    settleTimer = setTimeout(function () { settleTimer = null; maybeReportSettle(); }, 450);
  }

  // =============================================================================================
  //  Route detection (always armed once injected; only SENT while recording)
  // =============================================================================================
  function onRouteChange(via) {
    pendingVia = via;
    pendingViaAt = Date.now();
    lastKnownHref = location.href;
    scheduleSettle();
  }

  // Patch history in whatever world we run in. In the isolated world this only catches our own
  // calls (harmless); when injected with world:"MAIN" it catches the page's SPA navigations too.
  try {
    if (!history.__uiMapperPatched) {
      var _push = history.pushState;
      var _replace = history.replaceState;
      if (typeof _push === "function") {
        history.pushState = function () {
          var r;
          try { r = _push.apply(this, arguments); }
          finally { try { onRouteChange("push"); } catch (e) {} }
          return r;
        };
      }
      if (typeof _replace === "function") {
        history.replaceState = function () {
          var r;
          try { r = _replace.apply(this, arguments); }
          finally { try { onRouteChange("replace"); } catch (e) {} }
          return r;
        };
      }
      try { history.__uiMapperPatched = true; } catch (e) {}
    }
  } catch (e) {}

  try { window.addEventListener("popstate", function () { onRouteChange("pop"); }, true); } catch (e) {}
  try { window.addEventListener("hashchange", function () { onRouteChange("hash"); }, true); } catch (e) {}
  try {
    window.addEventListener("load", function () { if (recording || inspecting) { scheduleSettle(); } }, true);
  } catch (e) {}

  // Cross-world fallback: poll location so isolated-world injection still detects SPA push/replace.
  function ensureLocationWatch() {
    if (locTimer) { return; }
    locTimer = setInterval(function () {
      try {
        if (!recording && !inspecting) { return; }
        if (location.href !== lastKnownHref) {
          var hashOnly = location.href.split("#")[0] === lastKnownHref.split("#")[0];
          onRouteChange(hashOnly ? "hash" : "push");
        }
      } catch (e) {}
    }, 400);
  }

  function ensureObserver() {
    if (mo) { return; }
    try {
      mo = new MutationObserver(function (muts) {
        try {
          if (!recording && !inspecting) { return; }
          var meaningful = false;
          for (var i = 0; i < muts.length; i++) {
            var m = muts[i];
            if (m.type === "childList" && (m.addedNodes.length || m.removedNodes.length)) { meaningful = true; break; }
          }
          if (meaningful) { scheduleSettle(); }
        } catch (e) {}
      });
      mo.observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) {}
  }

  function maybeStopObserver() {
    if (!recording && !inspecting && mo) {
      try { mo.disconnect(); } catch (e) {}
      mo = null;
    }
  }

  // Always-armed passive click listener: remembers the actionable element that triggers a route.
  // Does NOT preventDefault, so it never blocks the page. Only does work while recording.
  function onDocClickCapture(e) {
    try {
      if (inspecting) { return; }        // inspect click handler owns clicks while inspecting
      if (!recording) { return; }        // triggers only matter for recorded routes
      var path = e.composedPath ? e.composedPath() : [e.target];
      if (overlayHost && path.indexOf(overlayHost) !== -1) { return; }
      var act = findActionable(e.target);
      if (act) {
        pendingTrigger = { info: elementInfo(act), at: Date.now() };
        safeSend({ type: "cs.click", element: pendingTrigger.info });
      }
    } catch (err) {}
  }

  // =============================================================================================
  //  Recording / inspect / edit lifecycle
  // =============================================================================================
  function startRecord(sid) {
    recording = true;
    sessionId = sid || null;
    lastSentUrl = "";
    lastSentSignature = "";
    ensureObserver();
    ensureLocationWatch();
    pendingVia = "load";
    pendingViaAt = Date.now();
    log("record", "start");
    reportSettleNow(); // capture the starting screen immediately
    updateToolbar();
  }

  function stopRecord() {
    recording = false;
    log("record", "stop");
    maybeStopObserver();
    updateToolbar();
  }

  function startInspect() {
    inspecting = true;
    ensureOverlay();
    showToolbar();
    attachInspectListeners();
    ensureObserver();
    ensureLocationWatch();
    captureNow(); // fresh map so picks/tree line up immediately
    log("inspect", "start");
    updateToolbar();
  }

  function stopInspect() {
    inspecting = false;
    detachInspectListeners();
    removeOverlay();
    selectedEl = null;
    lastHover = null;
    lastPickPoint = null;
    maybeStopObserver();
    log("inspect", "stop");
  }

  function setEditEnabled(on) {
    editing = !!on;
    log("edit", editing ? "enabled" : "disabled");
  }

  // =============================================================================================
  //  Edit (only on explicit bg.setText, only while editing is enabled)
  // =============================================================================================
  function locate(selector, xpath) {
    var el = null;
    if (selector) { try { el = document.querySelector(selector); } catch (e) {} }
    if (!el && xpath) {
      try {
        var r = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);
        el = r.singleNodeValue;
      } catch (e2) {}
    }
    return el;
  }

  function setNativeValue(el, value) {
    try {
      var proto = el.tagName.toLowerCase() === "textarea"
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var desc = Object.getOwnPropertyDescriptor(proto, "value");
      if (desc && desc.set) { desc.set.call(el, value); }
      else { el.value = value; }
    } catch (e) {
      try { el.value = value; } catch (e2) {}
    }
  }

  function doSetText(selector, xpath, text) {
    try {
      if (!editing) { log("edit", "ignored (disabled)"); return; }
      var el = locate(selector, xpath);
      if (!el) { log("edit", "target not found"); return; }
      var tag = el.tagName.toLowerCase();
      var t = (typeof text === "string") ? text : "";
      if (tag === "input" || tag === "textarea") {
        setNativeValue(el, t);
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
      } else if (el.isContentEditable) {
        el.textContent = t;
        el.dispatchEvent(new Event("input", { bubbles: true }));
      } else {
        log("edit", "target not editable");
        return;
      }
      // Privacy: we never log or store the text value itself, only that an edit occurred.
      log("edit", "set text on " + tag);
    } catch (e) { log("error", "setText: " + (e && e.message)); }
  }

  // =============================================================================================
  //  Node targeting from the panel/background (bg.selectNode / bg.scrollTo)
  // =============================================================================================
  function scrollIntoViewSafe(el) {
    try { el.scrollIntoView({ block: "center", inline: "center", behavior: "smooth" }); }
    catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
  }

  function onSelectNode(idx) {
    try {
      var el = currentNodeMap[idx];
      if (!el) { return; }
      selectedEl = el;
      scrollIntoViewSafe(el);
      if (inspecting) {
        ensureOverlay();
        positionBoxEl(selBox, el);
        selBox.style.display = "block";
        setChip(selChip, chipLabel(el), el, false);
        updateBreadcrumb(el);
      } else {
        flashElement(el);
      }
    } catch (e) {}
  }

  function onScrollTo(idx) {
    try {
      var el = currentNodeMap[idx];
      if (!el) { return; }
      scrollIntoViewSafe(el);
      if (inspecting) {
        ensureOverlay();
        selectedEl = el;
        positionBoxEl(selBox, el);
        selBox.style.display = "block";
        setChip(selChip, chipLabel(el), el, false);
      } else {
        flashElement(el);
      }
    } catch (e) {}
  }

  // =============================================================================================
  //  Overlay (Shadow DOM) - visual only; all events handled via document-level capture listeners
  // =============================================================================================
  var OVERLAY_CSS =
    ":host{all:initial;}" +
    ".um-box{position:fixed;pointer-events:none;box-sizing:border-box;z-index:1;border-radius:2px;}" +
    ".um-hover{border:2px solid #16a34a;background:rgba(22,163,74,0.12);}" +
    ".um-sel{border:2px solid #ea580c;background:rgba(234,88,12,0.10);}" +
    ".um-chip{position:fixed;pointer-events:none;z-index:2;font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;" +
      "color:#f8fafc;background:#0b1220;padding:2px 6px;border-radius:4px;max-width:60vw;white-space:nowrap;" +
      "overflow:hidden;text-overflow:ellipsis;box-shadow:0 1px 4px rgba(0,0,0,0.45);}" +
    ".um-chip-hover{border:1px solid #16a34a;}" +
    ".um-chip-sel{border:1px solid #ea580c;}" +
    ".um-actions{position:fixed;left:0;top:0;pointer-events:none;z-index:0;}" +
    ".um-act{position:fixed;pointer-events:none;border:1px dashed rgba(59,130,246,0.55);border-radius:2px;box-sizing:border-box;}" +
    ".um-toolbar{position:fixed;right:16px;bottom:16px;pointer-events:auto;z-index:3;background:#0b1220;color:#e5e7eb;" +
      "border:1px solid #1f2937;border-radius:10px;box-shadow:0 6px 24px rgba(0,0,0,0.5);width:300px;max-width:92vw;" +
      "overflow:hidden;font:13px/1.4 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;}" +
    ".um-tb-head{display:flex;align-items:center;gap:8px;padding:8px 10px;background:#111827;cursor:move;user-select:none;}" +
    ".um-tb-title{font-weight:600;font-size:12px;letter-spacing:.02em;color:#93c5fd;flex:1;}" +
    ".um-tb-handle{color:#6b7280;cursor:move;padding:0 2px;font-size:14px;}" +
    ".um-tb-crumb{padding:8px 10px;font:11px/1.4 ui-monospace,Menlo,Consolas,monospace;color:#9ca3af;" +
      "border-bottom:1px solid #1f2937;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}" +
    ".um-tb-btns{display:flex;flex-wrap:wrap;gap:6px;padding:10px;}" +
    ".um-btn{pointer-events:auto;flex:1 1 auto;min-width:64px;cursor:pointer;border:1px solid #374151;background:#1f2937;" +
      "color:#e5e7eb;padding:6px 10px;border-radius:6px;font:12px system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;text-align:center;}" +
    ".um-btn:hover{background:#374151;}" +
    ".um-btn-primary{background:#2563eb;border-color:#2563eb;color:#fff;}" +
    ".um-btn-primary:hover{background:#1d4ed8;}" +
    ".um-btn-danger{background:#b91c1c;border-color:#b91c1c;color:#fff;}" +
    ".um-btn-danger:hover{background:#991b1b;}" +
    ".um-btn.on{outline:2px solid #16a34a;}" +
    ".um-dot{width:8px;height:8px;border-radius:50%;background:#6b7280;flex:0 0 auto;}" +
    ".um-dot.on{background:#ef4444;box-shadow:0 0 0 3px rgba(239,68,68,0.25);}";

  function mkDiv(cls) {
    var d = document.createElement("div");
    d.className = cls;
    return d;
  }

  function ensureOverlay() {
    try {
      if (overlayHost && document.documentElement && document.documentElement.contains(overlayHost)) { return; }
      overlayHost = document.createElement("div");
      overlayHost.setAttribute("data-ui-mapper-overlay", "");
      overlayHost.style.cssText =
        "position:fixed;left:0;top:0;width:0;height:0;margin:0;padding:0;border:0;z-index:2147483647;pointer-events:none;";
      shadow = overlayHost.attachShadow({ mode: "open" });

      var style = document.createElement("style");
      style.textContent = OVERLAY_CSS;
      shadow.appendChild(style);

      actionsLayer = mkDiv("um-actions");
      hoverBox = mkDiv("um-box um-hover");
      selBox = mkDiv("um-box um-sel");
      hoverChip = mkDiv("um-chip um-chip-hover");
      selChip = mkDiv("um-chip um-chip-sel");
      hoverBox.style.display = "none";
      selBox.style.display = "none";
      hoverChip.style.display = "none";
      selChip.style.display = "none";

      shadow.appendChild(actionsLayer);
      shadow.appendChild(hoverBox);
      shadow.appendChild(selBox);
      shadow.appendChild(hoverChip);
      shadow.appendChild(selChip);

      buildToolbar();

      (document.body || document.documentElement).appendChild(overlayHost);
    } catch (e) { log("error", "overlay: " + (e && e.message)); }
  }

  function removeOverlay() {
    try {
      showActionables = false;
      clearActionables();
      if (overlayHost && overlayHost.parentNode) { overlayHost.parentNode.removeChild(overlayHost); }
    } catch (e) {}
    overlayHost = null; shadow = null;
    hoverBox = null; selBox = null; hoverChip = null; selChip = null;
    actionsLayer = null; toolbar = null; breadcrumbEl = null; recBtn = null;
  }

  function tbButton(label, cls, onClick) {
    var b = document.createElement("button");
    b.className = cls;
    b.type = "button";
    b.textContent = label; // textContent - static labels, but keeps us safe by construction
    b.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      try { onClick(); } catch (err) {}
    }, true);
    return b;
  }

  function buildToolbar() {
    toolbar = mkDiv("um-toolbar");

    var head = mkDiv("um-tb-head");
    var dot = mkDiv("um-dot");
    dot.id = "um-dot";
    var title = mkDiv("um-tb-title");
    title.textContent = "UI Mapper";
    var handle = mkDiv("um-tb-handle");
    handle.textContent = "⣿"; // braille dots, a neutral drag grip
    head.appendChild(dot);
    head.appendChild(title);
    head.appendChild(handle);

    breadcrumbEl = mkDiv("um-tb-crumb");
    breadcrumbEl.textContent = "Arahkan kursor ke elemen…";

    var btns = mkDiv("um-tb-btns");
    var bRefresh = tbButton("Segarkan", "um-btn", function () {
      // Relay intent to the background AND refresh our own overlay/map for responsiveness.
      safeSend({ type: "sp.captureNow" });
      captureNow();
      flashToolbar();
    });
    recBtn = tbButton(recording ? "Stop" : "Rekam",
      recording ? "um-btn um-btn-danger" : "um-btn um-btn-primary",
      function () {
        // Relay only - the actual recording state flips when background echoes bg.startRecord/stop.
        if (recording) { safeSend({ type: "sp.stopRecord" }); }
        else { safeSend({ type: "sp.startRecord" }); }
      });
    var bActions = tbButton("Aksi", "um-btn", function () { toggleActionables(); });
    var bDone = tbButton("Selesai", "um-btn", function () {
      safeSend({ type: "sp.toggleInspect", on: false });
      stopInspect();
    });

    btns.appendChild(bRefresh);
    btns.appendChild(recBtn);
    btns.appendChild(bActions);
    btns.appendChild(bDone);

    toolbar.appendChild(head);
    toolbar.appendChild(breadcrumbEl);
    toolbar.appendChild(btns);
    shadow.appendChild(toolbar);

    makeDraggable(head);
  }

  function showToolbar() {
    if (toolbar) { toolbar.style.display = "block"; }
    updateToolbar();
  }

  function updateToolbar() {
    try {
      if (recBtn) {
        recBtn.textContent = recording ? "Stop" : "Rekam";
        recBtn.className = recording ? "um-btn um-btn-danger" : "um-btn um-btn-primary";
      }
      if (shadow && shadow.querySelector) {
        var dot = shadow.querySelector("#um-dot");
        if (dot) { dot.className = "um-dot" + (recording ? " on" : ""); }
      }
    } catch (e) {}
  }

  function flashToolbar() {
    try {
      if (!toolbar) { return; }
      toolbar.style.outline = "2px solid #16a34a";
      setTimeout(function () { try { toolbar.style.outline = ""; } catch (e) {} }, 250);
    } catch (e) {}
  }

  function makeDraggable(handle) {
    var sx = 0, sy = 0, ox = 0, oy = 0;

    function mm(e) {
      if (!draggingToolbar) { return; }
      var nx = ox + (e.clientX - sx);
      var ny = oy + (e.clientY - sy);
      nx = Math.max(0, Math.min(nx, (window.innerWidth || 0) - 40));
      ny = Math.max(0, Math.min(ny, (window.innerHeight || 0) - 20));
      toolbar.style.left = nx + "px";
      toolbar.style.top = ny + "px";
      e.preventDefault();
      e.stopPropagation();
    }
    function mu() {
      draggingToolbar = false;
      try { window.removeEventListener("mousemove", mm, true); } catch (e) {}
      try { window.removeEventListener("mouseup", mu, true); } catch (e) {}
    }

    handle.addEventListener("mousedown", function (e) {
      try {
        var r = toolbar.getBoundingClientRect();
        toolbar.style.right = "auto";
        toolbar.style.bottom = "auto";
        toolbar.style.left = r.left + "px";
        toolbar.style.top = r.top + "px";
        sx = e.clientX; sy = e.clientY; ox = r.left; oy = r.top;
        draggingToolbar = true;
        e.preventDefault();
        e.stopPropagation();
        window.addEventListener("mousemove", mm, true);
        window.addEventListener("mouseup", mu, true);
      } catch (err) {}
    }, true);
  }

  // ---- box / chip / breadcrumb drawing --------------------------------------------------------
  function positionBox(div, rect) {
    var w = Math.max(0, rect.r - rect.l);
    var h = Math.max(0, rect.b - rect.t);
    div.style.left = rect.l + "px";
    div.style.top = rect.t + "px";
    div.style.width = w + "px";
    div.style.height = h + "px";
  }

  function positionBoxEl(div, el) {
    try {
      var r = el.getBoundingClientRect();
      div.style.left = r.left + "px";
      div.style.top = r.top + "px";
      div.style.width = Math.max(0, r.width) + "px";
      div.style.height = Math.max(0, r.height) + "px";
    } catch (e) {}
  }

  function setChip(chip, text, el, preferAbove) {
    try {
      chip.textContent = text; // textContent - escapes page-derived text
      chip.style.display = "block";
      var r = el.getBoundingClientRect();
      var ch = 22;
      var top = preferAbove ? (r.top - ch - 2) : (r.top + 2);
      if (top < 2) { top = r.bottom + 2; }
      var left = r.left;
      if (left < 2) { left = 2; }
      var maxLeft = (window.innerWidth || 0) - 40;
      if (left > maxLeft) { left = maxLeft; }
      chip.style.left = left + "px";
      chip.style.top = top + "px";
    } catch (e) {}
  }

  function chipLabel(el) {
    try {
      var tag = el.tagName.toLowerCase();
      var s = tag;
      if (el.id) { s += "#" + el.id; }
      else if (el.classList && el.classList.length) { s += "." + el.classList[0]; }
      var role = el.getAttribute ? el.getAttribute("role") : null;
      var name = accessibleName(el, tag);
      var extra = role ? (" [" + role + "]") : "";
      if (name) { extra += " " + name.slice(0, 30); }
      return s + extra;
    } catch (e) { return el && el.tagName ? el.tagName.toLowerCase() : "?"; }
  }

  function pathString(el) {
    var parts = [];
    var c = el, n = 0;
    try {
      while (c && c.nodeType === 1 && n < 6) {
        var s = c.tagName.toLowerCase();
        if (c.id) { s += "#" + c.id; }
        else if (c.classList && c.classList.length) { s += "." + c.classList[0]; }
        parts.unshift(s);
        if (c === document.documentElement) { break; }
        c = c.parentElement;
        n++;
      }
    } catch (e) {}
    return parts.join(" › ");
  }

  function updateBreadcrumb(el) {
    if (breadcrumbEl) {
      try { breadcrumbEl.textContent = pathString(el); } catch (e) {}
    }
  }

  // ---- optional faint actionable outlines -----------------------------------------------------
  function clearActionables() {
    actBoxes = [];
    if (actionsLayer) {
      while (actionsLayer.firstChild) { actionsLayer.removeChild(actionsLayer.firstChild); }
    }
  }

  function drawActionables(root) {
    if (!actionsLayer) { return; }
    clearActionables();
    var n = 0;
    (function walk(node) {
      if (n >= 400) { return; }
      if (node.clickable && node.visible) {
        var el = currentNodeMap[node.idx];
        var d = mkDiv("um-act");
        if (el) { positionBoxEl(d, el); actBoxes.push({ el: el, div: d }); }
        else { positionBox(d, node.rect); }
        actionsLayer.appendChild(d);
        n++;
      }
      if (node.children) {
        for (var i = 0; i < node.children.length && n < 400; i++) { walk(node.children[i]); }
      }
    })(root);
  }

  function repositionActionables() {
    for (var i = 0; i < actBoxes.length; i++) { positionBoxEl(actBoxes[i].div, actBoxes[i].el); }
  }

  function toggleActionables() {
    showActionables = !showActionables;
    if (showActionables) {
      var b = buildScreen();
      adopt(b);
      drawActionables(b.screen.root);
    } else {
      clearActionables();
    }
  }

  // ---- temporary flash (used when not inspecting so no persistent overlay lingers) -------------
  function flashElement(el) {
    try {
      var host = document.createElement("div");
      host.setAttribute("data-ui-mapper-overlay", "");
      host.style.cssText = "position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;pointer-events:none;";
      var sh = host.attachShadow({ mode: "open" });
      var st = document.createElement("style");
      st.textContent = ".f{position:fixed;border:2px solid #ea580c;background:rgba(234,88,12,0.12);border-radius:2px;transition:opacity .3s;opacity:1;box-sizing:border-box;}";
      sh.appendChild(st);
      var box = document.createElement("div");
      box.className = "f";
      var r = el.getBoundingClientRect();
      box.style.left = r.left + "px";
      box.style.top = r.top + "px";
      box.style.width = Math.max(0, r.width) + "px";
      box.style.height = Math.max(0, r.height) + "px";
      sh.appendChild(box);
      (document.body || document.documentElement).appendChild(host);
      setTimeout(function () { try { box.style.opacity = "0"; } catch (e) {} }, 900);
      setTimeout(function () { try { host.remove(); } catch (e) {} }, 1300);
    } catch (e) {}
  }

  // =============================================================================================
  //  Inspect event handlers (attached only while inspecting)
  // =============================================================================================
  function scheduleReposition() {
    if (repoRAF) { return; }
    repoRAF = requestAnimationFrame(function () {
      repoRAF = 0;
      if (selectedEl && selBox && selBox.style.display !== "none") { positionBoxEl(selBox, selectedEl); }
      if (showActionables) { repositionActionables(); }
    });
  }

  function onInspectMove(e) {
    try {
      if (!inspecting || draggingToolbar) { return; }
      if (e.target === overlayHost) { return; } // over our own toolbar (retargeted to the host)
      var el = document.elementFromPoint(e.clientX, e.clientY);
      if (!el || el === overlayHost || shouldSkip(el)) { return; }
      if (el === lastHover) { return; }
      lastHover = el;
      positionBoxEl(hoverBox, el);
      hoverBox.style.display = "block";
      setChip(hoverChip, chipLabel(el), el, true);
      updateBreadcrumb(el);
      var idx = currentElementToIdx.get(el);
      if (idx != null) { safeSend({ type: "cs.hover", idx: idx }); }
    } catch (err) {}
  }

  // Fully blocks a page interaction event during inspect (but never our own overlay UI).
  function blockEvent(e) {
    try {
      if (!inspecting) { return; }
      var path = e.composedPath ? e.composedPath() : [e.target];
      if (overlayHost && path.indexOf(overlayHost) !== -1) { return; }
      e.preventDefault();
      e.stopPropagation();
      if (e.stopImmediatePropagation) { e.stopImmediatePropagation(); }
    } catch (err) {}
  }

  function onInspectClick(e) {
    try {
      if (!inspecting) { return; }
      var path = e.composedPath ? e.composedPath() : [e.target];
      if (overlayHost && path.indexOf(overlayHost) !== -1) { return; } // our own UI - let it through
      e.preventDefault();
      e.stopPropagation();
      if (e.stopImmediatePropagation) { e.stopImmediatePropagation(); }

      var x = e.clientX, y = e.clientY;
      var hit = document.elementFromPoint(x, y);
      if (hit === overlayHost) { hit = null; }

      var samePlace = lastPickPoint && Math.abs(x - lastPickPoint.x) < 6 && Math.abs(y - lastPickPoint.y) < 6;
      var target;
      if (samePlace && selectedEl && document.documentElement.contains(selectedEl)) {
        // Repeated click on (roughly) the same spot cycles outward to the enclosing element.
        var p = selectedEl.parentElement;
        while (p && shouldSkip(p)) { p = p.parentElement; }
        target = (p && p.nodeType === 1) ? p : selectedEl;
      } else {
        target = hit;
      }
      lastPickPoint = { x: x, y: y };
      if (!target || shouldSkip(target)) { return; }
      pickElement(target);
    } catch (err) { log("error", "click: " + (err && err.message)); }
  }

  function pickElement(el) {
    try {
      // Refresh capture so the picked idx aligns with the tree the panel receives.
      var built = buildScreen();
      adopt(built);
      lastCaptureAt = Date.now();
      safeSend({ type: "cs.capture", snapshot: built.screen });

      var idx = currentElementToIdx.get(el);
      var node = makeNode(el, (idx == null ? -1 : idx), computeDepth(el));

      selectedEl = el;
      positionBoxEl(selBox, el);
      selBox.style.display = "block";
      setChip(selChip, chipLabel(el), el, false);
      updateBreadcrumb(el);
      if (showActionables) { drawActionables(built.screen.root); }

      safeSend({ type: "cs.picked", node: node });
      log("pick", node.tag + (node.id ? ("#" + node.id) : ""));
    } catch (e) { log("error", "pick: " + (e && e.message)); }
  }

  function attachInspectListeners() {
    inspectMove = onInspectMove;
    inspectClick = onInspectClick;
    inspectScroll = function () { scheduleReposition(); };
    inspectKey = function (e) {
      if (e.key === "Escape") {
        safeSend({ type: "sp.toggleInspect", on: false });
        stopInspect();
      }
    };
    try {
      document.addEventListener("mousemove", inspectMove, true);
      document.addEventListener("click", inspectClick, true);
      for (var i = 0; i < BLOCK_EVENTS.length; i++) { document.addEventListener(BLOCK_EVENTS[i], blockEvent, true); }
      window.addEventListener("scroll", inspectScroll, true);
      window.addEventListener("resize", inspectScroll, true);
      document.addEventListener("keydown", inspectKey, true);
    } catch (e) {}
  }

  function detachInspectListeners() {
    try {
      if (inspectMove) { document.removeEventListener("mousemove", inspectMove, true); }
      if (inspectClick) { document.removeEventListener("click", inspectClick, true); }
      for (var i = 0; i < BLOCK_EVENTS.length; i++) { document.removeEventListener(BLOCK_EVENTS[i], blockEvent, true); }
      if (inspectScroll) {
        window.removeEventListener("scroll", inspectScroll, true);
        window.removeEventListener("resize", inspectScroll, true);
      }
      if (inspectKey) { document.removeEventListener("keydown", inspectKey, true); }
    } catch (e) {}
    inspectMove = null; inspectClick = null; inspectScroll = null; inspectKey = null;
  }

  // =============================================================================================
  //  Message router (background -> content, via chrome.tabs.sendMessage)
  // =============================================================================================
  try {
    chrome.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
      try {
        if (!msg || !msg.type) { return false; }
        switch (msg.type) {
          case "bg.startInspect": startInspect(); break;
          case "bg.stopInspect": stopInspect(); break;
          case "bg.startRecord": startRecord(msg.sessionId); break;
          case "bg.stopRecord": stopRecord(); break;
          case "bg.captureNow": captureNow(); break;
          case "bg.setEdit": setEditEnabled(!!msg.enabled); break;
          case "bg.selectNode": onSelectNode(msg.idx); break;
          case "bg.setText": doSetText(msg.selector, msg.xpath, msg.text); break;
          case "bg.scrollTo": onScrollTo(msg.idx); break;
          default: break;
        }
        if (typeof sendResponse === "function") { sendResponse({ ok: true }); }
      } catch (e) {
        try { if (typeof sendResponse === "function") { sendResponse({ ok: false, error: String((e && e.message) || e) }); } } catch (e2) {}
      }
      return false; // all handlers are synchronous
    });
  } catch (e) {}

  // Always-armed passive click listener for route-trigger attribution.
  try { document.addEventListener("click", onDocClickCapture, true); } catch (e) {}

  // =============================================================================================
  //  Boot
  // =============================================================================================
  function init() {
    try { safeSend({ type: "cs.ready", url: location.href }); } catch (e) {}
    ensureLocationWatch();
    log("inject", "ready");
  }
  init();
})();
