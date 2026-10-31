/* Facet built-in extension: reader view, highlights and page text. Talks only to the Facet app on this device. */
(function () {
  "use strict";
  if (window.top !== window) return;

  var port = null;
  try {
    port = browser.runtime.connectNative("facet");
  } catch (e) {
    return;
  }

  function send(msg) {
    try { port.postMessage(msg); } catch (e) { /* app closed the port */ }
  }

  send({ type: "hello", url: location.href, title: document.title });

  // ------------------------------------------------------------ page text

  function pageText() {
    var root = bestRoot();
    var text = (root.innerText || "").replace(/\n{3,}/g, "\n\n").trim();
    return text.slice(0, 24000);
  }

  // ------------------------------------------------------------ highlights

  var HL_STYLE = "background:rgba(255,206,64,0.55);color:inherit;border-radius:2px;";

  function markRange(range) {
    var mark = document.createElement("mark");
    mark.setAttribute("style", HL_STYLE);
    mark.setAttribute("data-facet-highlight", "1");
    try {
      range.surroundContents(mark);
      return true;
    } catch (e) {
      // the selection spans several elements: mark each text piece on its own
      var nodes = [];
      var walker = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_TEXT);
      var n;
      while ((n = walker.nextNode())) {
        if (range.intersectsNode(n) && n.nodeValue.trim()) nodes.push(n);
      }
      nodes.forEach(function (node) {
        var r = document.createRange();
        r.selectNodeContents(node);
        if (node === range.startContainer) r.setStart(node, range.startOffset);
        if (node === range.endContainer) r.setEnd(node, range.endOffset);
        var m = document.createElement("mark");
        m.setAttribute("style", HL_STYLE);
        m.setAttribute("data-facet-highlight", "1");
        try { r.surroundContents(m); } catch (e2) { /* skip */ }
      });
      return nodes.length > 0;
    }
  }

  function highlightSelection() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
      send({ type: "toast", text: "Select some text first, then choose Highlight." });
      return;
    }
    var text = sel.toString().trim();
    if (!text) return;
    markRange(sel.getRangeAt(0));
    sel.removeAllRanges();
    send({ type: "highlight", url: location.href, title: document.title, text: text.slice(0, 1200) });
  }

  function applyHighlights(list) {
    list.forEach(function (text) {
      if (!text) return;
      var probe = text.split("\n")[0].trim();
      if (!probe) return;
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      var n;
      while ((n = walker.nextNode())) {
        if (n.parentNode && n.parentNode.getAttribute && n.parentNode.getAttribute("data-facet-highlight")) continue;
        var i = n.nodeValue.indexOf(probe);
        if (i >= 0) {
          var r = document.createRange();
          r.setStart(n, i);
          r.setEnd(n, i + probe.length);
          markRange(r);
          break;
        }
      }
    });
  }

  // ------------------------------------------------------------ reader view

  var reader = null;
  var settings = { size: 19, width: 1, theme: 0 };

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
    var blocks = document.querySelectorAll("div, section");
    blocks.forEach(function (el) {
      var score = 0;
      for (var i = 0; i < el.children.length; i++) {
        if (el.children[i].tagName === "P") score += el.children[i].innerText.length;
      }
      if (score > bestScore) { bestScore = score; best = el; }
    });
    return best || document.body;
  }

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

  var THEMES = [
    { bg: "#FBF6EA", fg: "#1E1915", muted: "#7A6E62", accent: "#B5532F" },
    { bg: "#15110E", fg: "#F3EBDA", muted: "#A09282", accent: "#D4693F" },
    { bg: "#F1E7D0", fg: "#3B2F22", muted: "#80715E", accent: "#9A4A28" }
  ];
  var WIDTHS = [560, 680, 820];

  function css() {
    var t = THEMES[settings.theme] || THEMES[0];
    var w = WIDTHS[settings.width] || WIDTHS[1];
    return ":host{all:initial}" +
      ".wrap{position:fixed;inset:0;z-index:2147483647;overflow:auto;background:" + t.bg + ";color:" + t.fg + ";font-family:Georgia,'Times New Roman',serif;-webkit-text-size-adjust:100%}" +
      ".bar{position:sticky;top:0;display:flex;gap:6px;align-items:center;justify-content:center;padding:10px;background:" + t.bg + ";border-bottom:1px solid " + t.muted + "33;font-family:system-ui,sans-serif}" +
      ".bar button{border:1px solid " + t.muted + "66;background:transparent;color:" + t.fg + ";border-radius:18px;padding:7px 13px;font-size:14px}" +
      ".bar .on{background:" + t.accent + ";border-color:" + t.accent + ";color:" + t.bg + "}" +
      ".art{max-width:" + w + "px;margin:0 auto;padding:22px 20px 80px;font-size:" + settings.size + "px;line-height:1.65}" +
      ".art h1{font-size:1.7em;line-height:1.2;margin:.2em 0 .4em}.art h2,.art h3{line-height:1.25}" +
      ".art img{max-width:100%;height:auto;border-radius:8px}.art a{color:" + t.accent + "}" +
      ".art blockquote{border-left:3px solid " + t.accent + ";margin-left:0;padding-left:14px;color:" + t.muted + "}" +
      ".art pre{overflow:auto;background:" + t.muted + "22;padding:12px;border-radius:8px}" +
      ".site{font-family:system-ui,sans-serif;font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:" + t.muted + "}";
  }

  function buildReader() {
    if (reader) { reader.host.remove(); reader = null; }
    var host = document.createElement("div");
    var shadow = host.attachShadow({ mode: "closed" });
    var style = document.createElement("style");
    style.textContent = css();
    var wrap = document.createElement("div");
    wrap.className = "wrap";

    var bar = document.createElement("div");
    bar.className = "bar";
    function btn(label, fn, on) {
      var b = document.createElement("button");
      b.textContent = label;
      if (on) b.className = "on";
      b.addEventListener("click", fn);
      bar.appendChild(b);
    }
    function change(k, v) {
      settings[k] = v;
      send({ type: "readerSettings", size: settings.size, width: settings.width, theme: settings.theme });
      buildReader();
    }
    btn("A−", function () { change("size", Math.max(14, settings.size - 2)); });
    btn("A+", function () { change("size", Math.min(30, settings.size + 2)); });
    btn("Narrow", function () { change("width", 0); }, settings.width === 0);
    btn("Wide", function () { change("width", 2); }, settings.width === 2);
    btn("Paper", function () { change("theme", 0); }, settings.theme === 0);
    btn("Dark", function () { change("theme", 1); }, settings.theme === 1);
    btn("Sepia", function () { change("theme", 2); }, settings.theme === 2);
    btn("Close", function () { closeReader(); });

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

    wrap.appendChild(bar);
    wrap.appendChild(art);
    shadow.appendChild(style);
    shadow.appendChild(wrap);
    document.documentElement.appendChild(host);
    reader = { host: host };
  }

  function closeReader() {
    if (reader) { reader.host.remove(); reader = null; }
    send({ type: "readerClosed" });
  }

  // ------------------------------------------------------------ commands from the app

  port.onMessage.addListener(function (m) {
    if (!m || !m.cmd) return;
    switch (m.cmd) {
      case "highlights": applyHighlights(m.list || []); break;
      case "highlightSelection": highlightSelection(); break;
      case "reader":
        if (m.on) {
          settings.size = m.size || settings.size;
          settings.width = typeof m.width === "number" ? m.width : settings.width;
          settings.theme = typeof m.theme === "number" ? m.theme : settings.theme;
          buildReader();
        } else {
          closeReader();
        }
        break;
      case "extractText":
        send({ type: "text", title: document.title, url: location.href, text: pageText() });
        break;
    }
  });
})();
