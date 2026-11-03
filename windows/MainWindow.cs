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
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Shell;
using System.Windows.Threading;
using CefSharp;

namespace Facet
{
    public partial class MainWindow : Window
    {
        [DllImport("dwmapi.dll")]
        private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        [DllImport("user32.dll")]
        private static extern int GetSystemMetrics(int index);

        /// <summary>Height of the tab row, which doubles as the window's title bar.</summary>
        private const double CaptionHeight = 42;

        private readonly Prefs prefs = Prefs.Load();
        private Palette p;
        private readonly List<BrowserTab> tabs = new List<BrowserTab>();
        private BrowserTab current;
        private readonly List<UIElement> pages = new List<UIElement>();

        private Grid root, host, overlay, modal, toastHost;
        private StackPanel tabStrip;
        private TextBox address;
        private ProgressBar progress;
        private Border backBtn, forwardBtn, reloadBtn, lockBox, starBox, menuBtn, shieldBox, maxBtn;
        private TextBlock shieldCount;
        private NewTabView newTabView;
        private DispatcherTimer toastTimer;
        private readonly string startUrl;

        // tab chips, so a new tab can grow in and a closed one can shrink away
        private readonly Dictionary<BrowserTab, Border> chips = new Dictionary<BrowserTab, Border>();
        private readonly HashSet<BrowserTab> closing = new HashSet<BrowserTab>();
        private BrowserTab growing;
        private int modalGen;

        public MainWindow(string startUrl)
        {
            this.startUrl = startUrl;
            Title = "Facet";
            try { Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/Facet.ico")); } catch { }
            Width = 1200; Height = 800; MinWidth = 720; MinHeight = 480;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            FontFamily = Fonts.Sans;
            UseLayoutRounding = true;
            // Sharp UI text: ClearType everywhere, including text over see-through backgrounds (the address bar),
            // where WPF would otherwise drop to greyscale.
            TextOptions.SetTextRenderingMode(this, TextRenderingMode.ClearType);
            RenderOptions.SetClearTypeHint(this, ClearTypeHint.Enabled);

            // No Windows title bar: the tab row is the title bar, like Chrome. Dragging empty space there moves
            // the window, double-clicking maximises it, and Windows still handles snapping, resizing and the shadow.
            WindowChrome.SetWindowChrome(this, new WindowChrome
            {
                CaptionHeight = CaptionHeight,
                ResizeBorderThickness = new Thickness(6),
                GlassFrameThickness = new Thickness(0, 0, 0, 1),
                CornerRadius = new CornerRadius(0),
                UseAeroCaptionButtons = false
            });
            StateChanged += (s, e) => FitToState();

            Extensions.Load();
            home = HomeConfig.Load();
            LoadProfile();
            // still marked running from last time: Facet didn't close properly (crash, power cut)
            bool crashed = prefs.Running;
            prefs.Running = true;
            prefs.Save();
            BuildChrome();

            if (!string.IsNullOrEmpty(startUrl)) { RestoreSession(); NewTab(startUrl, false); }
            else RestoreSession();

            SourceInitialized += (s, e) => ApplyTitleBar();
            SizeChanged += (s, e) => QueueRefreshTabs();
            Closing += (s, e) => { SaveState(); prefs.Running = false; prefs.Save(); };
            Loaded += (s, e) =>
            {
                bool smoke = Environment.GetEnvironmentVariable("FACET_SMOKE") == "1";
                if (smoke) RunSmoke();
                else AutoCheckUpdates();
                ListenForLinks();
                if (!smoke && !prefs.Onboarded) ShowOnboarding();
                else if (crashed && prefs.RestoreTabs) Toast("Facet didn't close properly last time. Your tabs are back.");
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
                int round = 2; // DWMWCP_ROUND: Windows 11 rounded corners
                DwmSetWindowAttribute(h, 33, ref round, 4);
            }
            catch { /* older Windows: square corners */ }
        }

        /// <summary>A maximised window without a title bar hangs over the screen edge by its frame, so pad it back in.</summary>
        private void FitToState()
        {
            bool max = WindowState == WindowState.Maximized;
            if (root != null)
            {
                if (max)
                {
                    var f = SystemParameters.WindowResizeBorderThickness;
                    double pad = GetSystemMetrics(92 /* SM_CXPADDEDBORDER */) / VisualTreeHelper.GetDpi(this).DpiScaleX;
                    root.Margin = new Thickness(f.Left + pad, f.Top + pad, f.Right + pad, f.Bottom + pad);
                }
                else root.Margin = new Thickness(0);
            }
            if (maxBtn != null) maxBtn.Child = CaptionGlyph(max ? "restore" : "maximize", p.Ink);
        }

        // Sized like Windows 11 and Chrome: 46 px wide, the full height of the tab strip, 10 px glyphs drawn with 1 px lines.
        private const double CaptionGlyphBox = 20, CaptionStroke = 1.2;

        private static UIElement CaptionGlyph(string icon, Brush color) { return Icons.Make(icon, color, CaptionGlyphBox, CaptionStroke); }

        private Border CaptionButton(string icon, Action click, bool danger = false)
        {
            var b = new Border { Width = 46, Height = CaptionHeight, VerticalAlignment = VerticalAlignment.Top, Child = CaptionGlyph(icon, p.Ink) };
            Ui.Button(b, Brushes.Transparent, danger ? Ui.B("#C42B1C") : p.Hover, click, false);
            if (danger)
            {
                b.MouseEnter += (s, e) => b.Child = CaptionGlyph(icon, Brushes.White);
                b.MouseLeave += (s, e) => b.Child = CaptionGlyph(icon, p.Ink);
            }
            WindowChrome.SetIsHitTestVisibleInChrome(b, true);
            return b;
        }

        private void BuildChrome()
        {
            p = Palette.For(prefs.Theme, prefs.Accent);
            Background = p.Bg;
            // every ScrollBar in the window gets the paper look; websites get the same via PageScript
            Resources[typeof(ScrollBar)] = Scrolling.ScrollBarStyle(p);
            Extensions.ScrollbarScript = Scrolling.PageScript(p);
            if (host != null) host.Children.Clear();
            if (IsLoaded || new WindowInteropHelper(this).Handle != IntPtr.Zero) ApplyTitleBar();

            root = new Grid { Background = p.Bg };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

            // tab strip, which is also the title bar: tabs on the left, empty space to drag, window buttons on the right
            var titleRow = titleRowEl = new Grid { Height = CaptionHeight };
            titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            tabStrip = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 8, 10, 2) };
            tabsKey = null;
            WindowChrome.SetIsHitTestVisibleInChrome(tabStrip, true);
            titleRow.Children.Add(tabStrip);
            var caption = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Top };
            caption.Children.Add(CaptionButton("minimize", () => SystemCommands.MinimizeWindow(this)));
            maxBtn = CaptionButton("maximize", () =>
            {
                if (WindowState == WindowState.Maximized) SystemCommands.RestoreWindow(this); else SystemCommands.MaximizeWindow(this);
            });
            caption.Children.Add(maxBtn);
            caption.Children.Add(CaptionButton("close", () => SystemCommands.CloseWindow(this), true));
            Grid.SetColumn(caption, 2);
            titleRow.Children.Add(caption);
            Grid.SetRow(titleRow, 0);
            root.Children.Add(titleRow);

            // toolbar
            var bar = toolbarEl = new Grid { Margin = new Thickness(10, 4, 10, 8) };
            for (int i = 0; i < 3; i++) bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            backBtn = Ui.IconButton(p, "back", 20, () => { if (current != null && current.CanBack) current.Browser.Back(); }, 36);
            forwardBtn = Ui.IconButton(p, "forward", 20, () => { if (current != null && current.CanForward) current.Browser.Forward(); }, 36);
            reloadBtn = Ui.IconButton(p, "refresh", 19, ReloadOrStop, 36);
            Grid.SetColumn(forwardBtn, 1); Grid.SetColumn(reloadBtn, 2);
            bar.Children.Add(backBtn); bar.Children.Add(forwardBtn); bar.Children.Add(reloadBtn);

            var pillLine = new SolidColorBrush(p.Line.Color);
            var pill = Ui.Box(p.Surface, 19, pillLine, 1.5);
            pill.Height = 38; pill.Margin = new Thickness(6, 0, 6, 0);
            var pg = new Grid();
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(38) });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            lockBox = new Border { Child = Icons.Make("search", p.Muted, 17), Width = 32, Height = 32, CornerRadius = new CornerRadius(16) };
            // the lock opens the site's connection, protection and permission details
            Ui.Button(lockBox, Brushes.Transparent, p.Hover, () =>
            {
                if (current == null || current.ShowsNewTab) { address.Focus(); return; }
                PushPage(new SiteInfoPage(this, Urls.Host(current.Url), (current.Url ?? "").StartsWith("https://", StringComparison.OrdinalIgnoreCase)));
            });
            lockBox.ToolTip = "Site information";
            pg.Children.Add(lockBox);
            address = new TextBox
            {
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = p.Ink, CaretBrush = p.Ink,
                FontFamily = Fonts.Sans, FontSize = 14, VerticalContentAlignment = VerticalAlignment.Center, SelectionBrush = p.Accent
            };
            address.GotKeyboardFocus += (s, e) =>
            {
                Anim.Color(pillLine, p.Accent.Color, 160);
                address.Dispatcher.BeginInvoke(new Action(() => address.SelectAll()));
            };
            address.LostKeyboardFocus += (s, e) => Anim.Color(pillLine, p.Line.Color, 200);
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
            newTabView = new NewTabView(this, new HomeActions
            {
                Open = url => { if (current != null) current.Go(url); },
                Profiles = () => PushPage(new ProfilesPage(this)),
                Command = ShowCommandBar,
                Research = ShowResearch,
                Downloads = ShowDownloads,
                Private = () => NewTab(null, true),
                Reminders = () => PushPage(new PermissionRemindersPage(this)),
                Settings = () => { OpenSettings(true); PushPage(new HomeSettingsPage(this)); },
            });
            overlay = new Grid { Visibility = Visibility.Collapsed };
            modal = new Grid { Visibility = Visibility.Collapsed };
            toastHost = new Grid { IsHitTestVisible = false };
            content.Children.Add(host);
            content.Children.Add(BuildFindBar());
            content.Children.Add(overlay);
            content.Children.Add(modal);
            content.Children.Add(toastHost);
            Grid.SetRow(content, 3);
            root.Children.Add(content);

            foreach (var t in tabs) host.Children.Add(t.Browser);
            host.Children.Add(newTabView);
            Content = root;
            FitToState();

            if (current != null) SelectTab(current);
            else RefreshTabs();
        }

        // ------------------------------------------------------------ tabs

        private BrowserTab NewTab(string url, bool isPrivate, bool select = true, bool lazy = false)
        {
            var t = new BrowserTab(prefs, isPrivate, url, lazy, isPrivate ? null : Profiles.Context(profile));
            t.Changed += () => OnTabChanged(t);
            t.PageLoaded += () => OnPageLoaded(t);
            t.ExtensionOffered += (u, n) => { if (n.EndsWith(".user.js", StringComparison.OrdinalIgnoreCase)) OfferUserScript(u); else OfferExtensionFile(u, n); };
            t.PopupRequested += u => NewTab(u, t.IsPrivate);
            t.Shortcut += Shortcut;
            t.DownloadFinished += n => Toast("Downloaded " + n);
            HookTab(t);
            tabs.Add(t);
            host.Children.Insert(0, t.Browser);
            t.Browser.Visibility = Visibility.Collapsed;
            if (IsLoaded) growing = t;
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
            QueueRefreshTabs();
        }

        private bool tabsQueued;
        private string tabsKey;

        /// <summary>A page load fires many changes in a row; redraw the tab strip once for all of them.</summary>
        private void QueueRefreshTabs()
        {
            if (tabsQueued) return;
            tabsQueued = true;
            Dispatcher.BeginInvoke(new Action(() => { tabsQueued = false; RefreshTabs(); }), DispatcherPriority.Background);
        }

        private void SelectTab(BrowserTab t)
        {
            bool switched = t != current;
            current = t;
            // Going to a tab means leaving Settings, the same as in Chrome.
            ClosePages();
            foreach (var x in tabs)
            {
                x.Browser.Visibility = x == t ? Visibility.Visible : Visibility.Collapsed;
                x.SetHidden(x != t);
            }
            t.Activate();
            if (t.ShowsNewTab)
            {
                newTabView.Rebuild();
                newTabView.Visibility = Visibility.Visible;
                if (switched && IsLoaded) Kit.FadeIn(newTabView);
            }
            else
            {
                newTabView.Visibility = Visibility.Collapsed;
                if (switched && IsLoaded) Anim.Opacity(t.Browser, 0, 1, 160);
            }
            if (switched)
            {
                CloseFind();
                // the address bar may still hold focus (and the text) from the tab we just left
                address.Text = t.Url ?? "";
                if (!t.ShowsNewTab && address.IsKeyboardFocused) t.Browser.Focus();
            }
            RefreshToolbar();
            RefreshTabs();
            if (t.ShowsNewTab) { address.Dispatcher.BeginInvoke(new Action(() => { address.Focus(); }), DispatcherPriority.Background); }
        }

        /// <summary>Shrinks the tab's chip away, then closes the tab.</summary>
        private void CloseTab(BrowserTab t)
        {
            if (!tabs.Contains(t) || closing.Contains(t)) return;
            Border chip;
            if (!IsLoaded || !chips.TryGetValue(t, out chip)) { RemoveTab(t); return; }
            closing.Add(t);
            chip.IsHitTestVisible = false;
            var shrink = Anim.To(0, 150, Anim.In);
            shrink.Completed += (s, e) => { closing.Remove(t); RemoveTab(t); };
            chip.BeginAnimation(WidthProperty, shrink);
            chip.BeginAnimation(OpacityProperty, Anim.To(0, 120, Anim.In));
        }

        private void RemoveTab(BrowserTab t)
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
            // Most tab changes (loading started/stopped, back/forward state) don't change the strip: skip the rebuild.
            var sig = new System.Text.StringBuilder();
            sig.Append((int)ActualWidth).Append('|');
            foreach (var t in tabs)
                sig.Append(t.GetHashCode()).Append(t == current ? '*' : ' ').Append(closing.Contains(t) ? 'x' : ' ')
                   .Append(t.ShowsNewTab ? "" : t.Title).Append('\n');
            string key = sig.ToString();
            if (growing == null && key == tabsKey) return;
            tabsKey = key;
            tabStrip.Children.Clear();
            chips.Clear();
            // leave room for the new-tab button, the window buttons and some empty title bar to drag
            double avail = Math.Max(300, ActualWidth - 260);
            int shown = Math.Max(1, tabs.Count - closing.Count);
            double w = Math.Max(80, Math.Min(220, avail / shown));
            foreach (var t in tabs)
            {
                var tab = t;
                if (closing.Contains(tab)) continue;
                bool sel = tab == current;
                var chip = Ui.Box(sel ? p.Surface : Brushes.Transparent, 10, sel ? p.Line : null);
                chip.Width = w; chip.Height = 32; chip.Margin = new Thickness(0, 0, 4, 0);
                chips[tab] = chip;
                if (tab == growing)
                {
                    growing = null;
                    chip.BeginAnimation(WidthProperty, new DoubleAnimation(0, w, TimeSpan.FromMilliseconds(200)) { EasingFunction = Anim.Out });
                    Anim.Opacity(chip, 0, 1, 200);
                }
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
            // shown on the taskbar and in Alt+Tab now that the window has no title bar of its own
            Title = current.ShowsNewTab || string.IsNullOrWhiteSpace(current.Title) ? "Facet" : current.Title + " - Facet";
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
            // Browsing from the address bar leaves Settings, otherwise the page loads hidden behind it.
            ClosePages();
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
            return data.Bookmarks.Exists(b => b.Url == url);
        }

        /// <summary>Bookmarks belong to the profile in use.</summary>
        private void ToggleBookmark()
        {
            if (current == null || current.ShowsNewTab) return;
            string url = current.Url;
            if (IsBookmarked(url)) { data.Bookmarks.RemoveAll(b => b.Url == url); Toast("Bookmark removed"); }
            else { data.Bookmarks.Insert(0, new Bookmark { Title = current.Title, Url = url }); Toast("Bookmarked in " + profile.Name); }
            SaveProfileData();
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
                case "find": OpenFind(); break;
                case "findnext": FindNext(true); break;
                case "print": Print(); break;
                case "zoomin": ZoomBy(1); break;
                case "zoomout": ZoomBy(-1); break;
                case "zoomreset": ZoomBy(0); break;
                case "fullscreen": SetFullscreen(!fullscreen); break;
                case "savepage": if (current != null && !current.ShowsNewTab) Download(current, current.Url); break;
                case "source": ViewSource(current); break;
                case "devtools": if (current != null && !current.ShowsNewTab) Inspect(current, 0, 0); break;
                case "command": ShowCommandBar(); break;
                case "history": ShowHistory(); break;
                case "downloads": ShowDownloads(); break;
                case "reader": ToggleReader(); break;
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
            if (asking) return; // an open question takes Enter and Esc itself
            bool ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            bool shift = (Keyboard.Modifiers & ModifierKeys.Shift) != 0;
            if (ctrl && e.Key == Key.T) { Shortcut(shift ? "private" : "newtab"); e.Handled = true; }
            else if (ctrl && e.Key == Key.W) { Shortcut("close"); e.Handled = true; }
            else if (ctrl && e.Key == Key.L) { Shortcut("address"); e.Handled = true; }
            else if (ctrl && e.Key == Key.D) { Shortcut("bookmark"); e.Handled = true; }
            else if (ctrl && e.Key == Key.Tab) { Shortcut(shift ? "prevtab" : "nexttab"); e.Handled = true; }
            else if (e.Key == Key.F5) { Shortcut("reload"); e.Handled = true; }
            else if (ctrl && e.Key == Key.F) { Shortcut("find"); e.Handled = true; }
            else if (ctrl && e.Key == Key.P) { Shortcut("print"); e.Handled = true; }
            else if (ctrl && e.Key == Key.S) { Shortcut("savepage"); e.Handled = true; }
            else if (ctrl && e.Key == Key.K) { Shortcut("command"); e.Handled = true; }
            else if (ctrl && e.Key == Key.H) { Shortcut("history"); e.Handled = true; }
            else if (ctrl && e.Key == Key.J) { Shortcut("downloads"); e.Handled = true; }
            else if (e.Key == Key.F9) { Shortcut("reader"); e.Handled = true; }
            else if (ctrl && e.Key == Key.U) { Shortcut("source"); e.Handled = true; }
            else if ((ctrl && shift && e.Key == Key.I) || e.Key == Key.F12) { Shortcut("devtools"); e.Handled = true; }
            else if (ctrl && (e.Key == Key.OemPlus || e.Key == Key.Add)) { Shortcut("zoomin"); e.Handled = true; }
            else if (ctrl && (e.Key == Key.OemMinus || e.Key == Key.Subtract)) { Shortcut("zoomout"); e.Handled = true; }
            else if (ctrl && (e.Key == Key.D0 || e.Key == Key.NumPad0)) { Shortcut("zoomreset"); e.Handled = true; }
            else if (e.Key == Key.F11) { Shortcut("fullscreen"); e.Handled = true; }
            else if (e.Key == Key.F3) { Shortcut("findnext"); e.Handled = true; }
            else if (e.Key == Key.Escape && findBar != null && findBar.Visibility == Visibility.Visible) { CloseFind(); e.Handled = true; }
            else if (e.Key == Key.Escape && fullscreen) { SetFullscreen(false); e.Handled = true; }
            else if (e.Key == Key.Escape && pages.Count > 0) { PopPage(); e.Handled = true; }
        }

        // ------------------------------------------------------------ menu, pages, dialogs

        /// <summary>The main menu: tabs, the profile, this page's tools, Research and the assistant, then Facet's pages.</summary>
        private void ShowMenu()
        {
            var items = new List<Tuple<string, string, Action>>();
            items.Add(Item("plus", "New tab\tCtrl+T", () => NewTab(null, false)));
            items.Add(Item("private", "New private tab\tCtrl+Shift+T", () => NewTab(null, true)));
            items.Add(Item("tabs", "Profile: " + profile.Name, () => PushPage(new ProfilesPage(this))));
            items.Add(Item("search", "Search everything\tCtrl+K", ShowCommandBar));
            bool page = current != null && !current.ShowsNewTab;
            if (page)
            {
                items.Add(null);
                items.Add(Item("star", (IsBookmarked(current.Url) ? "Remove bookmark" : "Add bookmark") + "\tCtrl+D", ToggleBookmark));
                items.Add(Item("copy", "Reader view\tF9", ToggleReader));
                items.Add(Item("search", "Find in page\tCtrl+F", OpenFind));
                items.Add(Item("link", "Copy link", () => CopyText(current.Url, "Link copied")));
                items.Add(Item("print", "Print\tCtrl+P", Print));
                items.Add(Item("zoom", "Zoom (" + current.Zoom + "%)", ShowZoom));
                if (!current.IsPrivate)
                {
                    items.Add(null);
                    items.Add(Item("copy", "Save to research", SaveCurrentToResearch));
                    items.Add(Item("star", "Highlight selection", () => HighlightSelection(current)));
                    if (AiConfig.Load().Enabled)
                    {
                        items.Add(Item("info", "Summarize page", () => AskAi(true)));
                        items.Add(Item("info", "Ask about this page", () => AskAi(false)));
                    }
                }
            }
            items.Add(null);
            items.Add(Item("copy", "Research", ShowResearch));
            items.Add(Item("refresh", "History\tCtrl+H", ShowHistory));
            items.Add(Item("download", "Downloads\tCtrl+J", ShowDownloads));
            items.Add(Item("puzzle", "Extensions", ShowExtensions));
            items.Add(Item("settings", "Settings", ShowSettings));
            items.Add(Item("refresh", "Software update", ShowUpdates));
            ShowPopupMenu(items, menuBtn, PlacementMode.Bottom, -250);
        }

        private void PushPage(UIElement page, bool animate = true)
        {
            bool first = pages.Count == 0;
            pages.Add(page);
            var o = overlay;
            // A sub-page slides in on top of the page it came from, which stays put underneath until it is covered.
            // (Clearing first would flash the web page through for a frame: the "blink".)
            if (first) o.Children.Clear();
            o.Children.Add(page);
            o.Visibility = Visibility.Visible;
            Action covered = () =>
            {
                if (pages.Count == 0 || pages[pages.Count - 1] != page) return;
                for (int i = o.Children.Count - 1; i >= 0; i--) if (o.Children[i] != page) o.Children.RemoveAt(i);
            };
            if (!animate) { covered(); CoverHost(); }
            else if (first) Kit.FadeIn(page, CoverHost);
            else Kit.SlideIn(page, covered);
        }

        /// <summary>Once a page fully covers the web page, hide it: Chromium stops painting behind Settings,
        /// which would otherwise compete with Settings' own animations for every frame.</summary>
        private void CoverHost()
        {
            if (pages.Count > 0) host.Visibility = Visibility.Hidden;
        }

        private void PopPage() { Dismiss(false); }

        /// <summary>Leaves Settings / Software update entirely, for example when the person goes back to browsing.</summary>
        private void ClosePages()
        {
            CloseModal();
            Dismiss(true);
        }

        private void Dismiss(bool all)
        {
            if (pages.Count == 0) return;
            var top = pages[pages.Count - 1];
            if (all) pages.Clear(); else pages.RemoveAt(pages.Count - 1);
            var o = overlay;
            o.Children.Clear();
            bool back = pages.Count > 0;
            if (back)
            {
                // the page we return to is already built: refresh its values in place, without animating it again
                o.Children.Add(pages[pages.Count - 1]);
                var s = pages[pages.Count - 1] as IRefreshable;
                if (s != null) s.Rebuild();
            }
            else
            {
                host.Visibility = Visibility.Visible; // the web page is what the leaving page fades to
                RefreshToolbar();
            }
            // The page that is leaving stays on top and ignores the mouse while it goes: a sub-page slides back
            // out to the right, the last page fades to reveal the web page.
            top.IsHitTestVisible = false;
            o.Children.Add(top);
            Action gone = () =>
            {
                o.Children.Remove(top);
                if (o == overlay && pages.Count == 0 && o.Children.Count == 0) o.Visibility = Visibility.Collapsed;
            };
            if (back) Kit.SlideOut(top, gone); else Kit.FadeOut(top, gone);
        }

        private void ShowSettings() { OpenSettings(true); }

        private void OpenSettings(bool animate)
        {
            if (pages.Count > 0 && pages[pages.Count - 1] is SettingsPage) return;
            PushPage(new SettingsPage(this), animate);
        }

        private void RefreshSettings() { RefreshPages(); }

        private void ShowUpdates()
        {
            PushPage(new UpdatesPage(p, prefs, PopPage, Input));
        }

        private async void ClearData()
        {
            try
            {
                // the profile in use: its own storage, or the main one for Personal
                var ctx = Profiles.Context(profile);
                await (ctx != null ? ctx.GetCookieManager(null) : Cef.GetGlobalCookieManager()).DeleteCookiesAsync("", "");
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
            if (modal.Visibility != Visibility.Visible) return;
            int gen = ++modalGen;
            var m = modal;
            m.IsHitTestVisible = false;
            Kit.FadeOut(m, () =>
            {
                if (gen != modalGen) return; // another dialog opened meanwhile
                m.Children.Clear();
                m.Visibility = Visibility.Collapsed;
            }, false);
        }

        private void OpenModal(Border scrim, Border card)
        {
            modalGen++;
            modal.BeginAnimation(OpacityProperty, null);
            modal.Opacity = 1;
            modal.IsHitTestVisible = true;
            modal.Children.Clear();
            modal.Children.Add(scrim);
            modal.Children.Add(card);
            modal.Visibility = Visibility.Visible;
            Anim.Opacity(scrim, 0, 1, 180);
            Kit.PopIn(card, new Point(0.5, 0.5));
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
            OpenModal(scrim, card);
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
            OpenModal(scrim, card);
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
            toastTimer.Tick += (s, e) => { toastTimer.Stop(); Kit.FadeOut(t, () => toastHost.Children.Remove(t)); };
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
                async Task<string> Eval(string js, bool promise)
                {
                    try
                    {
                        var r = promise ? await tab.Browser.EvaluateScriptAsPromiseAsync(js) : await tab.Browser.EvaluateScriptAsync(js);
                        return r.Success ? "" + (r.Result ?? "(null)") : "script error: " + r.Message;
                    }
                    catch (Exception ex) { return "exception: " + ex.Message; }
                }
                var ready = new TaskCompletionSource<bool>();
                bool seenLoad = false;
                Action hh = () => { if (tab.Loading) seenLoad = true; else if (seenLoad) ready.TrySetResult(true); };
                tab.Changed += hh;
                tab.Go("https://webauthn.io/?again=1");
                await Task.WhenAny(ready.Task, Task.Delay(35000));
                tab.Changed -= hh;
                await Task.Delay(3000);
                report.AppendLine("FEATURE PublicKeyCredential = " + await Eval("String(typeof PublicKeyCredential)", false));
                report.AppendLine("FEATURE platform authenticator available = " + await Eval("return PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable().then(function(v){return String(v)}).catch(function(e){return 'error '+e})", true));
                report.AppendLine("FEATURE conditional UI = " + await Eval("return (PublicKeyCredential.isConditionalMediationAvailable?PublicKeyCredential.isConditionalMediationAvailable():Promise.resolve(false)).then(function(v){return String(v)}).catch(function(e){return 'error '+e})", true));
                report.AppendLine("FEATURE H.264 video = [" + await Eval("document.createElement('video').canPlayType('video/mp4; codecs=\"avc1.42E01E\"')", false) + "]");
                report.AppendLine("FEATURE AAC audio = [" + await Eval("document.createElement('audio').canPlayType('audio/mp4; codecs=\"mp4a.40.2\"')", false) + "]");
                report.AppendLine("FEATURE VP9 video = [" + await Eval("document.createElement('video').canPlayType('video/webm; codecs=\"vp9\"')", false) + "]");
                report.AppendLine("FEATURE EME API = " + await Eval("String(typeof navigator.requestMediaKeySystemAccess)", false));
                report.AppendLine("FEATURE WebGL = " + await Eval("String(!!document.createElement('canvas').getContext('webgl'))", false));
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
            data.SessionUrls = urls;
            data.SessionSelected = selected;
            // add only what's new since the last save (sessions are now saved often, not just on closing)
            prefs.TrackersBlocked += Stats.Blocked - savedBlocked; savedBlocked = Stats.Blocked;
            prefs.SecureLoads += Stats.Secure - savedSecure; savedSecure = Stats.Secure;
            SaveProfileData();
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
