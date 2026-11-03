using System;
using System.IO;
using System.Windows;
using CefSharp;
using CefSharp.Handler;
using CefSharp.Wpf;

namespace Facet
{
    public class BrowserTab
    {
        public ChromiumWebBrowser Browser;
        public bool IsPrivate;
        public string Title = "New tab";
        public string Url = "";
        public bool Loading, CanBack, CanForward;
        public bool ShowsNewTab = true;
        /// <summary>Name of the tab group this tab belongs to, or null. Groups live inside one profile.</summary>
        public string Group;

        /// <summary>A restored tab that hasn't been looked at yet. It stays unloaded (no page, no renderer work) until selected.</summary>
        private string pending;

        /// <summary>Raised on the UI thread whenever title, address or loading state changes.</summary>
        public event Action Changed;
        public event Action<string> PopupRequested;
        public event Action<string> Shortcut;
        public event Action<string> DownloadFinished;
        public event Action<Ask> AskRequested;
        public event Action<MenuTarget> MenuRequested;
        public event Action<bool> FullscreenChanged;
        public event Action Crashed;
        public event Action<string> UserScriptOffered;
        public event Action<int, int> FindResult;

        /// <summary>Raised when a page has finished loading (for history and research highlights).</summary>
        public event Action PageLoaded;
        /// <summary>A link to an extension or userscript file was opened: offer to install it instead of saving it.</summary>
        public event Action<string, string> ExtensionOffered;

        /// <summary>Page zoom in percent.</summary>
        public int Zoom = 100;

        /// <summary>The engine's id for this tab's browser, once it exists (0 before).</summary>
        public int EngineId { get { try { return Browser.IsBrowserInitialized ? Browser.GetBrowser().Identifier : 0; } catch { return 0; } } }

        /// <param name="context">The profile's storage; null for the main (Personal) storage.</param>
        public BrowserTab(Prefs prefs, bool isPrivate, string url, bool lazy = false, IRequestContext context = null)
        {
            IsPrivate = isPrivate;
            Browser = new ChromiumWebBrowser();
            // Pages draw at 30 fps by default here, which makes scrolling and page animations look choppy.
            // Hidden tabs don't draw at all, so this only costs anything for the tab being watched.
            Browser.BrowserSettings.WindowlessFrameRate = Math.Min(60, Anim.RefreshRate());
            if (!prefs.Javascript) Browser.BrowserSettings.Javascript = CefState.Disabled;
            if (isPrivate)
            {
                // An empty cache path means an in-memory request context: nothing is written to disk.
                Browser.RequestContext = new RequestContext(new RequestContextSettings { CachePath = "" });
            }
            else if (context != null) Browser.RequestContext = context;
            Browser.RequestHandler = new FacetRequestHandler(prefs,
                () => Ui_Dispatch(() => { if (Crashed != null) Crashed(); }),
                u => Ui_Dispatch(() => { if (UserScriptOffered != null) UserScriptOffered(u); }));
            Browser.PermissionHandler = new FacetPermissions(a => Ui_Dispatch(() => { if (AskRequested != null) AskRequested(a); else a.Answer(false, null); }));
            Browser.JsDialogHandler = new FacetDialogs(a => Ui_Dispatch(() => { if (AskRequested != null) AskRequested(a); else a.Answer(false, null); }));
            Browser.MenuHandler = new FacetContextMenu(t => Ui_Dispatch(() => { if (MenuRequested != null) MenuRequested(t); }));
            Browser.DisplayHandler = new FacetDisplay(on => Ui_Dispatch(() => { if (FullscreenChanged != null) FullscreenChanged(on); }));
            Browser.FindHandler = new FacetFind((i, n) => Ui_Dispatch(() => { if (FindResult != null) FindResult(i, n); }));
            Browser.RenderProcessMessageHandler = new FacetInjector(isPrivate);
            Browser.LifeSpanHandler = new FacetLifeSpan(u => Ui_Dispatch(() => { if (PopupRequested != null) PopupRequested(u); }));
            Browser.DownloadHandler = new FacetDownloads(
                name => Ui_Dispatch(() => { if (DownloadFinished != null) DownloadFinished(name); }),
                (u, n) =>
                {
                    if (ExtensionOffered == null) return false;
                    Ui_Dispatch(() => ExtensionOffered(u, n));
                    return true;
                });
            Browser.KeyboardHandler = new FacetKeys(a => Ui_Dispatch(() => { if (Shortcut != null) Shortcut(a); }));

            Browser.TitleChanged += (s, e) => Ui_Dispatch(() =>
            {
                var t = e.NewValue as string;
                Title = string.IsNullOrWhiteSpace(t) ? Urls.Host(Url) : t;
                Raise();
            });
            Browser.AddressChanged += (s, e) => Ui_Dispatch(() =>
            {
                var a = e.NewValue as string ?? "";
                bool blank = a.Length == 0 || a.StartsWith("about:blank", StringComparison.OrdinalIgnoreCase);
                if (blank && pending != null) return; // still waiting to be opened: keep showing the saved page
                ShowsNewTab = blank;
                Url = blank ? "" : a;
                if (blank) Title = "New tab";
                Raise();
            });
            Browser.LoadingStateChanged += (s, e) => Ui_Dispatch(() =>
            {
                bool finished = Loading && !e.IsLoading;
                Loading = e.IsLoading;
                CanBack = e.CanGoBack;
                CanForward = e.CanGoForward;
                Raise();
                if (finished && !ShowsNewTab && PageLoaded != null) PageLoaded();
            });

            if (!string.IsNullOrEmpty(url) && url != "about:blank")
            {
                ShowsNewTab = false;
                Url = url;
                if (lazy) { pending = url; Title = Urls.Host(url); }
                else Browser.Address = url;
            }
        }

        private void Raise() { if (Changed != null) Changed(); }

        /// <summary>Takes the address back from the engine after a navigation Facet cancelled.</summary>
        public void SyncAddress()
        {
            var a = Browser.Address ?? "";
            bool blank = a.Length == 0 || a.StartsWith("about:blank", StringComparison.OrdinalIgnoreCase);
            ShowsNewTab = blank && pending == null;
            if (!blank) Url = a;
            else if (pending == null) Url = "";
            Raise();
        }

        /// <summary>Loads a restored tab the first time it is shown.</summary>
        public void Activate()
        {
            if (pending != null) Go(pending);
        }

        private static readonly int[] ZoomSteps = { 25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300, 400, 500 };

        /// <summary>Steps through the usual browser zoom levels (direction 0 resets to 100%).</summary>
        public int StepZoom(int direction)
        {
            int i = Array.IndexOf(ZoomSteps, Zoom);
            if (i < 0) i = Array.IndexOf(ZoomSteps, 100);
            return SetZoom(direction == 0 ? 100 : ZoomSteps[Math.Max(0, Math.Min(ZoomSteps.Length - 1, i + direction))]);
        }

        public int SetZoom(int percent)
        {
            Zoom = percent;
            // Chromium zoom levels are powers of 1.2: 1.2^level = scale
            try { if (Browser.IsBrowserInitialized) Browser.GetBrowserHost().SetZoomLevel(Math.Log(Zoom / 100.0) / Math.Log(1.2)); } catch { }
            return Zoom;
        }

        /// <summary>Hidden tabs stop painting and Chromium throttles their timers, so background pages cost almost nothing.</summary>
        public void SetHidden(bool hidden)
        {
            try { if (Browser.IsBrowserInitialized) Browser.GetBrowserHost().WasHidden(hidden); } catch { }
        }

        public void Go(string url)
        {
            pending = null;
            ShowsNewTab = false;
            Url = url;
            if (Browser.IsBrowserInitialized) Browser.Load(url); else Browser.Address = url;
            Raise();
        }

        private static void Ui_Dispatch(Action a)
        {
            var d = Application.Current?.Dispatcher;
            if (d == null) return;
            if (d.CheckAccess()) a(); else d.BeginInvoke(a);
        }
    }

    /// <summary>Opens pop-ups and target=_blank links as ordinary Facet tabs.</summary>
    public class FacetLifeSpan : LifeSpanHandler
    {
        private readonly Action<string> open;
        public FacetLifeSpan(Action<string> open) { this.open = open; }

        protected override bool OnBeforePopup(
            IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, string targetUrl, string targetFrameName,
            WindowOpenDisposition targetDisposition, bool userGesture, IPopupFeatures popupFeatures, IWindowInfo windowInfo,
            IBrowserSettings browserSettings, ref bool noJavascriptAccess, out IWebBrowser newBrowser)
        {
            newBrowser = null;
            // Pop-ups that the page opened without a click are dropped.
            if (!userGesture) return true;
            // Sign-in windows (window.open) keep their link to the opener, so let the engine create them natively.
            if (targetDisposition == WindowOpenDisposition.NewPopup || targetDisposition == WindowOpenDisposition.NewWindow) return false;
            if (!string.IsNullOrEmpty(targetUrl)) open(targetUrl);
            return true;
        }
    }

    /// <summary>Saves downloads to the Downloads folder and keeps the Downloads page up to date.</summary>
    public class FacetDownloads : IDownloadHandler
    {
        private readonly Action<string> done;
        private readonly Func<string, string, bool> offerExtension;

        // shared by all tabs: the entry for each engine download id, and ids the person asked to cancel
        private static readonly System.Collections.Concurrent.ConcurrentDictionary<int, DownloadEntry> entries = new System.Collections.Concurrent.ConcurrentDictionary<int, DownloadEntry>();
        private static readonly System.Collections.Concurrent.ConcurrentDictionary<int, bool> cancels = new System.Collections.Concurrent.ConcurrentDictionary<int, bool>();
        private static readonly System.Collections.Concurrent.ConcurrentDictionary<int, long> lastShown = new System.Collections.Concurrent.ConcurrentDictionary<int, long>();

        public FacetDownloads(Action<string> done, Func<string, string, bool> offerExtension)
        {
            this.done = done;
            this.offerExtension = offerExtension;
        }

        /// <summary>Asks the engine to stop a download; it stops at its next progress report.</summary>
        public static void Cancel(DownloadEntry e) { cancels[e.Id] = true; }

        public static string Folder
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        public bool CanDownload(IWebBrowser chromiumWebBrowser, IBrowser browser, string url, string requestMethod) { return true; }

        public bool OnBeforeDownload(IWebBrowser chromiumWebBrowser, IBrowser browser, DownloadItem downloadItem, IBeforeDownloadCallback callback)
        {
            string name = Path.GetFileName(downloadItem.SuggestedFileName);
            if (string.IsNullOrWhiteSpace(name)) name = "download";
            // extensions and userscripts get installed rather than saved (after asking)
            string lower = name.ToLowerInvariant();
            if ((lower.EndsWith(".xpi") || lower.EndsWith(".crx") || lower.EndsWith(".user.js")) && offerExtension(downloadItem.Url, name))
                return true; // not continuing the callback leaves the file unsaved
            string dir = Folder;
            string path = Path.Combine(dir, name);
            int n = 1;
            string stem = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
            while (File.Exists(path)) path = Path.Combine(dir, stem + " (" + (n++) + ")" + ext);
            var entry = new DownloadEntry
            {
                Id = downloadItem.Id, Url = downloadItem.Url ?? "", Name = Path.GetFileName(path), Path = path,
                Total = downloadItem.TotalBytes, Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
            entries[downloadItem.Id] = entry;
            Show(entry, true);
            if (!callback.IsDisposed) callback.Continue(path, false);
            return true;
        }

        public void OnDownloadUpdated(IWebBrowser chromiumWebBrowser, IBrowser browser, DownloadItem downloadItem, IDownloadItemCallback callback)
        {
            bool stop;
            if (cancels.TryRemove(downloadItem.Id, out stop) && !callback.IsDisposed) { callback.Cancel(); }
            DownloadEntry e;
            if (!entries.TryGetValue(downloadItem.Id, out e)) return;
            e.Received = downloadItem.ReceivedBytes;
            if (downloadItem.TotalBytes > 0) e.Total = downloadItem.TotalBytes;
            int state = downloadItem.IsComplete ? 1 : downloadItem.IsCancelled ? 3 : downloadItem.IsInterrupted ? 2 : 0;
            bool changed = state != e.State;
            e.State = state;
            if (state != 0) { DownloadEntry gone; entries.TryRemove(downloadItem.Id, out gone); }
            if (downloadItem.IsComplete) done(Path.GetFileName(downloadItem.FullPath));
            // progress is shown a few times a second; finishing, failing and cancelling right away
            long now = Environment.TickCount64, last;
            if (changed || !lastShown.TryGetValue(downloadItem.Id, out last) || now - last > 250)
            {
                lastShown[downloadItem.Id] = now;
                Show(e, changed);
            }
        }

        private static void Show(DownloadEntry e, bool save)
        {
            var d = Application.Current?.Dispatcher;
            if (d != null) d.BeginInvoke(new Action(() => Downloads.Update(e, save)));
        }
    }

    /// <summary>Keyboard shortcuts have to be caught here, because the web page owns the keyboard once it is focused.</summary>
    public class FacetKeys : IKeyboardHandler
    {
        private readonly Action<string> act;
        public FacetKeys(Action<string> act) { this.act = act; }

        public bool OnPreKeyEvent(IWebBrowser chromiumWebBrowser, IBrowser browser, KeyType type, int windowsKeyCode, int nativeKeyCode,
            CefEventFlags modifiers, bool isSystemKey, ref bool isKeyboardShortcut)
        {
            if (type != KeyType.RawKeyDown) return false;
            bool ctrl = (modifiers & CefEventFlags.ControlDown) != 0;
            bool alt = (modifiers & CefEventFlags.AltDown) != 0;
            bool shift = (modifiers & CefEventFlags.ShiftDown) != 0;
            string a = null;
            if (ctrl && windowsKeyCode == 0x54) a = shift ? "private" : "newtab";
            else if (ctrl && windowsKeyCode == 0x4E && shift) a = "private";
            else if (ctrl && windowsKeyCode == 0x57) a = "close";
            else if (ctrl && windowsKeyCode == 0x4C) a = "address";
            else if (ctrl && windowsKeyCode == 0x44) a = "bookmark";
            else if (ctrl && windowsKeyCode == 0x09) a = shift ? "prevtab" : "nexttab";
            else if (windowsKeyCode == 0x74 || (ctrl && windowsKeyCode == 0x52)) a = "reload";
            else if (alt && windowsKeyCode == 0x25) a = "back";
            else if (alt && windowsKeyCode == 0x27) a = "forward";
            else if (ctrl && windowsKeyCode == 0x46) a = "find";                                        // Ctrl+F
            else if (ctrl && windowsKeyCode == 0x50) a = "print";                                       // Ctrl+P
            else if (ctrl && (windowsKeyCode == 0xBB || windowsKeyCode == 0x6B)) a = "zoomin";          // Ctrl+= / Ctrl+numpad +
            else if (ctrl && (windowsKeyCode == 0xBD || windowsKeyCode == 0x6D)) a = "zoomout";         // Ctrl+- / Ctrl+numpad -
            else if (ctrl && (windowsKeyCode == 0x30 || windowsKeyCode == 0x60)) a = "zoomreset";       // Ctrl+0
            else if (windowsKeyCode == 0x7A) a = "fullscreen";                                          // F11
            else if (windowsKeyCode == 0x72) a = "findnext";                                            // F3
            else if (ctrl && windowsKeyCode == 0x53) a = "savepage";                                    // Ctrl+S
            else if (ctrl && windowsKeyCode == 0x4B) a = "command";                                     // Ctrl+K
            else if (ctrl && windowsKeyCode == 0x48) a = "history";                                     // Ctrl+H
            else if (ctrl && windowsKeyCode == 0x4A) a = "downloads";                                   // Ctrl+J
            else if (windowsKeyCode == 0x78) a = "reader";                                              // F9
            else if (ctrl && windowsKeyCode == 0x55) a = "source";                                      // Ctrl+U
            else if ((ctrl && shift && windowsKeyCode == 0x49) || windowsKeyCode == 0x7B) a = "devtools"; // Ctrl+Shift+I / F12
            if (a == null) return false;
            act(a);
            return true;
        }

        public bool OnKeyEvent(IWebBrowser chromiumWebBrowser, IBrowser browser, KeyType type, int windowsKeyCode, int nativeKeyCode,
            CefEventFlags modifiers, bool isSystemKey)
        {
            return false;
        }
    }
}
