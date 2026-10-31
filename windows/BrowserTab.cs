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

        /// <summary>Raised on the UI thread whenever title, address or loading state changes.</summary>
        public event Action Changed;
        public event Action<string> PopupRequested;
        public event Action<string> Shortcut;
        public event Action<string> DownloadFinished;

        public BrowserTab(Prefs prefs, bool isPrivate, string url)
        {
            IsPrivate = isPrivate;
            Browser = new ChromiumWebBrowser();
            if (isPrivate)
            {
                // An empty cache path means an in-memory request context: nothing is written to disk.
                Browser.RequestContext = new RequestContext(new RequestContextSettings { CachePath = "" });
            }
            Browser.RequestHandler = new FacetRequestHandler(prefs);
            Browser.LifeSpanHandler = new FacetLifeSpan(u => Ui_Dispatch(() => { if (PopupRequested != null) PopupRequested(u); }));
            Browser.DownloadHandler = new FacetDownloads(name => Ui_Dispatch(() => { if (DownloadFinished != null) DownloadFinished(name); }));
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
                ShowsNewTab = blank;
                Url = blank ? "" : a;
                if (blank) Title = "New tab";
                Raise();
            });
            Browser.LoadingStateChanged += (s, e) => Ui_Dispatch(() =>
            {
                Loading = e.IsLoading;
                CanBack = e.CanGoBack;
                CanForward = e.CanGoForward;
                Raise();
            });

            if (!string.IsNullOrEmpty(url) && url != "about:blank")
            {
                ShowsNewTab = false;
                Url = url;
                Browser.Address = url;
            }
        }

        private void Raise() { if (Changed != null) Changed(); }

        public void Go(string url)
        {
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

    public class FacetDownloads : IDownloadHandler
    {
        private readonly Action<string> done;
        public FacetDownloads(Action<string> done) { this.done = done; }

        public bool CanDownload(IWebBrowser chromiumWebBrowser, IBrowser browser, string url, string requestMethod) { return true; }

        public bool OnBeforeDownload(IWebBrowser chromiumWebBrowser, IBrowser browser, DownloadItem downloadItem, IBeforeDownloadCallback callback)
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            Directory.CreateDirectory(dir);
            string name = Path.GetFileName(downloadItem.SuggestedFileName);
            if (string.IsNullOrWhiteSpace(name)) name = "download";
            string path = Path.Combine(dir, name);
            int n = 1;
            string stem = Path.GetFileNameWithoutExtension(name), ext = Path.GetExtension(name);
            while (File.Exists(path)) path = Path.Combine(dir, stem + " (" + (n++) + ")" + ext);
            if (!callback.IsDisposed) callback.Continue(path, false);
            return true;
        }

        public void OnDownloadUpdated(IWebBrowser chromiumWebBrowser, IBrowser browser, DownloadItem downloadItem, IDownloadItemCallback callback)
        {
            if (downloadItem.IsComplete) done(Path.GetFileName(downloadItem.FullPath));
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
