using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shell;
using System.Windows.Threading;
using CefSharp;

namespace Facet
{
    /// <summary>Page dialogs, permissions, the right-click menu, find, zoom, print, fullscreen, crash recovery and extensions.</summary>
    public partial class MainWindow
    {
        private Grid titleRowEl, toolbarEl;

        private void HookTab(BrowserTab t)
        {
            t.AskRequested += a => ShowAsk(a);
            t.MenuRequested += m => { if (t == current) ShowContextMenu(m, t); };
            t.FullscreenChanged += on => { if (t == current) SetFullscreen(on); };
            t.Crashed += () => Recover(t);
            t.UserScriptOffered += u =>
            {
                // the script link was intercepted, so the tab stays where it was: show that address again
                t.SyncAddress();
                if (t == current) RefreshToolbar();
                OfferUserScript(u);
            };
            t.FindResult += (i, n) =>
            {
                if (t != current || findCount == null) return;
                findCount.Text = findBox.Text.Length == 0 ? "" : n == 0 ? "No results" : Math.Max(1, i) + " of " + n;
            };
        }

        // ------------------------------------------------------------ questions (permissions and page dialogs)

        private readonly Queue<Ask> asks = new Queue<Ask>();
        private bool asking;

        /// <summary>One question at a time; later ones wait their turn.</summary>
        private void ShowAsk(Ask a)
        {
            if (AnswerFromMemory(a)) return;
            if (asking || (modal.Visibility == Visibility.Visible && modal.IsHitTestVisible)) { asks.Enqueue(a); return; }
            asking = true;
            Border card;
            var scrim = Scrim(out card, 440);
            bool answered = false, remember = false;
            TextBox field = null;
            Action<bool> finish = ok =>
            {
                if (answered) return;
                answered = true;
                if (remember && a.PermissionHost != null && a.PermissionType != null) SitePerms.Set(a.PermissionHost, a.PermissionType, ok);
                CloseModal();
                asking = false;
                try { a.Answer(ok, field != null ? field.Text : null); } catch { /* the page is gone */ }
                if (asks.Count > 0) Dispatcher.BeginInvoke(new Action(() => ShowAsk(asks.Dequeue())), DispatcherPriority.Background);
            };
            // clicking outside answers "no" (an alert has only one answer)
            scrim.MouseLeftButtonUp += (s, e) => finish(a.Cancel == null);

            var sp = new StackPanel();
            var title = Ui.Text(a.Title, 22, p.Ink, Fonts.Serif);
            title.TextWrapping = TextWrapping.Wrap; title.TextTrimming = TextTrimming.None;
            sp.Children.Add(title);
            if (a.Message.Length > 0)
            {
                var msg = Ui.Text(a.Message, 14, p.Muted, Fonts.Sans);
                msg.TextWrapping = TextWrapping.Wrap; msg.TextTrimming = TextTrimming.None; msg.Margin = new Thickness(0, 8, 0, 0);
                var sv = new ScrollViewer { Content = msg, MaxHeight = 260, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
                Scrolling.Smooth(sv);
                sp.Children.Add(sv);
            }
            if (a.Field != null)
            {
                field = new TextBox
                {
                    Text = a.Field, Background = p.Surface, Foreground = p.Ink, BorderBrush = p.Line, FontFamily = Fonts.Sans, FontSize = 15,
                    Padding = new Thickness(10, 9, 10, 9), Margin = new Thickness(0, 14, 0, 0), CaretBrush = p.Ink, SelectionBrush = p.Accent
                };
                sp.Children.Add(field);
            }
            if (a.PermissionType != null && a.PermissionHost != null && a.PermissionHost.Length > 0)
            {
                var kit = new Kit(p);
                var row = kit.Toggle(null, "Remember for " + a.PermissionHost, "Change it any time under Settings, Site permissions.", false, v => remember = v);
                ((FrameworkElement)row).Margin = new Thickness(-16, 8, -16, 0);
                sp.Children.Add(row);
            }
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 18, 0, 0) };
            if (a.Cancel != null) buttons.Children.Add(Ui.PillButton(p, a.Cancel, false, () => finish(false)));
            var ok = Ui.PillButton(p, a.Ok, true, () => finish(true));
            ok.Margin = new Thickness(10, 0, 0, 0);
            buttons.Children.Add(ok);
            sp.Children.Add(buttons);
            card.Child = sp;
            card.Focusable = true;
            card.FocusVisualStyle = null; // the card takes Enter/Esc; no dotted focus outline around it
            card.PreviewKeyDown += (s, e) =>
            {
                if (e.Key == Key.Enter) { finish(true); e.Handled = true; }
                else if (e.Key == Key.Escape) { finish(a.Cancel == null); e.Handled = true; }
            };
            OpenModal(scrim, card);
            UIElement focus = field != null ? (UIElement)field : card;
            Dispatcher.BeginInvoke(new Action(() => { focus.Focus(); if (field != null) field.SelectAll(); }), DispatcherPriority.Background);
        }

        // ------------------------------------------------------------ menus

        private void ShowPopupMenu(List<Tuple<string, string, Action>> items, UIElement target, PlacementMode mode, double hOffset)
        {
            var pop = new Popup { PlacementTarget = target, Placement = mode, StaysOpen = false, AllowsTransparency = true, HorizontalOffset = hOffset };
            var card = Ui.Box(p.Bg, 14, p.Line);
            card.MinWidth = 220; card.Margin = new Thickness(10);
            card.Effect = new DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Opacity = 0.25, Color = Colors.Black, RenderingBias = RenderingBias.Performance };
            var sp = new StackPanel { Margin = new Thickness(6) };
            foreach (var it in items)
            {
                var item = it;
                if (item == null) { sp.Children.Add(new Border { Height = 1, Background = p.Line, Margin = new Thickness(10, 4, 10, 4) }); continue; }
                var row = new Border { CornerRadius = new CornerRadius(9), Padding = new Thickness(10, 7, 14, 7) };
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                // "Label\tCtrl+C": the part after a tab is the keyboard shortcut, shown muted on the right as in Chrome
                var parts = item.Item2.Split('\t');
                g.Children.Add(item.Item1 != null ? Icons.Make(item.Item1, p.Ink, 17) : new Border { Width = 17 });
                var t = Ui.Text(parts[0], 13.5, p.Ink, Fonts.Sans, FontWeights.Medium);
                t.Margin = new Thickness(12, 0, 0, 0); t.VerticalAlignment = VerticalAlignment.Center; t.MaxWidth = 320;
                Grid.SetColumn(t, 1);
                g.Children.Add(t);
                if (parts.Length > 1)
                {
                    var k = Ui.Text(parts[1], 12, p.Muted, Fonts.Sans);
                    k.Margin = new Thickness(24, 0, 0, 0); k.VerticalAlignment = VerticalAlignment.Center;
                    Grid.SetColumn(k, 2);
                    g.Children.Add(k);
                }
                row.Child = g;
                Ui.Button(row, Brushes.Transparent, p.Hover, () => { pop.IsOpen = false; item.Item3(); });
                sp.Children.Add(row);
            }
            card.Child = sp;
            pop.Child = card;
            // Esc closes the menu, as in Chrome; the menu takes keyboard focus while it's open
            card.Focusable = true;
            card.FocusVisualStyle = null;
            card.PreviewKeyDown += (s, e) => { if (e.Key == Key.Escape) { pop.IsOpen = false; e.Handled = true; } };
            pop.Opened += (s, e) => card.Focus();
            pop.IsOpen = true;
            Kit.PopIn(card, mode == PlacementMode.MousePoint ? new Point(0, 0) : new Point(1, 0));
        }

        private static Tuple<string, string, Action> Item(string icon, string text, Action act) { return Tuple.Create(icon, text, act); }

        /// <summary>
        /// Facet's right-click menu, with what Chrome and Brave offer: page, link, image, video/audio, selected text and
        /// text-field actions (with spelling suggestions), then Inspect. Shortcuts are listed on the right.
        /// </summary>
        private void ShowContextMenu(MenuTarget m, BrowserTab t)
        {
            var items = new List<Tuple<string, string, Action>>();
            Action sep = () => { if (items.Count > 0 && items[items.Count - 1] != null) items.Add(null); };
            string engine = Urls.EngineById(prefs.SearchEngine).Name;
            bool media = m.Video.Length > 0 || m.Audio.Length > 0;

            if (m.Editable && m.Misspelled.Length > 0)
            {
                foreach (var s in m.Suggestions.GetRange(0, Math.Min(4, m.Suggestions.Count)))
                {
                    var word = s;
                    items.Add(Item(null, word, () => Host(t, h => h.ReplaceMisspelling(word))));
                }
                if (m.Suggestions.Count == 0) items.Add(Item(null, "No spelling suggestions", () => { }));
                items.Add(Item("plus", "Add to dictionary", () => Host(t, h => h.AddWordToDictionary(m.Misspelled))));
                sep();
            }
            if (m.Link.Length > 0)
            {
                items.Add(Item("plus", "Open link in new tab", () => NewTab(m.Link, t.IsPrivate)));
                if (!t.IsPrivate) items.Add(Item("private", "Open link in private tab", () => NewTab(m.Link, true)));
                sep();
                items.Add(Item("download", "Save link", () => Download(t, m.Link)));
                items.Add(Item("link", "Copy link address", () => CopyText(m.Link, "Link copied")));
            }
            if (m.Image.Length > 0)
            {
                sep();
                items.Add(Item("image", "Open image in new tab", () => NewTab(m.Image, t.IsPrivate)));
                items.Add(Item("download", "Save image", () => Download(t, m.Image)));
                items.Add(Item("copy", "Copy image", () => CopyImage(m.Image)));
                items.Add(Item("link", "Copy image address", () => CopyText(m.Image, "Image address copied")));
                if (m.Image.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                    items.Add(Item("search", "Search image with Google Lens", () => NewTab("https://lens.google.com/uploadbyurl?url=" + Uri.EscapeDataString(m.Image), t.IsPrivate)));
            }
            if (media)
            {
                sep();
                string src = m.Video.Length > 0 ? m.Video : m.Audio, kind = m.Video.Length > 0 ? "video" : "audio";
                items.Add(Item(m.MediaLoops ? "check" : null, "Loop", () => MediaJs(t, m, "v.loop=!v.loop;")));
                items.Add(Item(null, "Show controls", () => MediaJs(t, m, "v.controls=!v.controls;")));
                if (m.Video.Length > 0) items.Add(Item("tabs", "Picture in picture", () => MediaJs(t, m,
                    "if(document.pictureInPictureElement)document.exitPictureInPicture();else if(v.requestPictureInPicture)v.requestPictureInPicture();")));
                if (src.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                {
                    sep();
                    items.Add(Item("plus", "Open " + kind + " in new tab", () => NewTab(src, t.IsPrivate)));
                    items.Add(Item("download", "Save " + kind, () => Download(t, src)));
                    items.Add(Item("link", "Copy " + kind + " address", () => CopyText(src, "Address copied")));
                }
            }
            if (m.Editable)
            {
                sep();
                items.Add(Item(null, "Undo\tCtrl+Z", () => Edit(t, f => f.Undo())));
                items.Add(Item(null, "Redo\tCtrl+Y", () => Edit(t, f => f.Redo())));
                sep();
                if (m.CanCut) items.Add(Item("cut", "Cut\tCtrl+X", () => Edit(t, f => f.Cut())));
                if (m.CanCopy) items.Add(Item("copy", "Copy\tCtrl+C", () => Edit(t, f => f.Copy())));
                if (m.CanPaste)
                {
                    items.Add(Item("paste", "Paste\tCtrl+V", () => Edit(t, f => f.Paste())));
                    items.Add(Item(null, "Paste as plain text\tCtrl+Shift+V", () => PastePlain(t)));
                }
                items.Add(Item("check", "Select all\tCtrl+A", () => Edit(t, f => f.SelectAll())));
            }
            else if (m.Selection.Trim().Length > 0)
            {
                sep();
                string sel = m.Selection.Trim();
                string shown = sel.Length > 28 ? sel.Substring(0, 28) + "…" : sel;
                items.Add(Item("copy", "Copy\tCtrl+C", () => CopyText(sel, null)));
                if (Regex.IsMatch(sel, @"^(https?://)?[\w-]+(\.[\w-]+)+(/\S*)?$") && m.Link.Length == 0)
                    items.Add(Item("globe", "Go to " + shown, () => NewTab(Urls.Resolve(sel, prefs.SearchEngine), t.IsPrivate)));
                items.Add(Item("search", "Search " + engine + " for “" + shown + "”", () =>
                    NewTab(string.Format(Urls.EngineById(prefs.SearchEngine).Template, Uri.EscapeDataString(sel)), t.IsPrivate)));
                items.Add(Item("print", "Print\tCtrl+P", Print));
            }
            if (items.Count == 0)
            {
                items.Add(Item("back", "Back\tAlt+Left", () => { if (t.CanBack) t.Browser.Back(); }));
                items.Add(Item("forward", "Forward\tAlt+Right", () => { if (t.CanForward) t.Browser.Forward(); }));
                items.Add(Item("refresh", "Reload\tCtrl+R", () => t.Browser.Reload()));
                sep();
                items.Add(Item("download", "Save page\tCtrl+S", () => Download(t, t.Url)));
                items.Add(Item("print", "Print\tCtrl+P", Print));
                items.Add(Item("search", "Find in page\tCtrl+F", OpenFind));
                sep();
                items.Add(Item("star", (IsBookmarked(t.Url) ? "Remove bookmark" : "Add bookmark") + "\tCtrl+D", ToggleBookmark));
                items.Add(Item("link", "Copy page link", () => CopyText(t.Url, "Link copied")));
                sep();
                items.Add(Item("copy", "View page source\tCtrl+U", () => ViewSource(t)));
            }
            sep();
            items.Add(Item("settings", "Inspect\tCtrl+Shift+I", () => Inspect(t, m.X, m.Y)));
            ShowPopupMenu(items, this, PlacementMode.MousePoint, 0);
        }

        private static void Host(BrowserTab t, Action<IBrowserHost> act)
        {
            try { act(t.Browser.GetBrowserHost()); } catch { }
        }

        /// <summary>Saves to the Downloads folder, like any other download (a toast says when it's done).</summary>
        private void Download(BrowserTab t, string url)
        {
            if (string.IsNullOrEmpty(url)) return;
            Host(t, h => h.StartDownload(url));
            Toast("Downloading…");
        }

        /// <summary>Runs a snippet on the video/audio element that was right-clicked (v), falling back to the first one on the page.</summary>
        private static void MediaJs(BrowserTab t, MenuTarget m, string body)
        {
            double z = t.Zoom / 100.0;
            string js = "(function(){var e=document.elementFromPoint(" + (int)(m.X / z) + "," + (int)(m.Y / z) + ");" +
                        "var v=(e&&e.closest&&e.closest('video,audio'))||document.querySelector('video,audio');if(!v)return;" + body + "})();";
            try { using (var f = t.Browser.GetMainFrame()) f.ExecuteJavaScriptAsync(js); } catch { }
        }

        /// <summary>Pastes the clipboard's text without its formatting.</summary>
        private static void PastePlain(BrowserTab t)
        {
            string text;
            try { text = Clipboard.GetText(); } catch { return; }
            if (string.IsNullOrEmpty(text)) return;
            string js = "document.execCommand('insertText',false," + System.Text.Json.JsonSerializer.Serialize(text) + ");";
            try { using (var f = t.Browser.GetFocusedFrame()) f.ExecuteJavaScriptAsync(js); } catch { }
        }

        /// <summary>Downloads the picture and puts it on the clipboard as an image (not just its address).</summary>
        private void CopyImage(string url)
        {
            Task.Run(async () =>
            {
                byte[] data;
                if (url.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
                {
                    int comma = url.IndexOf(',');
                    data = Convert.FromBase64String(url.Substring(comma + 1));
                }
                else using (var c = new HttpClient()) data = await c.GetByteArrayAsync(url);
                return data;
            }).ContinueWith(task => Dispatcher.Invoke(() =>
            {
                try
                {
                    if (task.IsFaulted) throw task.Exception;
                    var img = new System.Windows.Media.Imaging.BitmapImage();
                    img.BeginInit();
                    img.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                    img.StreamSource = new System.IO.MemoryStream(task.Result);
                    img.EndInit();
                    Clipboard.SetImage(img);
                    Toast("Image copied");
                }
                catch { CopyText(url, "Couldn't copy the picture, so its address was copied"); }
            }));
        }

        private void ViewSource(BrowserTab t)
        {
            if (t == null || t.ShowsNewTab || string.IsNullOrEmpty(t.Url)) return;
            NewTab("view-source:" + t.Url, t.IsPrivate);
        }

        /// <summary>Chromium's developer tools, in their own window, pointing at what was right-clicked.</summary>
        private static void Inspect(BrowserTab t, int x, int y)
        {
            try { t.Browser.ShowDevTools(null, x, y); } catch { }
        }

        private static void Edit(BrowserTab t, Action<IFrame> act)
        {
            try { using (var f = t.Browser.GetFocusedFrame()) if (f != null) act(f); } catch { }
        }

        private void CopyText(string text, string toast)
        {
            if (string.IsNullOrEmpty(text)) return;
            try { Clipboard.SetText(text); if (toast != null) Toast(toast); } catch { Toast("Couldn't copy"); }
        }

        // ------------------------------------------------------------ find in page

        private Border findBar;
        private TextBox findBox;
        private TextBlock findCount;

        private UIElement BuildFindBar()
        {
            findBar = Ui.Box(p.Bg, 14, p.Line);
            findBar.HorizontalAlignment = HorizontalAlignment.Right; findBar.VerticalAlignment = VerticalAlignment.Top;
            findBar.Margin = new Thickness(0, 10, 18, 0); findBar.Padding = new Thickness(12, 6, 6, 6);
            findBar.Visibility = Visibility.Collapsed;
            findBar.Effect = new DropShadowEffect { BlurRadius = 16, ShadowDepth = 2, Opacity = 0.2, Color = Colors.Black, RenderingBias = RenderingBias.Performance };
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(Icons.Make("search", p.Muted, 16));
            findBox = new TextBox
            {
                Width = 210, Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = p.Ink, CaretBrush = p.Ink,
                SelectionBrush = p.Accent, FontFamily = Fonts.Sans, FontSize = 14, VerticalContentAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 8, 0)
            };
            findBox.TextChanged += (s, e) =>
            {
                if (current == null) return;
                try
                {
                    if (findBox.Text.Length == 0) { current.Browser.GetBrowserHost().StopFinding(true); findCount.Text = ""; }
                    else current.Browser.GetBrowserHost().Find(findBox.Text, true, false, false);
                }
                catch { }
            };
            findBox.KeyDown += (s, e) =>
            {
                if (e.Key == Key.Enter) { FindNext((Keyboard.Modifiers & ModifierKeys.Shift) == 0); e.Handled = true; }
            };
            row.Children.Add(findBox);
            findCount = Ui.Text("", 12.5, p.Muted, Fonts.Sans);
            findCount.VerticalAlignment = VerticalAlignment.Center; findCount.MinWidth = 64; findCount.Margin = new Thickness(0, 0, 6, 0);
            row.Children.Add(findCount);
            var up = Ui.IconButton(p, "back", 15, () => FindNext(false), 30);
            ((FrameworkElement)up.Child).LayoutTransform = new RotateTransform(90);
            var down = Ui.IconButton(p, "forward", 15, () => FindNext(true), 30);
            ((FrameworkElement)down.Child).LayoutTransform = new RotateTransform(90);
            row.Children.Add(up); row.Children.Add(down);
            row.Children.Add(Ui.IconButton(p, "close", 12, CloseFind, 30));
            findBar.Child = row;
            return findBar;
        }

        private void OpenFind()
        {
            if (current == null || current.ShowsNewTab || findBar == null) return;
            if (pages.Count > 0) ClosePages();
            if (findBar.Visibility != Visibility.Visible)
            {
                findBar.Visibility = Visibility.Visible;
                Kit.PopIn(findBar, new Point(1, 0));
            }
            findBox.Focus();
            findBox.SelectAll();
            if (findBox.Text.Length > 0) FindNext(true);
        }

        private void FindNext(bool forward)
        {
            if (current == null) return;
            if (findBar == null || findBar.Visibility != Visibility.Visible || findBox.Text.Length == 0) { OpenFind(); return; }
            try { current.Browser.GetBrowserHost().Find(findBox.Text, forward, false, true); } catch { }
        }

        private void CloseFind()
        {
            if (findBar == null || findBar.Visibility != Visibility.Visible) return;
            try { if (current != null) current.Browser.GetBrowserHost().StopFinding(true); } catch { }
            var bar = findBar;
            Kit.FadeOut(bar, () => { bar.Visibility = Visibility.Collapsed; bar.BeginAnimation(OpacityProperty, null); bar.Opacity = 1; }, false);
            if (current != null) current.Browser.Focus();
        }

        // ------------------------------------------------------------ zoom and print

        private void ZoomBy(int d)
        {
            if (current == null || current.ShowsNewTab) return;
            Toast("Zoom " + current.StepZoom(d) + "%");
        }

        private void ShowZoom()
        {
            if (current == null) return;
            var tab = current;
            int[] levels = { 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200 };
            Choose("Zoom", Array.ConvertAll(levels, z => z + "%" + (z == 100 ? " (default)" : "")), Array.IndexOf(levels, tab.Zoom), i =>
            {
                tab.SetZoom(levels[i]);
                Toast("Zoom " + levels[i] + "%");
            });
        }

        private void Print()
        {
            if (current == null || current.ShowsNewTab) return;
            try { current.Browser.Print(); } catch { Toast("Couldn't print this page"); }
        }

        // ------------------------------------------------------------ fullscreen (videos, F11)

        [StructLayout(LayoutKind.Sequential)] private struct RECT { public int L, T, R, B; }
        [StructLayout(LayoutKind.Sequential)] private struct MONITORINFO { public int Size; public RECT Monitor, Work; public uint Flags; }
        [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
        [DllImport("user32.dll")] private static extern bool GetMonitorInfo(IntPtr m, ref MONITORINFO info);

        private bool fullscreen;
        private WindowState beforeFullscreen;
        private Rect boundsBeforeFullscreen;

        private void SetFullscreen(bool on)
        {
            if (on == fullscreen) return;
            fullscreen = on;
            var chrome = WindowChrome.GetWindowChrome(this);
            if (on)
            {
                beforeFullscreen = WindowState;
                boundsBeforeFullscreen = new Rect(Left, Top, Width, Height);
                titleRowEl.Visibility = Visibility.Collapsed;
                toolbarEl.Visibility = Visibility.Collapsed;
                progress.Visibility = Visibility.Collapsed;
                CloseFind();
                if (chrome != null) { chrome.CaptionHeight = 0; chrome.ResizeBorderThickness = new Thickness(0); }
                WindowState = WindowState.Normal;
                var mi = new MONITORINFO { Size = Marshal.SizeOf(typeof(MONITORINFO)) };
                GetMonitorInfo(MonitorFromWindow(new WindowInteropHelper(this).Handle, 2), ref mi);
                var dpi = VisualTreeHelper.GetDpi(this);
                root.Margin = new Thickness(0);
                Left = mi.Monitor.L / dpi.DpiScaleX; Top = mi.Monitor.T / dpi.DpiScaleY;
                Width = (mi.Monitor.R - mi.Monitor.L) / dpi.DpiScaleX; Height = (mi.Monitor.B - mi.Monitor.T) / dpi.DpiScaleY;
                Toast("Press Esc or F11 to exit full screen");
            }
            else
            {
                titleRowEl.Visibility = Visibility.Visible;
                toolbarEl.Visibility = Visibility.Visible;
                if (chrome != null) { chrome.CaptionHeight = CaptionHeight; chrome.ResizeBorderThickness = new Thickness(6); }
                Left = boundsBeforeFullscreen.Left; Top = boundsBeforeFullscreen.Top;
                Width = boundsBeforeFullscreen.Width; Height = boundsBeforeFullscreen.Height;
                WindowState = beforeFullscreen;
                FitToState();
                RefreshToolbar();
                // leaving with Esc / F11 must also take the page (a video) out of its fullscreen mode
                try { if (current != null) current.Browser.GetMainFrame().ExecuteJavaScriptAsync("if(document.fullscreenElement)document.exitFullscreen();"); } catch { }
            }
        }

        // ------------------------------------------------------------ crash recovery

        private readonly Dictionary<BrowserTab, List<DateTime>> crashes = new Dictionary<BrowserTab, List<DateTime>>();

        /// <summary>Crashed pages reload themselves, unless a page keeps crashing (three times in a minute).</summary>
        private void Recover(BrowserTab t)
        {
            if (!tabs.Contains(t)) return;
            List<DateTime> list;
            if (!crashes.TryGetValue(t, out list)) crashes[t] = list = new List<DateTime>();
            list.RemoveAll(d => (DateTime.UtcNow - d).TotalSeconds > 60);
            list.Add(DateTime.UtcNow);
            if (list.Count > 3) { if (t == current) Toast("This page keeps crashing, so Facet stopped reloading it"); return; }
            try { t.Browser.Reload(); } catch { }
            if (t == current) Toast("The page crashed and was reloaded");
        }

        // ------------------------------------------------------------ links from other apps

        /// <summary>A second Facet started by Windows (a link clicked elsewhere) passes its link here and exits.</summary>
        private void ListenForLinks()
        {
            var thread = new System.Threading.Thread(() =>
            {
                while (true)
                {
                    try
                    {
                        using (var pipe = new System.IO.Pipes.NamedPipeServerStream(Program.PipeName, System.IO.Pipes.PipeDirection.In, 1))
                        {
                            pipe.WaitForConnection();
                            using (var r = new System.IO.StreamReader(pipe, System.Text.Encoding.UTF8))
                            {
                                string url = r.ReadToEnd();
                                Dispatcher.BeginInvoke(new Action(() => OpenFromOutside(url)));
                            }
                        }
                    }
                    catch { System.Threading.Thread.Sleep(500); }
                }
            }) { IsBackground = true, Name = "Facet links" };
            thread.Start();
        }

        private void OpenFromOutside(string url)
        {
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            if (!string.IsNullOrWhiteSpace(url)) NewTab(url.Trim(), false);
        }

        // ------------------------------------------------------------ extensions

        private void ShowExtensions()
        {
            if (pages.Count > 0 && pages[pages.Count - 1] is ExtensionsPage) return;
            PushPage(new ExtensionsPage(p, PopPage, AddExtensionFile, AddExtensionFolder, AddFromWebStore,
                () => { ClosePages(); NewTab("https://greasyfork.org/", false); },
                RemoveExtension, (e, on) => { Extensions.SetEnabled(e.Id, on); Toast(on ? e.Name + " is on for pages you open or reload" : e.Name + " is off"); }));
        }

        private void RefreshExtensionPages()
        {
            foreach (var page in pages)
            {
                var ep = page as ExtensionsPage; if (ep != null) ep.Rebuild();
                var sp = page as SettingsPage; if (sp != null) sp.Rebuild();
            }
        }

        private void RemoveExtension(ExtensionInfo e)
        {
            Choose("Remove " + e.Name + "?", new[] { "Remove", "Keep it" }, -1, i =>
            {
                if (i != 0) return;
                Extensions.Remove(e.Id);
                RefreshExtensionPages();
                Toast(e.Name + " removed");
            });
        }

        /// <summary>Runs an install off the UI thread (downloads, unzipping), then reports back.</summary>
        private void RunInstall(Func<ExtensionInfo> install)
        {
            Toast("Adding extension…");
            Task.Run(install).ContinueWith(task => Dispatcher.Invoke(() =>
            {
                if (task.IsFaulted)
                {
                    var ex = task.Exception.GetBaseException();
                    ShowAsk(new Ask { Title = "Couldn't add this extension", Message = ex is InvalidOperationException ? ex.Message : "Something went wrong: " + ex.Message, Ok = "OK", Cancel = null, Answer = (a, b) => { } });
                    return;
                }
                var info = task.Result;
                RefreshExtensionPages();
                Toast(info.Name + " added" + (info.Warning.Length > 0 ? " (some parts won't run)" : ""));
            }));
        }

        private void AddExtensionFile()
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Add an extension",
                Filter = "Extensions and userscripts (*.crx, *.zip, *.xpi, *.user.js)|*.crx;*.zip;*.xpi;*.js|All files (*.*)|*.*"
            };
            if (dlg.ShowDialog(this) != true) return;
            string path = dlg.FileName;
            RunInstall(() => Extensions.InstallFile(path));
        }

        private void AddExtensionFolder()
        {
            var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Choose an unpacked extension (the folder with manifest.json)" };
            if (dlg.ShowDialog(this) != true) return;
            string path = dlg.FolderName;
            RunInstall(() => Extensions.InstallFolder(path));
        }

        private void AddFromWebStore()
        {
            string here = current != null && (current.Url ?? "").Contains("chromewebstore.google.com") ? current.Url : "";
            Input("Add from Chrome Web Store", "Paste the extension's store link. Extensions that change pages work; background-only ones can't run.", here, false,
                link => RunInstall(() => Extensions.InstallFromWebStoreAsync(link).GetAwaiter().GetResult()));
        }

        /// <summary>A .user.js link was opened: show what it is and offer to install it.</summary>
        private async void OfferUserScript(string url)
        {
            string text;
            try { using (var c = new HttpClient()) text = await c.GetStringAsync(url); }
            catch { Toast("Couldn't download the script"); return; }
            var name = Regex.Match(text, @"//\s*@name\s+(.+)").Groups[1].Value.Trim();
            var desc = Regex.Match(text, @"//\s*@description\s+(.+)").Groups[1].Value.Trim();
            if (name.Length == 0) { Toast("That file isn't a userscript"); return; }
            ShowAsk(new Ask
            {
                Title = "Install " + name + "?",
                Message = (desc.Length > 0 ? desc + "\n\n" : "") + "From " + Urls.Host(url) + ". Userscripts can read and change the pages they run on, so only install ones you trust.",
                Ok = "Install", Cancel = "Cancel",
                Answer = (ok, _) => { if (ok) RunInstall(() => Extensions.InstallUserScript(text, Urls.Host(url))); }
            });
        }
    }
}
