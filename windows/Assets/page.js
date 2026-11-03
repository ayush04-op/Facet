/* Facet's page helper (from the Android app's built-in extension): reader view, research highlights and page text.
   Facet injects it and calls it; it never talks to anything outside this page. */
(function () {
  "use strict";
  if (window.__facet) return;

  // ------------------------------------------------------------ page text

  function bestRoot() {
    var cands = document.querySelectorAll("article, main, [role=main], #content, .post, .article, .entry-content, .post-content");
    var best = null, bestScore = 0;
    cands.forEach(function (el) {
      var score = 0;
      el.querySelectorAll("p").forEach(function (p) { score += p.innerText.length; });
      if (score > bestScore) { bestScore = score; best = el; }
    });
    if (best && bestScore > 400) return best;
    // fall back to the block with the most paragraph text
    document.querySelectorAll("div, section").forEach(function (el) {
      var score = 0;
      for (var i = 0; i < el.children.length; i++) {
        if (el.children[i].tagName === "P") score += el.children[i].innerText.length;
      }
      if (score > bestScore) { bestScore = score; best = el; }
    });
    return best || document.body;
  }

  function pageText() {
    var text = (bestRoot().innerText || "").replace(/\n{3,}/g, "\n\n").trim();
    return JSON.stringify({ title: document.title, url: location.href, text: text.slice(0, 24000) });
  }

  // ------------------------------------------------------------ highlights

  var HL_STYLE = "background:rgba(255,206,64,0.55);color:inherit;border-radius:2px;";

  function mark(range) {
    var m = document.createElement("mark");
    m.setAttribute("style", HL_STYLE);
    m.setAttribute("data-facet-highlight", "1");
    try { range.surroundContents(m); return true; } catch (e) { /* spans several elements */ }
    var nodes = [], walker = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_TEXT), n;
    while ((n = walker.nextNode())) { if (range.intersectsNode(n) && n.nodeValue.trim()) nodes.push(n); }
    nodes.forEach(function (node) {
      var r = document.createRange();
      r.selectNodeContents(node);
      if (node === range.startContainer) r.setStart(node, range.startOffset);
      if (node === range.endContainer) r.setEnd(node, range.endOffset);
      var mm = document.createElement("mark");
      mm.setAttribute("style", HL_STYLE);
      mm.setAttribute("data-facet-highlight", "1");
      try { r.surroundContents(mm); } catch (e2) { /* skip */ }
    });
    return nodes.length > 0;
  }

  /** Marks the selection and returns its text ("" when nothing is selected). */
  function highlight() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return "";
    var text = sel.toString().trim();
    if (!text) return "";
    mark(sel.getRangeAt(0));
    sel.removeAllRanges();
    return text.slice(0, 1200);
  }

  function applyHighlights(list) {
    (list || []).forEach(function (text) {
      var probe = (text || "").split("\n")[0].trim();
      if (!probe) return;
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), n;
      while ((n = walker.nextNode())) {
        if (n.parentNode && n.parentNode.getAttribute && n.parentNode.getAttribute("data-facet-highlight")) continue;
        var i = n.nodeValue.indexOf(probe);
        if (i >= 0) {
          var r = document.createRange();
          r.setStart(n, i);
          r.setEnd(n, i + probe.length);
          mark(r);
          break;
        }
      }
    });
  }

  // ------------------------------------------------------------ reader view

  var THEMES = [
    { bg: "#FBF6EA", fg: "#1E1915", muted: "#7A6E62", accent: "#B5532F" },
    { bg: "#15110E", fg: "#F3EBDA", muted: "#A09282", accent: "#D4693F" },
    { bg: "#F1E7D0", fg: "#3B2F22", muted: "#80715E", accent: "#9A4A28" }
  ];
  var WIDTHS = [560, 680, 820];
  var reader = null;

  function cleanClone(root) {
    var clone = root.cloneNode(true);
    clone.querySelectorAll("script,style,noscript,nav,aside,footer,form,iframe,button,svg,[role=navigation],[aria-hidden=true],.ad,.ads,.advert,.share,.social,.comments,.related").forEach(function (e) { e.remove(); });
    clone.querySelectorAll("*").forEach(function (e) {
      var keep = ["href", "src", "alt"];
      Array.prototype.slice.call(e.attributes).forEach(function (a) { if (keep.indexOf(a.name) < 0) e.removeAttribute(a.name); });
      if (e.tagName === "A") e.setAttribute("target", "_blank");
    });
    return clone;
  }

  function readerOn(size, width, theme) {
    var t = THEMES[theme] || THEMES[0], w = WIDTHS[width] || WIDTHS[1];
    var keepScroll = reader ? reader.wrap.scrollTop : 0;
    readerOff();
    var host = document.createElement("div");
    var shadow = host.attachShadow({ mode: "closed" });
    var style = document.createElement("style");
    style.textContent = ":host{all:initial}" +
      ".wrap{position:fixed;inset:0;z-index:2147483647;overflow:auto;background:" + t.bg + ";color:" + t.fg + ";font-family:Georgia,'Times New Roman',serif;scroll-behavior:smooth}" +
      ".wrap::-webkit-scrollbar{width:12px}.wrap::-webkit-scrollbar-thumb{background:" + t.muted + "66;border-radius:8px;border:3px solid transparent;background-clip:content-box}" +
      ".art{max-width:" + w + "px;margin:0 auto;padding:40px 24px 120px;font-size:" + size + "px;line-height:1.65;animation:rise .45s cubic-bezier(.2,.8,.2,1)}" +
      "@keyframes rise{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}" +
      ".art h1{font-size:1.7em;line-height:1.2;margin:.2em 0 .4em}.art h2,.art h3{line-height:1.25}" +
      ".art img{max-width:100%;height:auto;border-radius:8px}.art a{color:" + t.accent + "}" +
      ".art blockquote{border-left:3px solid " + t.accent + ";margin-left:0;padding-left:14px;color:" + t.muted + "}" +
      ".art pre{overflow:auto;background:" + t.muted + "22;padding:12px;border-radius:8px}" +
      ".site{font-family:system-ui,sans-serif;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:" + t.muted + "}";
    var wrap = document.createElement("div");
    wrap.className = "wrap";
    var art = document.createElement("div");
    art.className = "art";
    var site = document.createElement("div");
    site.className = "site";
    site.textContent = location.hostname;
    var h1 = document.createElement("h1");
    var heading = document.querySelector("article h1, h1");
    h1.textContent = heading ? heading.innerText : document.title;
    art.appendChild(site);
    art.appendChild(h1);
    art.appendChild(cleanClone(bestRoot()));
    wrap.appendChild(art);
    shadow.appendChild(style);
    shadow.appendChild(wrap);
    document.documentElement.appendChild(host);
    wrap.scrollTop = keepScroll;
    reader = { host: host, wrap: wrap };
    return true;
  }

  function readerOff() {
    if (reader) { reader.host.remove(); reader = null; }
    return true;
  }

  window.__facet = {
    pageText: pageText,
    highlight: highlight,
    applyHighlights: applyHighlights,
    reader: function (on, size, width, theme) { return on ? readerOn(size, width, theme) : readerOff(); },
    readerShown: function () { return !!reader; }
  };
})();
