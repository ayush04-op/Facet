using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using CefSharp;

namespace Facet
{
    public class MainWindow : Window
    {
        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        private readonly Prefs prefs = Prefs.Load();
        private Palette p;
        private readonly List<BrowserTab> tabs = new List<BrowserTab>();
        private BrowserTab current;
        private readonly List<UIElement> pages = new List<UIElement>();

        private Grid root, host, overlay, modal, toastHost;
        private StackPanel tabStrip;
        private TextBox address;
        private ProgressBar progress;
        private Border backBtn, forwardBtn, reloadBtn, lockBox, starBox, menuBtn, shieldBox;
        private TextBlock shieldCount;
        private NewTabView newTabView;
        private DispatcherTimer toastTimer;
        private readonly string startUrl;

        public MainWindow(string startUrl)
        {
            this.startUrl = startUrl;
            Title = "Facet";
            Width = 1200; Height = 800; MinWidth = 720; MinHeight = 480;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            FontFamily = Fonts.Sans;

            BuildChrome();

            if (!string.IsNullOrEmpty(startUrl)) NewTab(startUrl, false);
            else if (prefs.RestoreTabs && prefs.SessionUrls.Count > 0)
            {
                foreach (var u in prefs.SessionUrls) NewTab(u, false, false);
                SelectTab(tabs[Math.Max(0, Math.Min(prefs.SessionSelected, tabs.Count - 1))]);
            }
            else NewTab(null, false);

            SourceInitialized += (s, e) => ApplyTitleBar();
            SizeChanged += (s, e) => RefreshTabs();
            Closing += (s, e) => SaveState();
            Loaded += (s, e) =>
            {
                if (Environment.GetEnvironmentVariable("FACET_SMOKE") == "1") RunSmoke();
                else AutoCheckUpdates();
            };
        }

        // ------------------------------------------------------------ chrome

        private void ApplyTitleBar()
        {
            try
            {
                var h = new WindowInteropHelper(this).Handle;
                int v = p.Dark ? 1 : 0;
                DwmSetWindowAttribute(h, 20, ref v, 4);
            }
            catch { /* older Windows: keep the default title bar */ }
        }

        private void BuildChrome()
        {
            p = Palette.For(prefs.Theme);
            Background = p.Bg;
            if (host != null) host.Children.Clear();
            if (IsLoaded || new WindowInteropHelper(this).Handle != IntPtr.Zero) ApplyTitleBar();

            root = new Grid { Background = p.Bg };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            // tab strip
            tabStrip = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 8, 10, 2) };
            Grid.SetRow(tabStrip, 0);
            root.Children.Add(tabStrip);

            // toolbar
            var bar = new Grid { Margin = new Thickness(10, 4, 10, 8) };
            for (int i = 0; i < 3; i++) bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            backBtn = Ui.IconButton(p, "back", 20, () => { if (current != null && current.CanBack) current.Browser.Back(); }, 36);
            forwardBtn = Ui.IconButton(p, "forward", 20, () => { if (current != null && current.CanForward) current.Browser.Forward(); }, 36);
            reloadBtn = Ui.IconButton(p, "refresh", 19, ReloadOrStop, 36);
            Grid.SetColumn(forwardBtn, 1); Grid.SetColumn(reloadBtn, 2);
            bar.Children.Add(backBtn); bar.Children.Add(forwardBtn); bar.Children.Add(reloadBtn);

            var pill = Ui.Box(p.Surface, 19, p.Line);
            pill.Height = 38; pill.Margin = new Thickness(6, 0, 6, 0);
            var pg = new Grid();
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(38) });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            lockBox = new Border { Child = Icons.Make("search", p.Muted, 17) };
            pg.Children.Add(lockBox);
            address = new TextBox
            {
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = p.Ink, CaretBrush = p.Ink,
                FontFamily = Fonts.Sans, FontSize = 14, VerticalContentAlignment = VerticalAlignment.Center, SelectionBrush = p.Accent
            };
            address.GotKeyboardFocus += (s, e) => address.Dispatcher.BeginInvoke(new Action(() => address.SelectAll()));
            address.KeyDown += (s, e) =>
            {
                if (e.Key == Key.Enter) { Navigate(address.Text); e.Handled = true; }
                else if (e.Key == Key.Escape) { RefreshToolbar(); if (current != null) current.Browser.Focus(); e.Handled = true; }
            };
            Grid.SetColumn(address, 1);
            pg.Children.Add(address);
            starBox = Ui.IconButton(p, "star", 17, ToggleBookmark, 30);
            Grid.SetColumn(starBox, 2);
            pg.Children.Add(starBox);
            shieldBox = new Border { Margin = new Thickness(4, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center, Background = Brushes.Transparent };
            var sh = new StackPanel { Orientation = Orientation.Horizontal };
            sh.Children.Add(Icons.Make("shield", p.Accent, 16));
            shieldCount = Ui.Text("0", 12.5, p.Accent, Fonts.Mono);
            shieldCount.Margin = new Thickness(5, 0, 0, 0); shieldCount.VerticalAlignment = VerticalAlignment.Center;
            sh.Children.Add(shieldCount);
            shieldBox.Child = sh;
            shieldBox.ToolTip = "Trackers blocked this session";
            Grid.SetColumn(shieldBox, 3);
            pg.Children.Add(shieldBox);
            pill.Child = pg;
            Grid.SetColumn(pill, 3);
            bar.Children.Add(pill);

            menuBtn = Ui.IconButton(p, "menu", 20, ShowMenu, 36);
            Grid.SetColumn(menuBtn, 4);
            bar.Children.Add(menuBtn);
            Grid.SetRow(bar, 1);
            root.Children.Add(bar);

            progress = new ProgressBar { Height = 2, Foreground = p.Accent, Background = Brushes.Transparent, BorderThickness = new Thickness(0), IsIndeterminate = false, Visibility = Visibility.Hidden };
            Grid.SetRow(progress, 2);
            root.Children.Add(progress);

            // content
            var content = new Grid();
            host = new Grid();
            newTabView = new NewTabView(p, prefs, url => { if (current != null) current.Go(url); });
            overlay = new Grid { Visibility = Visibility.Collapsed };
            modal = new Grid { Visibility = Visibility.Collapsed };
            toastHost = new Grid { IsHitTestVisible = false };
            content.Children.Add(host);
            content.Children.Add(overlay);
            content.Children.Add(modal);
            content.Children.Add(toastHost);
            Grid.SetRow(content, 3);
            root.Children.Add(content);

            foreach (var t in tabs) host.Children.Add(t.Browser);
            host.Children.Add(newTabView);
            Content = root;

            if (current != null) SelectTab(current);
            else RefreshTabs();
        }

        // ------------------------------------------------------------ tabs

        private BrowserTab NewTab(string url, bool isPrivate, bool select = true)
        {
            var t = new BrowserTab(prefs, isPrivate, url);
            t.Changed += () => OnTabChanged(t);
            t.PopupRequested += u => NewTab(u, t.IsPrivate);
            t.Shortcut += Shortcut;
            t.DownloadFinished += n => Toast("Downloaded " + n);
            tabs.Add(t);
            host.Children.Insert(0, t.Browser);
            t.Browser.Visibility = Visibility.Collapsed;
            if (select) SelectTab(t);
            else RefreshTabs();
            return t;
        }

        private void OnTabChanged(BrowserTab t)
        {
            if (t == current)
            {
                newTabView.Visibility = t.ShowsNewTab ? Visibility.Visible : Visibility.Collapsed;
                RefreshToolbar();
            }
            RefreshTabs();
        }

        private void SelectTab(BrowserTab t)
        {
            current = t;
            foreach (var x in tabs) x.Browser.Visibility = x == t ? Visibility.Visible : Visibility.Collapsed;
            if (t.ShowsNewTab) { newTabView.Rebuild(); newTabView.Visibility = Visibility.Visible; }
            else newTabView.Visibility = Visibility.Collapsed;
            RefreshToolbar();
            RefreshTabs();
            if (t.ShowsNewTab) { address.Dispatcher.BeginInvoke(new Action(() => { address.Focus(); }), DispatcherPriority.Background); }
        }

        private void CloseTab(BrowserTab t)
        {
            int i = tabs.IndexOf(t);
            if (i < 0) return;
            tabs.RemoveAt(i);
            host.Children.Remove(t.Browser);
            try { t.Browser.Dispose(); } catch { }
            if (tabs.Count == 0) { current = null; NewTab(null, false); return; }
            if (t == current) SelectTab(tabs[Math.Min(i, tabs.Count - 1)]);
            else RefreshTabs();
        }

        private void RefreshTabs()
        {
            if (tabStrip == null) return;
            tabStrip.Children.Clear();
            double avail = Math.Max(300, ActualWidth - 120);
            double w = Math.Max(80, Math.Min(220, avail / Math.Max(1, tabs.Count)));
            foreach (var t in tabs)
            {
                var tab = t;
                bool sel = tab == current;
                var chip = Ui.Box(sel ? p.Surface : Brushes.Transparent, 10, sel ? p.Line : null);
                chip.Width = w; chip.Height = 32; chip.Margin = new Thickness(0, 0, 4, 0);
                var g = new Grid { Margin = new Thickness(10, 0, 4, 0) };
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var ic = Icons.Make(tab.IsPrivate ? "private" : "globe", tab.IsPrivate ? p.Accent : p.Muted, 15);
                ((FrameworkElement)ic).Margin = new Thickness(0, 0, 8, 0);
                g.Children.Add(ic);
                var title = Ui.Text(tab.ShowsNewTab ? "New tab" : tab.Title, 13, sel ? p.Ink : p.Muted, Fonts.Sans, sel ? FontWeights.Medium : FontWeights.Normal);
                title.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(title, 1);
                g.Children.Add(title);
                var x = Ui.IconButton(p, "close", 12, () => CloseTab(tab), 22);
                Grid.SetColumn(x, 2);
                g.Children.Add(x);
                chip.Child = g;
                Ui.Button(chip, sel ? p.Surface : Brushes.Transparent, sel ? p.Surface : p.Hover, () => SelectTab(tab));
                chip.MouseUp += (s, e) => { if (e.ChangedButton == MouseButton.Middle) CloseTab(tab); };
                tabStrip.Children.Add(chip);
            }
            var plus = Ui.IconButton(p, "plus", 17, () => NewTab(null, false), 30);
            plus.Margin = new Thickness(2, 1, 0, 0);
            tabStrip.Children.Add(plus);
        }

        private void RefreshToolbar()
        {
            if (current == null) return;
            backBtn.Opacity = current.CanBack ? 1 : 0.35;
            forwardBtn.Opacity = current.CanForward ? 1 : 0.35;
            reloadBtn.Child = Icons.Make(current.Loading ? "close" : "refresh", p.Ink, current.Loading ? 17 : 19);
            progress.Visibility = current.Loading ? Visibility.Visible : Visibility.Hidden;
            progress.IsIndeterminate = current.Loading;
            if (!address.IsKeyboardFocused) address.Text = current.Url ?? "";
            bool secure = (current.Url ?? "").StartsWith("https://", StringComparison.OrdinalIgnoreCase);
            lockBox.Child = Icons.Make(current.ShowsNewTab ? "search" : (secure ? "lock" : "globe"), secure ? p.Accent : p.Muted, 17);
            bool saved = IsBookmarked(current.Url);
            starBox.Child = Icons.Make("star", saved ? p.Accent : p.Muted, 17);
            shieldCount.Text = Stats.Blocked.ToString();
        }

        // ------------------------------------------------------------ actions

        private void Navigate(string input)
        {
            if (current == null) return;
            var url = Urls.Resolve(input, prefs.SearchEngine);
            current.Go(url);
            current.Browser.Focus();
        }

        private void ReloadOrStop()
        {
            if (current == null) return;
            if (current.Loading) current.Browser.Stop(); else if (!current.ShowsNewTab) current.Browser.Reload();
        }

        private bool IsBookmarked(string url)
        {
            if (string.IsNullOrEmpty(url)) return false;
            return prefs.Bookmarks.Exists(b => b.Url == url);
        }

        private void ToggleBookmark()
        {
            if (current == null || current.ShowsNewTab) return;
            string url = current.Url;
            if (IsBookmarked(url)) { prefs.Bookmarks.RemoveAll(b => b.Url == url); Toast("Bookmark removed"); }
            else { prefs.Bookmarks.Insert(0, new Bookmark { Title = current.Title, Url = url }); Toast("Bookmarked"); }
            prefs.Save();
            RefreshToolbar();
        }

        private void Shortcut(string a)
        {
            switch (a)
            {
                case "newtab": NewTab(null, false); break;
                case "private": NewTab(null, true); break;
                case "close": if (current != null) CloseTab(current); break;
                case "address": address.Focus(); address.SelectAll(); break;
                case "bookmark": ToggleBookmark(); break;
                case "reload": if (current != null && !current.ShowsNewTab) current.Browser.Reload(); break;
                case "back": if (current != null && current.CanBack) current.Browser.Back(); break;
                case "forward": if (current != null && current.CanForward) current.Browser.Forward(); break;
                case "nexttab": Cycle(1); break;
                case "prevtab": Cycle(-1); break;
            }
        }

        private void Cycle(int d)
        {
            if (tabs.Count < 2 || current == null) return;
            int i = (tabs.IndexOf(current) + d + tabs.Count) % tabs.Count;
            SelectTab(tabs[i]);
        }

        protected override void OnPreviewKeyDown(KeyEventArgs e)
        {
            base.OnPreviewKeyDown(e);
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            bool shift = (Keyboard.Modifiers & ModifierKeys.Shift) != 0;
            if (ctrl && e.Key == Key.T) { Shortcut(shift ? "private" : "newtab"); e.Handled = true; }
            else if (ctrl && e.Key == Key.W) { Shortcut("close"); e.Handled = true; }
            else if (ctrl && e.Key == Key.L) { Shortcut("address"); e.Handled = true; }
            else if (ctrl && e.Key == Key.D) { Shortcut("bookmark"); e.Handled = true; }
            else if (ctrl && e.Key == Key.Tab) { Shortcut(shift ? "prevtab" : "nexttab"); e.Handled = true; }
            else if (e.Key == Key.F5) { Shortcut("reload"); e.Handled = true; }
            else if (e.Key == Key.Escape && pages.Count > 0) { PopPage(); e.Handled = true; }
        }

        // ------------------------------------------------------------ menu, pages, dialogs

        private void ShowMenu()
        {
            var pop = new Popup { PlacementTarget = menuBtn, Placement = PlacementMode.Bottom, StaysOpen = false, AllowsTransparency = true, HorizontalOffset = -200 };
            var card = Ui.Box(p.Bg, 14, p.Line);
            card.Width = 244; card.Margin = new Thickness(10);
            card.Effect = new DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Opacity = 0.25, Color = Colors.Black };
            var sp = new StackPanel { Margin = new Thickness(6) };
            Action<string, string, Action> item = (icon, text, act) =>
            {
                var row = new Border { CornerRadius = new CornerRadius(9), Padding = new Thickness(10, 9, 10, 9) };
                var g = new StackPanel { Orientation = Orientation.Horizontal };
                g.Children.Add(Icons.Make(icon, p.Ink, 18));
                var t = Ui.Text(text, 14, p.Ink, Fonts.Sans, FontWeights.Medium);
                t.Margin = new Thickness(12, 0, 0, 0); t.VerticalAlignment = VerticalAlignment.Center;
                g.Children.Add(t);
                row.Child = g;
                Ui.Button(row, Brushes.Transparent, p.Hover, () => { pop.IsOpen = false; act(); });
                sp.Children.Add(row);
            };
            item("plus", "New tab", () => NewTab(null, false));
            item("private", "New private tab", () => NewTab(null, true));
            if (current != null && !current.ShowsNewTab)
                item("star", IsBookmarked(current.Url) ? "Remove bookmark" : "Add bookmark", ToggleBookmark);
            item("download", "Downloads folder", () =>
            {
                try { System.Diagnostics.Process.Start("explorer.exe", System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads")); } catch { }
            });
            item("settings", "Settings", ShowSettings);
            item("refresh", "Software update", ShowUpdates);
            card.Child = sp;
            pop.Child = card;
            pop.IsOpen = true;
        }

        private void PushPage(UIElement page)
        {
            pages.Add(page);
            overlay.Children.Clear();
            overlay.Children.Add(page);
            overlay.Visibility = Visibility.Visible;
            Kit.FadeIn(page);
        }

        private void PopPage()
        {
            if (pages.Count == 0) return;
            pages.RemoveAt(pages.Count - 1);
            overlay.Children.Clear();
            if (pages.Count == 0) { overlay.Visibility = Visibility.Collapsed; RefreshToolbar(); return; }
            overlay.Children.Add(pages[pages.Count - 1]);
            var s = pages[pages.Count - 1] as SettingsPage;
            if (s != null) s.Rebuild();
        }

        private void ShowSettings()
        {
            PushPage(new SettingsPage(p, prefs, PopPage,
                () => { /* options are read on the next request */ },
                () => Choose("Search engine", Array.ConvertAll(Urls.Engines, e => e.Name), Array.FindIndex(Urls.Engines, e => e.Id == prefs.SearchEngine), i =>
                {
                    prefs.SearchEngine = Urls.Engines[i].Id; prefs.Save(); RefreshSettings();
                }),
                () => Choose("Theme", new[] { "Match Windows", "Paper (light)", "Paper Dark" }, prefs.Theme, i =>
                {
                    prefs.Theme = i; prefs.Save();
                    pages.Clear();
                    BuildChrome();
                    ShowSettings();
                }),
                () => Choose("Clear cookies, cache and site data?", new[] { "Clear everything", "Cancel" }, -1, i => { if (i == 0) ClearData(); }),
                ShowUpdates));
        }

        private void RefreshSettings()
        {
            var s = pages.Count > 0 ? pages[pages.Count - 1] as SettingsPage : null;
            if (s != null) s.Rebuild();
        }

        private void ShowUpdates()
        {
            PushPage(new UpdatesPage(p, prefs, PopPage, Input));
        }

        private async void ClearData()
        {
            try
            {
                await Cef.GetGlobalCookieManager().DeleteCookiesAsync("", "");
                foreach (var t in tabs)
                {
                    try { await t.Browser.GetDevToolsClient().Network.ClearBrowserCacheAsync(); } catch { }
                }
                Toast("Browsing data cleared");
            }
            catch { Toast("Couldn't clear everything"); }
            RefreshSettings();
        }

        private void CloseModal()
        {
            modal.Children.Clear();
            modal.Visibility = Visibility.Collapsed;
        }

        private Border Scrim(out Border card, double width)
        {
            var scrim = new Border { Background = Ui.B("#000000", 130) };
            scrim.MouseLeftButtonUp += (s, e) => CloseModal();
            card = Ui.Box(p.Bg, 20, p.Line);
            card.Width = width; card.Padding = new Thickness(20);
            card.HorizontalAlignment = HorizontalAlignment.Center; card.VerticalAlignment = VerticalAlignment.Center;
            card.MouseLeftButtonUp += (s, e) => e.Handled = true;
            return scrim;
        }

        private void Choose(string title, string[] options, int selected, Action<int> pick)
        {
            Border card;
            var scrim = Scrim(out card, 360);
            var sp = new StackPanel();
            sp.Children.Add(Ui.Text(title, 22, p.Ink, Fonts.Serif));
            for (int i = 0; i < options.Length; i++)
            {
                int idx = i;
                var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(12, 11, 12, 11), Margin = new Thickness(0, i == 0 ? 14 : 2, 0, 0) };
                var g = new Grid();
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                g.Children.Add(Ui.Text(options[i], 15, p.Ink, Fonts.Sans, FontWeights.Medium));
                if (i == selected) { var c = Icons.Make("check", p.Accent, 18); Grid.SetColumn(c, 1); g.Children.Add(c); }
                row.Child = g;
                Ui.Button(row, Brushes.Transparent, p.Hover, () => { CloseModal(); pick(idx); });
                sp.Children.Add(row);
            }
            card.Child = sp;
            modal.Children.Clear();
            modal.Children.Add(scrim);
            modal.Children.Add(card);
            modal.Visibility = Visibility.Visible;
            Kit.FadeIn(card);
        }

        private void Input(string title, string hint, string initial, bool secret, Action<string> save)
        {
            Border card;
            var scrim = Scrim(out card, 440);
            var sp = new StackPanel();
            sp.Children.Add(Ui.Text(title, 22, p.Ink, Fonts.Serif));
            var hintText = Ui.Text(hint, 13, p.Muted, Fonts.Sans);
            hintText.Margin = new Thickness(0, 6, 0, 0);
            sp.Children.Add(hintText);
            Control field;
            Func<string> read;
            if (secret)
            {
                var pb = new PasswordBox { Password = initial };
                field = pb; read = () => pb.Password;
            }
            else
            {
                var tb = new TextBox { Text = initial };
                field = tb; read = () => tb.Text;
            }
            field.Background = p.Surface; field.Foreground = p.Ink; field.BorderBrush = p.Line; field.FontFamily = Fonts.Sans; field.FontSize = 15;
            field.Padding = new Thickness(10, 9, 10, 9); field.Margin = new Thickness(0, 14, 0, 0);
            sp.Children.Add(field);
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 18, 0, 0) };
            buttons.Children.Add(Ui.PillButton(p, "Cancel", false, CloseModal));
            var saveBtn = Ui.PillButton(p, "Save", true, () => { var v = read(); CloseModal(); save(v); });
            saveBtn.Margin = new Thickness(10, 0, 0, 0);
            buttons.Children.Add(saveBtn);
            sp.Children.Add(buttons);
            card.Child = sp;
            modal.Children.Clear();
            modal.Children.Add(scrim);
            modal.Children.Add(card);
            modal.Visibility = Visibility.Visible;
            Kit.FadeIn(card);
            field.Dispatcher.BeginInvoke(new Action(() => field.Focus()), DispatcherPriority.Background);
        }

        private void Toast(string text)
        {
            var t = Ui.Box(p.Ink, 18);
            t.Padding = new Thickness(18, 10, 18, 10);
            t.Margin = new Thickness(0, 0, 0, 28);
            t.HorizontalAlignment = HorizontalAlignment.Center; t.VerticalAlignment = VerticalAlignment.Bottom;
            t.Child = Ui.Text(text, 13.5, p.Bg, Fonts.Sans, FontWeights.Medium);
            toastHost.Children.Clear();
            toastHost.Children.Add(t);
            Kit.FadeIn(t);
            if (toastTimer != null) toastTimer.Stop();
            toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.6) };
            toastTimer.Tick += (s, e) => { toastTimer.Stop(); toastHost.Children.Clear(); };
            toastTimer.Start();
        }


        // ------------------------------------------------------------ self test (used by CI only)

        /// <summary>Loads a few real sites and checks engine features, then writes smoke.txt and exits.</summary>
        private async void RunSmoke()
        {
            var report = new System.Text.StringBuilder();
            try
            {
                await Task.Delay(4000);
                var tab = current;
                string lastError = "";
                tab.Browser.LoadError += (s, e) => { if (e.Frame != null && e.Frame.IsMain) lastError = e.ErrorText + " (" + e.ErrorCode + ")"; };
                string[] urls =
                {
                    "https://example.com", "https://en.wikipedia.org/wiki/Main_Page", "https://github.com", "https://www.google.com",
                    "https://duckduckgo.com", "https://www.youtube.com", "https://accounts.google.com", "https://webauthn.io", "http://neverssl.com"
                };
                foreach (var url in urls)
                {
                    var done = new TaskCompletionSource<bool>();
                    bool seen = false;
                    lastError = "";
                    Action h = () => { if (tab.Loading) seen = true; else if (seen) done.TrySetResult(true); };
                    tab.Changed += h;
                    tab.Go(url);
                    await Task.WhenAny(done.Task, Task.Delay(35000));
                    tab.Changed -= h;
                    await Task.Delay(1500);
                    report.AppendLine("SITE " + url + " -> " + (done.Task.IsCompleted ? "loaded" : "TIMEOUT")
                        + " | title=\"" + tab.Title + "\" | final=" + tab.Url + (lastError.Length > 0 ? " | ERROR " + lastError : ""));
                }

                // engine features that decide whether sites and passkeys work
                async Task<string> Eval(string js)
                {
                    try
                    {
                        var r = await tab.Browser.EvaluateScriptAsPromiseAsync(js);
                        return r.Success ? Convert.ToString(r.Result) : "script error: " + r.Message;
                    }
                    catch (Exception ex) { return "exception: " + ex.Message; }
                }
                tab.Go("https://webauthn.io");
                await Task.Delay(6000);
                report.AppendLine("FEATURE PublicKeyCredential = " + await Eval("Promise.resolve(typeof PublicKeyCredential)"));
                report.AppendLine("FEATURE platform authenticator available = " + await Eval("PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable().then(v=>String(v)).catch(e=>'error '+e)"));
                report.AppendLine("FEATURE conditional UI = " + await Eval("(PublicKeyCredential.isConditionalMediationAvailable?PublicKeyCredential.isConditionalMediationAvailable():Promise.resolve(false)).then(v=>String(v)).catch(e=>'error '+e)"));
                report.AppendLine("FEATURE H.264 video = [" + await Eval("Promise.resolve(document.createElement('video').canPlayType('video/mp4; codecs=\"avc1.42E01E\"'))") + "]");
                report.AppendLine("FEATURE AAC audio = [" + await Eval("Promise.resolve(document.createElement('audio').canPlayType('audio/mp4; codecs=\"mp4a.40.2\"'))") + "]");
                report.AppendLine("FEATURE VP9 video = [" + await Eval("Promise.resolve(document.createElement('video').canPlayType('video/webm; codecs=\"vp9\"'))") + "]");
                report.AppendLine("FEATURE Widevine (EME) = " + await Eval("Promise.resolve(typeof navigator.requestMediaKeySystemAccess)"));
                report.AppendLine("FEATURE WebGL = " + await Eval("Promise.resolve(!!document.createElement('canvas').getContext('webgl'))"));
                report.AppendLine("STATS trackers blocked = " + Stats.Blocked + ", secure loads = " + Stats.Secure);
            }
            catch (Exception ex) { report.AppendLine("SMOKE CRASHED: " + ex); }
            try { System.IO.File.WriteAllText(System.IO.Path.Combine(AppInfo.DataDir, "smoke.txt"), report.ToString()); } catch { }
            Application.Current.Shutdown();
        }

        // ------------------------------------------------------------ state

        private void SaveState()
        {
            var urls = new List<string>();
            int selected = 0;
            foreach (var t in tabs)
            {
                if (t.IsPrivate || string.IsNullOrEmpty(t.Url)) continue;
                if (t == current) selected = urls.Count;
                urls.Add(t.Url);
            }
            prefs.SessionUrls = urls;
            prefs.SessionSelected = selected;
            prefs.TrackersBlocked += Stats.Blocked;
            prefs.SecureLoads += Stats.Secure;
            prefs.Save();
        }

        private void AutoCheckUpdates()
        {
            if (!prefs.AutoCheckUpdates) return;
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (now - prefs.LastUpdateCheck < 12L * 3600 * 1000) return;
            Task.Run(async () =>
            {
                try
                {
                    var r = await Updater.LatestAsync(prefs.UpdateRepo, prefs.UpdateToken);
                    prefs.LastUpdateCheck = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    prefs.AvailableUpdate = r != null && Updater.IsNewer(r.Tag, AppInfo.Version) ? r.Tag : "";
                    prefs.Save();
                }
                catch { /* stay quiet: the updates page shows errors when asked */ }
            });
        }
    }
}
