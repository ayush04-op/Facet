using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using CefSharp;

namespace Facet
{
    /// <summary>One installed extension, as kept in extensions.json.</summary>
    public class ExtensionInfo
    {
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public string Version { get; set; } = "";
        public string Description { get; set; } = "";
        /// <summary>"WebExtension" or "Userscript".</summary>
        public string Kind { get; set; } = "";
        public bool Enabled { get; set; } = true;
        public string Source { get; set; } = "";
        /// <summary>Parts of the extension Facet can't run (shown on the Extensions page).</summary>
        public string Warning { get; set; } = "";
    }

    /// <summary>
    /// Facet's extension engine. Chromium's own extension system isn't available in the embedded engine, so Facet
    /// runs the part of extensions that most of them are made of: content scripts and styles that change pages.
    /// Two kinds are supported:
    ///  - WebExtensions (Chrome / Firefox format: unpacked folder, .zip, .crx, .xpi, or straight from the Chrome Web
    ///    Store). Their content_scripts and CSS run with a chrome.* / browser.* shim: runtime.getURL, storage, i18n.
    ///    Background pages, service workers and toolbar popups don't run.
    ///  - Userscripts (.user.js, as on Greasy Fork), with the common GM_* functions.
    /// Scripts run in the page itself (there is no separate isolated world), and not in private tabs.
    /// </summary>
    public static class Extensions
    {
        public const string Scheme = "facet-extension";

        public static string Root
        {
            get
            {
                var d = Path.Combine(AppInfo.DataDir, "extensions");
                Directory.CreateDirectory(d);
                return d;
            }
        }

        /// <summary>Paper scrollbars for websites; set by the window for the current theme.</summary>
        public static volatile string ScrollbarScript = "";

        public static List<ExtensionInfo> All { get; private set; } = new List<ExtensionInfo>();

        private class Script
        {
            public Regex[] Include, Exclude;
            public bool AllFrames;
            public string Code;
        }

        // replaced as a whole on every change, so engine threads always see a complete list
        private static volatile List<Script> scripts = new List<Script>();
        private static readonly object gate = new object();

        private static string ListFile { get { return Path.Combine(Root, "extensions.json"); } }

        public static void Load()
        {
            lock (gate)
            {
                try
                {
                    if (File.Exists(ListFile))
                        All = JsonSerializer.Deserialize<List<ExtensionInfo>>(File.ReadAllText(ListFile)) ?? new List<ExtensionInfo>();
                }
                catch { All = new List<ExtensionInfo>(); }
                Compile();
            }
        }

        private static void Save()
        {
            try { File.WriteAllText(ListFile, JsonSerializer.Serialize(All, new JsonSerializerOptions { WriteIndented = true })); } catch { }
        }

        public static void SetEnabled(string id, bool on)
        {
            lock (gate)
            {
                var e = All.Find(x => x.Id == id);
                if (e == null) return;
                e.Enabled = on;
                Save();
                Compile();
            }
        }

        public static void Remove(string id)
        {
            lock (gate)
            {
                All.RemoveAll(x => x.Id == id);
                Save();
                Compile();
                try { Directory.Delete(Path.Combine(Root, id), true); } catch { }
            }
        }

        // ------------------------------------------------------------------ installing

        /// <summary>.crx, .zip, .xpi or .user.js. Throws with a message meant for the person.</summary>
        public static ExtensionInfo InstallFile(string path)
        {
            if (path.EndsWith(".js", StringComparison.OrdinalIgnoreCase))
                return InstallUserScript(File.ReadAllText(path), Path.GetFileName(path));
            return InstallPackage(File.ReadAllBytes(path), Path.GetFileName(path));
        }

        public static ExtensionInfo InstallFolder(string dir)
        {
            return InstallWebExtension(dir, dir);
        }

        /// <summary>A Chrome Web Store link (or a bare 32-letter extension ID).</summary>
        public static async Task<ExtensionInfo> InstallFromWebStoreAsync(string linkOrId)
        {
            var m = Regex.Match(linkOrId ?? "", @"(?<![a-p])([a-p]{32})(?![a-p])");
            if (!m.Success) throw new InvalidOperationException("That isn't a Chrome Web Store link. Open the extension's page in the store and copy its address.");
            string id = m.Groups[1].Value;
            string url = "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=154.0.0.0&acceptformat=crx2,crx3&x=id%3D"
                         + id + "%26installsource%3Dondemand%26uc";
            byte[] data;
            using (var c = new HttpClient())
            {
                c.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/154.0.0.0 Safari/537.36");
                var res = await c.GetAsync(url);
                if (!res.IsSuccessStatusCode || res.Content.Headers.ContentLength == 0)
                    throw new InvalidOperationException("The Chrome Web Store didn't send this extension (it may be removed or region-locked).");
                data = await res.Content.ReadAsByteArrayAsync();
            }
            if (data.Length == 0) throw new InvalidOperationException("The Chrome Web Store sent an empty download.");
            return InstallPackage(data, "Chrome Web Store");
        }

        private static ExtensionInfo InstallPackage(byte[] data, string source)
        {
            string tmp = Path.Combine(Path.GetTempPath(), "facet-ext-" + Guid.NewGuid().ToString("N"));
            try
            {
                using (var ms = new MemoryStream(ZipPart(data)))
                {
                    try { ZipFile.ExtractToDirectory(ms, tmp); }
                    catch (InvalidDataException) { throw new InvalidOperationException("This file isn't an extension package (.crx, .zip or .xpi)."); }
                }
                // packages sometimes wrap everything in one top-level folder
                string dir = tmp;
                if (!File.Exists(Path.Combine(dir, "manifest.json")))
                {
                    var subs = Directory.GetDirectories(dir);
                    if (subs.Length == 1 && File.Exists(Path.Combine(subs[0], "manifest.json"))) dir = subs[0];
                }
                return InstallWebExtension(dir, source);
            }
            finally { try { Directory.Delete(tmp, true); } catch { } }
        }

        /// <summary>The zip inside a .crx (versions 2 and 3); other files are returned as they are.</summary>
        private static byte[] ZipPart(byte[] d)
        {
            if (d.Length < 16 || d[0] != 'C' || d[1] != 'r' || d[2] != '2' || d[3] != '4') return d;
            uint version = BitConverter.ToUInt32(d, 4);
            int start;
            if (version == 3) start = 12 + (int)BitConverter.ToUInt32(d, 8);
            else start = 16 + (int)BitConverter.ToUInt32(d, 8) + (int)BitConverter.ToUInt32(d, 12);
            if (start <= 0 || start >= d.Length) throw new InvalidOperationException("This .crx file is damaged.");
            var z = new byte[d.Length - start];
            Buffer.BlockCopy(d, start, z, 0, z.Length);
            return z;
        }

        private static readonly JsonDocumentOptions Lenient = new JsonDocumentOptions { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true };

        private static ExtensionInfo InstallWebExtension(string dir, string source)
        {
            string manifestPath = Path.Combine(dir, "manifest.json");
            if (!File.Exists(manifestPath)) throw new InvalidOperationException("There's no manifest.json in this extension.");
            using (var doc = JsonDocument.Parse(File.ReadAllText(manifestPath).TrimStart('\uFEFF'), Lenient))
            {
                var m = doc.RootElement;
                var msgs = Messages(dir, Str(m, "default_locale"));
                string name = Localize(Str(m, "name"), msgs);
                if (name.Length == 0) throw new InvalidOperationException("This extension has no name in its manifest.");
                bool hasScripts = m.TryGetProperty("content_scripts", out var cs) && cs.ValueKind == JsonValueKind.Array && cs.GetArrayLength() > 0;
                if (!hasScripts)
                    throw new InvalidOperationException("\"" + name + "\" works through a background page or toolbar popup, which Facet can't run yet. "
                        + "Extensions that change pages (content scripts and styles) are supported.");
                var missing = new List<string>();
                if (m.TryGetProperty("background", out _)) missing.Add("background tasks");
                if (m.TryGetProperty("action", out _) || m.TryGetProperty("browser_action", out _) || m.TryGetProperty("page_action", out _)) missing.Add("its toolbar button");
                if (m.TryGetProperty("options_page", out _) || m.TryGetProperty("options_ui", out _)) missing.Add("its options page");

                var info = new ExtensionInfo
                {
                    Id = "ext-" + Slug(name),
                    Name = name,
                    Version = Str(m, "version"),
                    Description = Localize(Str(m, "description"), msgs),
                    Kind = "WebExtension",
                    Source = source,
                    Warning = missing.Count > 0 ? "Pages it changes work; " + string.Join(", ", missing) + " won't run in Facet." : ""
                };
                lock (gate)
                {
                    string dst = Path.Combine(Root, info.Id);
                    if (!string.Equals(Path.GetFullPath(dir).TrimEnd('\\'), Path.GetFullPath(dst).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                    {
                        if (Directory.Exists(dst)) Directory.Delete(dst, true);
                        CopyDir(dir, dst);
                    }
                    Put(info);
                }
                return info;
            }
        }

        /// <summary>A .user.js script. Its @require libraries are fetched once, at install time.</summary>
        public static ExtensionInfo InstallUserScript(string text, string source)
        {
            var meta = UserScriptMeta(text);
            string name = First(meta, "name");
            if (!text.Contains("==UserScript==") || name.Length == 0) throw new InvalidOperationException("This file isn't a userscript (it has no ==UserScript== header with a @name).");
            var requires = new StringBuilder();
            foreach (var r in Each(meta, "require"))
            {
                if (!r.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) continue;
                try { using (var c = new HttpClient()) requires.Append(c.GetStringAsync(r).GetAwaiter().GetResult()).Append("\n;\n"); }
                catch { throw new InvalidOperationException("Couldn't download a library this script needs: " + r); }
            }
            var info = new ExtensionInfo
            {
                Id = "us-" + Slug(name),
                Name = name,
                Version = First(meta, "version"),
                Description = First(meta, "description"),
                Kind = "Userscript",
                Source = source,
                Warning = Each(meta, "resource").Count > 0 ? "@resource files aren't supported; the script may miss images or styles." : ""
            };
            lock (gate)
            {
                string dst = Path.Combine(Root, info.Id);
                if (Directory.Exists(dst)) Directory.Delete(dst, true);
                Directory.CreateDirectory(dst);
                File.WriteAllText(Path.Combine(dst, "script.user.js"), text);
                File.WriteAllText(Path.Combine(dst, "require.js"), requires.ToString());
                Put(info);
            }
            return info;
        }

        private static void Put(ExtensionInfo info)
        {
            var old = All.Find(x => x.Id == info.Id);
            if (old != null) { info.Enabled = old.Enabled; All.Remove(old); }
            All.Add(info);
            All.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
            Save();
            Compile();
        }

        // ------------------------------------------------------------------ running

        /// <summary>Runs as each page or frame gets its script context: paper scrollbars, then matching extensions.</summary>
        public static void Inject(IFrame frame, bool privateTab)
        {
            try
            {
                if (frame == null || !frame.IsValid) return;
                string url = frame.Url ?? "";
                if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                    && !url.StartsWith("file://", StringComparison.OrdinalIgnoreCase)) return;
                var sb = new StringBuilder(ScrollbarScript);
                if (!privateTab)
                {
                    bool main = frame.IsMain;
                    foreach (var s in scripts)
                        if ((main || s.AllFrames) && Matches(s, url)) sb.Append('\n').Append(s.Code);
                }
                if (sb.Length > 0) frame.ExecuteJavaScriptAsync(sb.ToString(), url, 1);
            }
            catch { /* the frame went away */ }
        }

        private static bool Matches(Script s, string url)
        {
            bool hit = false;
            foreach (var r in s.Include) if (r.IsMatch(url)) { hit = true; break; }
            if (!hit) return false;
            foreach (var r in s.Exclude) if (r.IsMatch(url)) return false;
            return true;
        }

        private static void Compile()
        {
            var list = new List<Script>();
            foreach (var e in All)
            {
                if (!e.Enabled) continue;
                try
                {
                    string dir = Path.Combine(Root, e.Id);
                    if (e.Kind == "Userscript") CompileUserScript(e, dir, list);
                    else CompileWebExtension(e, dir, list);
                }
                catch { /* a broken extension shouldn't stop the others */ }
            }
            scripts = list;
        }

        private static void CompileWebExtension(ExtensionInfo e, string dir, List<Script> into)
        {
            string manifest = File.ReadAllText(Path.Combine(dir, "manifest.json")).TrimStart('\uFEFF');
            using (var doc = JsonDocument.Parse(manifest, Lenient))
            {
                var m = doc.RootElement;
                var msgs = Messages(dir, Str(m, "default_locale"));
                string manifestJson = JsonSerializer.Serialize(doc.RootElement);
                int n = 0;
                foreach (var cs in m.GetProperty("content_scripts").EnumerateArray())
                {
                    var js = new StringBuilder();
                    foreach (var f in Strings(cs, "js")) js.Append(ReadInside(dir, f)).Append("\n;\n");
                    var css = new StringBuilder();
                    foreach (var f in Strings(cs, "css")) css.Append(ReadInside(dir, f)).Append('\n');
                    var include = Strings(cs, "matches").Select(MatchPattern).Where(r => r != null).ToArray();
                    if (include.Length == 0) continue;
                    var exclude = Strings(cs, "exclude_matches").Select(MatchPattern).Where(r => r != null)
                        .Concat(Strings(cs, "exclude_globs").Select(Glob)).ToArray();
                    string runAt = Str(cs, "run_at");
                    bool allFrames = cs.TryGetProperty("all_frames", out var af) && af.ValueKind == JsonValueKind.True;
                    string api = WebExtensionApi(e.Id, manifestJson, msgs);
                    into.Add(new Script { Include = include, Exclude = exclude, AllFrames = allFrames, Code = Wrap(e, n++, api, js.ToString(), css.ToString(), runAt.Length > 0 ? runAt : "document_idle") });
                }
            }
        }

        private static void CompileUserScript(ExtensionInfo e, string dir, List<Script> into)
        {
            string text = File.ReadAllText(Path.Combine(dir, "script.user.js"));
            string req = File.Exists(Path.Combine(dir, "require.js")) ? File.ReadAllText(Path.Combine(dir, "require.js")) : "";
            var meta = UserScriptMeta(text);
            var include = Each(meta, "match").Select(MatchPattern).Concat(Each(meta, "include").Select(Glob)).Where(r => r != null).ToList();
            if (include.Count == 0) include.Add(MatchPattern("<all_urls>"));
            var exclude = Each(meta, "exclude").Select(Glob).Concat(Each(meta, "exclude-match").Select(MatchPattern)).Where(r => r != null).ToArray();
            string runAt = First(meta, "run-at").Replace('-', '_');
            into.Add(new Script
            {
                Include = include.ToArray(),
                Exclude = exclude,
                AllFrames = !meta.ContainsKey("noframes"),
                Code = Wrap(e, 0, UserScriptApi(e), req + "\n" + text, "", runAt.Length > 0 ? runAt : "document_idle")
            });
        }

        /// <summary>One self-contained, run-once snippet: the API shim, the CSS, and the script started at its run_at time.</summary>
        private static string Wrap(ExtensionInfo e, int n, string api, string js, string css, string runAt)
        {
            string key = JsonSerializer.Serialize(e.Id + "#" + n);
            string label = JsonSerializer.Serialize("[Facet extension: " + e.Name + "]");
            var sb = new StringBuilder();
            sb.Append("(function(){var __k=").Append(key).Append(";var __d=window.__facetExt||(window.__facetExt={});if(__d[__k])return;__d[__k]=1;\n");
            sb.Append(api).Append('\n');
            if (css.Trim().Length > 0)
                sb.Append("(function(c){var a=function(){if(!document.documentElement)return false;var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return true;};")
                  .Append("if(!a())document.addEventListener('readystatechange',a,{once:true});})(").Append(JsonSerializer.Serialize(css)).Append(");\n");
            if (js.Trim().Length > 0)
            {
                sb.Append("var __run=function(){try{(function(){\n").Append(js).Append("\n}).call(window);}catch(e){console.error(").Append(label).Append(",e);}};\n");
                if (runAt == "document_start") sb.Append("__run();\n");
                else if (runAt == "document_end") sb.Append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__run,{once:true});else __run();\n");
                else sb.Append("if(document.readyState==='complete')setTimeout(__run,0);else window.addEventListener('load',function(){setTimeout(__run,0);},{once:true});\n");
            }
            sb.Append("})();");
            return sb.ToString();
        }

        private static string WebExtensionApi(string id, string manifestJson, Dictionary<string, string> msgs)
        {
            return
                "var __id=" + JsonSerializer.Serialize(id) + ",__base='" + Scheme + "://ext/'+__id+'/',__msgs=" + JsonSerializer.Serialize(msgs) + ",__manifest=" + manifestJson + ";\n" +
                "var __area=function(a){var p='facet-ext:'+__id+':'+a+':';" +
                "var all=function(){var o={};try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k.indexOf(p)===0)o[k.slice(p.length)]=JSON.parse(localStorage.getItem(k));}}catch(e){}return o;};" +
                "var done=function(cb,v){if(typeof cb==='function'){try{cb(v);}catch(e){}}return Promise.resolve(v);};" +
                "return{get:function(keys,cb){var o=all(),r={};if(keys==null)r=o;else if(typeof keys==='string'){if(keys in o)r[keys]=o[keys];}" +
                "else if(Array.isArray(keys)){keys.forEach(function(k){if(k in o)r[k]=o[k];});}else{for(var k in keys)r[k]=(k in o)?o[k]:keys[k];}return done(cb,r);}," +
                "set:function(items,cb){try{for(var k in items)localStorage.setItem(p+k,JSON.stringify(items[k]));}catch(e){}return done(cb);}," +
                "remove:function(keys,cb){try{[].concat(keys).forEach(function(k){localStorage.removeItem(p+k);});}catch(e){}return done(cb);}," +
                "clear:function(cb){var o=all();try{for(var k in o)localStorage.removeItem(p+k);}catch(e){}return done(cb);}," +
                "getBytesInUse:function(k,cb){return done(typeof k==='function'?k:cb,0);}};};\n" +
                "var __noop={addListener:function(){},removeListener:function(){},hasListener:function(){return false;}};\n" +
                "var chrome={runtime:{id:__id,getURL:function(p){return __base+String(p).replace(/^\\//,'');},getManifest:function(){return __manifest;}," +
                "sendMessage:function(){var cb=arguments[arguments.length-1];if(typeof cb==='function')cb();return Promise.resolve();},onMessage:__noop,onConnect:__noop," +
                "connect:function(){return{postMessage:function(){},disconnect:function(){},onMessage:__noop,onDisconnect:__noop};},lastError:undefined}," +
                "storage:{local:__area('local'),sync:__area('sync'),session:__area('session'),onChanged:__noop}," +
                "i18n:{getMessage:function(n,s){var m=__msgs[String(n).toLowerCase()]||'';s=[].concat(s==null?[]:s);return m.replace(/\\$(\\d)/g,function(_,i){return s[i-1]==null?'':s[i-1];});}," +
                "getUILanguage:function(){return navigator.language;}}};\nvar browser=chrome;\n" +
                // An extension reading its own files: Chromium refuses CORS-mode fetches to custom schemes from web
                // pages, but serves them in no-cors mode with a readable body, so route those through that.
                "var fetch=function(u,o){var s=(u&&u.url)||String(u);if(s.indexOf(__base)===0){o=Object.assign({},o,{mode:'no-cors'});}return window.fetch(u,o);};";
        }

        private static string UserScriptApi(ExtensionInfo e)
        {
            return
                "var __p='facet-us:'+" + JsonSerializer.Serialize(e.Id) + "+':';\n" +
                "var GM_info={script:{name:" + JsonSerializer.Serialize(e.Name) + ",version:" + JsonSerializer.Serialize(e.Version) + ",namespace:''},scriptHandler:'Facet',version:" + JsonSerializer.Serialize(AppInfo.Version) + "};\n" +
                "var unsafeWindow=window;\n" +
                "function GM_addStyle(c){var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return s;}\n" +
                "function GM_getValue(k,d){try{var v=localStorage.getItem(__p+k);return v===null?d:JSON.parse(v);}catch(e){return d;}}\n" +
                "function GM_setValue(k,v){try{localStorage.setItem(__p+k,JSON.stringify(v));}catch(e){}}\n" +
                "function GM_deleteValue(k){try{localStorage.removeItem(__p+k);}catch(e){}}\n" +
                "function GM_listValues(){var r=[];try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k.indexOf(__p)===0)r.push(k.slice(__p.length));}}catch(e){}return r;}\n" +
                "function GM_openInTab(u){return window.open(u,'_blank');}\n" +
                "function GM_setClipboard(t){try{navigator.clipboard.writeText(String(t));}catch(e){}}\n" +
                "function GM_registerMenuCommand(){return 0;}\nfunction GM_unregisterMenuCommand(){}\nfunction GM_log(){console.log.apply(console,arguments);}\n" +
                "function GM_xmlhttpRequest(o){var c=new AbortController();fetch(o.url,{method:o.method||'GET',headers:o.headers,body:o.data,credentials:'include',signal:c.signal})" +
                ".then(function(r){return r.text().then(function(t){var x={status:r.status,statusText:r.statusText,responseText:t,response:t,finalUrl:r.url,responseHeaders:''};" +
                "if(o.onload)o.onload(x);if(o.onloadend)o.onloadend(x);});}).catch(function(e){if(o.onerror)o.onerror(e);});return{abort:function(){c.abort();}};}\n" +
                "var GM={info:GM_info,addStyle:function(c){return Promise.resolve(GM_addStyle(c));},getValue:function(k,d){return Promise.resolve(GM_getValue(k,d));}," +
                "setValue:function(k,v){return Promise.resolve(GM_setValue(k,v));},deleteValue:function(k){return Promise.resolve(GM_deleteValue(k));}," +
                "listValues:function(){return Promise.resolve(GM_listValues());},openInTab:GM_openInTab,setClipboard:GM_setClipboard,xmlHttpRequest:GM_xmlhttpRequest,registerMenuCommand:GM_registerMenuCommand};";
        }

        // ------------------------------------------------------------------ parsing helpers

        /// <summary>Chrome match patterns: &lt;all_urls&gt;, scheme://host/path with * wildcards.</summary>
        public static Regex MatchPattern(string p)
        {
            if (string.IsNullOrWhiteSpace(p)) return null;
            if (p == "<all_urls>") return new Regex(@"^(https?|file)://", RegexOptions.IgnoreCase);
            var m = Regex.Match(p.Trim(), @"^(\*|https?|file|ftp)://([^/]*)(/.*)?$", RegexOptions.IgnoreCase);
            if (!m.Success) return null;
            string scheme = m.Groups[1].Value == "*" ? "https?" : Regex.Escape(m.Groups[1].Value);
            string host = m.Groups[2].Value;
            string hostRx = host == "*" ? "[^/]*"
                : host.StartsWith("*.") ? @"(?:[^/]*\.)?" + Regex.Escape(host.Substring(2))
                : Regex.Escape(host);
            string path = Regex.Escape(m.Groups[3].Success && m.Groups[3].Value.Length > 0 ? m.Groups[3].Value : "/").Replace(@"\*", ".*");
            return new Regex("^" + scheme + "://" + hostRx + @"(?::\d+)?" + path + "$", RegexOptions.IgnoreCase);
        }

        /// <summary>Userscript @include / @exclude: a glob with * (or /regex/).</summary>
        public static Regex Glob(string g)
        {
            if (string.IsNullOrWhiteSpace(g)) return null;
            g = g.Trim();
            try
            {
                if (g.Length > 2 && g.StartsWith("/") && g.EndsWith("/")) return new Regex(g.Substring(1, g.Length - 2), RegexOptions.IgnoreCase);
                if (g == "*") return new Regex(".*");
                return new Regex("^" + Regex.Escape(g).Replace(@"\*", ".*") + "$", RegexOptions.IgnoreCase);
            }
            catch { return null; }
        }

        private static Dictionary<string, List<string>> UserScriptMeta(string text)
        {
            var d = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
            var block = Regex.Match(text ?? "", @"//\s*==UserScript==(.*?)//\s*==/UserScript==", RegexOptions.Singleline);
            if (!block.Success) return d;
            foreach (Match line in Regex.Matches(block.Groups[1].Value, @"^\s*//\s*@([\w:-]+)(?:[ \t]+(.*?))?\s*$", RegexOptions.Multiline))
            {
                string k = line.Groups[1].Value;
                // localized names (@name:fr) don't replace the main one
                if (k.Contains(":")) continue;
                if (!d.ContainsKey(k)) d[k] = new List<string>();
                d[k].Add(line.Groups[2].Value.Trim());
            }
            return d;
        }

        private static string First(Dictionary<string, List<string>> meta, string k)
        {
            List<string> v;
            return meta.TryGetValue(k, out v) && v.Count > 0 ? v[0] : "";
        }

        private static List<string> Each(Dictionary<string, List<string>> meta, string k)
        {
            List<string> v;
            return meta.TryGetValue(k, out v) ? v : new List<string>();
        }

        private static Dictionary<string, string> Messages(string dir, string locale)
        {
            var d = new Dictionary<string, string>();
            foreach (var loc in new[] { locale, "en", "en_US", "en_GB" })
            {
                if (string.IsNullOrEmpty(loc)) continue;
                var f = Path.Combine(dir, "_locales", loc, "messages.json");
                if (!File.Exists(f)) continue;
                try
                {
                    using (var doc = JsonDocument.Parse(File.ReadAllText(f).TrimStart('\uFEFF'), Lenient))
                        foreach (var p in doc.RootElement.EnumerateObject())
                            if (p.Value.ValueKind == JsonValueKind.Object) d[p.Name.ToLowerInvariant()] = Str(p.Value, "message");
                }
                catch { }
                break;
            }
            return d;
        }

        private static string Localize(string s, Dictionary<string, string> msgs)
        {
            var m = Regex.Match(s ?? "", @"^__MSG_(\w+)__$");
            string v;
            if (m.Success && msgs.TryGetValue(m.Groups[1].Value.ToLowerInvariant(), out v)) return v;
            return s ?? "";
        }

        private static string ReadInside(string dir, string rel)
        {
            string full = Path.GetFullPath(Path.Combine(dir, rel.TrimStart('/', '\\')));
            if (!full.StartsWith(Path.GetFullPath(dir), StringComparison.OrdinalIgnoreCase)) return "";
            return File.Exists(full) ? File.ReadAllText(full) : "";
        }

        private static string Str(JsonElement e, string k)
        {
            return e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : "";
        }

        private static IEnumerable<string> Strings(JsonElement e, string k)
        {
            if (e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Array)
                foreach (var x in v.EnumerateArray()) if (x.ValueKind == JsonValueKind.String) yield return x.GetString();
        }

        private static string Slug(string name)
        {
            var s = Regex.Replace(name.ToLowerInvariant(), "[^a-z0-9]+", "-").Trim('-');
            if (s.Length > 32) s = s.Substring(0, 32).Trim('-');
            using (var sha = SHA256.Create())
                return (s.Length > 0 ? s + "-" : "") + BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(name))).Replace("-", "").Substring(0, 8).ToLowerInvariant();
        }

        private static void CopyDir(string from, string to)
        {
            Directory.CreateDirectory(to);
            foreach (var f in Directory.GetFiles(from, "*", SearchOption.AllDirectories))
            {
                var rel = Path.GetRelativePath(from, f);
                var dst = Path.Combine(to, rel);
                Directory.CreateDirectory(Path.GetDirectoryName(dst));
                File.Copy(f, dst, true);
            }
        }
    }

    /// <summary>
    /// Serves extensions' own files at facet-extension://ext/&lt;id&gt;/&lt;path&gt;, so chrome.runtime.getURL() works for
    /// images, fonts, styles and fetch(). Files outside the extensions folder are never served.
    /// </summary>
    public class ExtensionFiles : ISchemeHandlerFactory
    {
        public IResourceHandler Create(IBrowser browser, IFrame frame, string schemeName, IRequest request)
        {
            try
            {
                var u = new Uri(request.Url);
                string rel = Uri.UnescapeDataString(u.AbsolutePath).TrimStart('/').Replace('/', Path.DirectorySeparatorChar);
                string root = Path.GetFullPath(Extensions.Root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                string file = Path.GetFullPath(Path.Combine(root, rel));
                if (!file.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !File.Exists(file)) return ResourceHandler.ForErrorMessage("Not found", System.Net.HttpStatusCode.NotFound);
                string mime = Cef.GetMimeType(Path.GetExtension(file).TrimStart('.'));
                var h = new ResourceHandler(string.IsNullOrEmpty(mime) ? "application/octet-stream" : mime, new MemoryStream(File.ReadAllBytes(file)), true, null);
                h.StatusCode = 200;
                // pages fetch extension files cross-origin, as web_accessible_resources allow in Chrome
                h.Headers.Add("Access-Control-Allow-Origin", "*");
                return h;
            }
            catch { return ResourceHandler.ForErrorMessage("Not found", System.Net.HttpStatusCode.NotFound); }
        }
    }

    /// <summary>Hands each new page / frame to the extension engine.</summary>
    public class FacetInjector : IRenderProcessMessageHandler
    {
        private readonly bool isPrivate;
        public FacetInjector(bool isPrivate) { this.isPrivate = isPrivate; }

        public void OnContextCreated(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame) { Extensions.Inject(frame, isPrivate); }
        public void OnContextReleased(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame) { }
        public void OnFocusedNodeChanged(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IDomNode node) { }
        public void OnUncaughtException(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, JavascriptException exception) { }
    }
}
