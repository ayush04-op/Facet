using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Facet
{
    public class HomeActions
    {
        public Action<string> Open;
        public Action Profiles, Command, Research, Downloads, Private, Reminders, Settings;
    }

    /// <summary>
    /// The home page (an empty tab), as on Android: profile and command-bar chips, clock, date and weather, a greeting
    /// or the shimmering "Browse, quietly." headline, protection counters, a permission reminder, four quick tiles
    /// (Research, Downloads, Private tab, Command bar) and bookmarks, over the Paper grid, a wallpaper or your photo.
    /// </summary>
    public class NewTabView : Grid
    {
        private readonly IFacetHost h;
        private readonly HomeActions a;
        private readonly StackPanel column = new StackPanel { Width = 680, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(24, 34, 24, 40) };
        private readonly Grid backdrop = new Grid();
        private DispatcherTimer clock;
        private static string wallKey;
        private static ImageSource wallCache;

        public NewTabView(IFacetHost host, HomeActions actions)
        {
            h = host; a = actions;
            ClipToBounds = true;
            Children.Add(backdrop);
            var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = column };
            Scrolling.Smooth(scroll);
            Children.Add(scroll);
            Rebuild();
            IsVisibleChanged += (s, e) => { if (clock != null) { if (IsVisible) clock.Start(); else clock.Stop(); } };
        }

        public void Rebuild()
        {
            var p = h.P;
            var cfg = h.Home;
            bool priv = h.Current != null && h.Current.IsPrivate;
            BuildBackdrop(p, cfg);
            column.Children.Clear();
            if (clock != null) clock.Stop();

            // chips: profile and command bar
            var chips = new StackPanel { Orientation = Orientation.Horizontal };
            chips.Children.Add(Chip(p, "tabs", priv ? "Private" : h.Profile.Name, true, a.Profiles));
            var cmd = Chip(p, "search", "Search everything", false, a.Command);
            cmd.Margin = new Thickness(8, 0, 0, 0);
            chips.Children.Add(cmd);
            column.Children.Add(chips);

            bool compact = cfg.Clock && !priv;
            if (compact) column.Children.Add(ClockBlock(p, cfg));

            string greeting = !priv && cfg.Name.Length > 0 ? Greeting(cfg.Name) : null;
            var l1 = Ui.Text(priv ? "Nothing here will" : greeting ?? "Browse,", compact ? 34 : 46, p.Ink, Fonts.Serif);
            l1.TextWrapping = TextWrapping.Wrap; l1.TextTrimming = TextTrimming.None; l1.Margin = new Thickness(0, compact ? 14 : 18, 0, 0);
            column.Children.Add(l1);
            if (!(compact && greeting != null)) column.Children.Add(Shimmer(p, priv ? "follow you." : "quietly.", compact ? 34 : 46));

            string sub = priv ? "No history is saved for this tab, and its data is wiped when the last private tab closes."
                : h.Profile.Kind == Profile.Research ? "Research profile. Tabs, notes and highlights for this topic stay together, on this PC."
                : h.Profile.Kind == Profile.Temporary ? "Temporary session. Everything here is erased when you leave."
                : "Fast, private, and free of clutter.";
            var s = Ui.Text(sub, 16, p.Muted, Fonts.Sans);
            s.TextWrapping = TextWrapping.Wrap; s.TextTrimming = TextTrimming.None; s.Margin = new Thickness(0, 10, 0, 0);
            column.Children.Add(s);

            if (!priv)
            {
                if (cfg.Stats)
                {
                    var row = new Grid { Margin = new Thickness(0, 20, 0, 0) };
                    row.ColumnDefinitions.Add(new ColumnDefinition());
                    row.ColumnDefinitions.Add(new ColumnDefinition());
                    var c1 = Stat(p, "Trackers blocked", h.Prefs.TrackersBlocked + Stats.Blocked, "and counting");
                    c1.Margin = new Thickness(0, 0, 6, 0);
                    var c2 = Stat(p, "Secure page loads", h.Prefs.SecureLoads + Stats.Secure, "over HTTPS");
                    c2.Margin = new Thickness(6, 0, 0, 0);
                    Grid.SetColumn(c2, 1);
                    row.Children.Add(c1); row.Children.Add(c2);
                    column.Children.Add(row);
                }

                int reminders = SitePerms.Reminders().Select(x => x.Host).Distinct().Count();
                if (reminders > 0)
                {
                    var rem = Bento(p);
                    var sp = new StackPanel();
                    sp.Children.Add(Ui.Tag("Permission reminder", p.Accent));
                    var t = Ui.Text(reminders == 1 ? "1 site can still use your camera, microphone or location." : reminders + " sites can still use your camera, microphone or location.", 18, p.Ink, Fonts.Serif);
                    t.TextWrapping = TextWrapping.Wrap; t.TextTrimming = TextTrimming.None; t.Margin = new Thickness(0, 10, 0, 0);
                    sp.Children.Add(t);
                    var go = Ui.Text("Review", 14, p.Accent, Fonts.Sans, FontWeights.Medium);
                    go.Margin = new Thickness(0, 10, 0, 0);
                    sp.Children.Add(go);
                    rem.Child = sp;
                    Ui.Button(rem, p.Surface, p.Hover, a.Reminders);
                    rem.Margin = new Thickness(0, 12, 0, 0);
                    column.Children.Add(rem);
                }

                // quick actions: four tiles in one row
                var tiles = new UniformGrid4();
                tiles.Add(Tile(p, "copy", "Research", a.Research));
                tiles.Add(Tile(p, "download", "Downloads", a.Downloads));
                tiles.Add(Tile(p, "private", "Private tab", a.Private));
                tiles.Add(Tile(p, "search", "Command bar", a.Command));
                tiles.Panel.Margin = new Thickness(0, 12, 0, 0);
                column.Children.Add(tiles.Panel);

                if (cfg.Bookmarks)
                {
                    var bm = Bento(p);
                    var sp = new StackPanel();
                    sp.Children.Add(Ui.Tag("Bookmarks · " + h.Profile.Name, p.Muted));
                    if (h.Data.Bookmarks.Count == 0)
                    {
                        var e = Ui.Text("Bookmark a page from the menu and it will live here.", 15, p.Muted, Fonts.Sans);
                        e.Margin = new Thickness(0, 10, 0, 0);
                        sp.Children.Add(e);
                    }
                    foreach (var b in h.Data.Bookmarks.Take(8)) sp.Children.Add(BookmarkRow(p, b));
                    bm.Child = sp;
                    bm.Margin = new Thickness(0, 12, 0, 0);
                    column.Children.Add(bm);
                }
            }
            var foot = Ui.Tag("Facet v" + AppInfo.Version, p.Muted);
            foot.Opacity = 0.7; foot.Margin = new Thickness(2, 14, 0, 0);
            column.Children.Add(foot);

            // the column's lines rise in one after another
            int i = 0;
            foreach (UIElement c in column.Children)
            {
                var el = c;
                el.Opacity = 0;
                var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(60 + 70 * i++) };
                t.Tick += (o, e) => { t.Stop(); Kit.FadeIn(el); };
                t.Start();
            }
        }

        // ---------------------------------------------------------------- background

        private void BuildBackdrop(Palette p, HomeConfig cfg)
        {
            backdrop.Children.Clear();
            backdrop.Background = p.Bg;
            if (cfg.Wallpaper != HomeConfig.Paper)
            {
                var img = Wallpaper(cfg, p.Dark);
                if (img != null)
                {
                    backdrop.Children.Add(new Image { Source = img, Stretch = Stretch.UniformToFill, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center });
                    byte dim = p.Amoled ? Math.Max(cfg.DimAlpha, (byte)0xB3) : cfg.DimAlpha;
                    if (dim > 0) backdrop.Children.Add(new Border { Background = new SolidColorBrush(Color.FromArgb(dim, 0, 0, 0)) });
                }
            }
            if (cfg.Wallpaper == HomeConfig.Paper || cfg.Aurora) backdrop.Children.Add(Aurora(p, cfg.Wallpaper != HomeConfig.Paper ? 0.6 : 1));
            if (cfg.Wallpaper == HomeConfig.Paper) backdrop.Children.Add(new Border { Background = new NewTabGrid(p).Brush, Opacity = 0.6 });
        }

        /// <summary>Slow drifting colour: three soft blobs, animated at a low frame rate so it costs next to nothing.</summary>
        private static UIElement Aurora(Palette p, double strength)
        {
            var g = new Grid { IsHitTestVisible = false, Opacity = 0 };
            var c = p.Accent.Color;
            Color[] cols = { c, p.Dark ? Color.FromRgb(0x5E, 0x7C, 0x8C) : Color.FromRgb(0xA9, 0xC7, 0xD2), p.Dark ? Color.FromRgb(0xB8, 0x86, 0x2F) : Color.FromRgb(0xF0, 0xCB, 0x8A) };
            double[][] spots = { new[] { 0.15, 0.1, 700.0 }, new[] { 0.9, 0.45, 620.0 }, new[] { 0.35, 0.95, 680.0 } };
            for (int i = 0; i < 3; i++)
            {
                var e = new Ellipse
                {
                    Width = spots[i][2], Height = spots[i][2], HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                    Fill = new RadialGradientBrush(Color.FromArgb((byte)(p.Dark ? 0x40 : 0x38), cols[i].R, cols[i].G, cols[i].B), Color.FromArgb(0, cols[i].R, cols[i].G, cols[i].B))
                };
                var t = new TranslateTransform();
                e.RenderTransform = t;
                g.Children.Add(e);
                int k = i;
                g.SizeChanged += (s, ev) => { e.Margin = new Thickness(g.ActualWidth * spots[k][0] - spots[k][2] / 2, g.ActualHeight * spots[k][1] - spots[k][2] / 2, 0, 0); };
                var dx = new DoubleAnimation(-60, 60, TimeSpan.FromSeconds(18 + 5 * i)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
                var dy = new DoubleAnimation(-40, 40, TimeSpan.FromSeconds(23 + 4 * i)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
                Timeline.SetDesiredFrameRate(dx, 20); Timeline.SetDesiredFrameRate(dy, 20);
                t.BeginAnimation(TranslateTransform.XProperty, dx);
                t.BeginAnimation(TranslateTransform.YProperty, dy);
            }
            g.CacheMode = null;
            g.BeginAnimation(OpacityProperty, new DoubleAnimation(0, strength, TimeSpan.FromMilliseconds(1200)));
            return g;
        }

        /// <summary>Your photo, or one of the generated wallpapers (Facet ships no images), made once and reused.</summary>
        private static ImageSource Wallpaper(HomeConfig cfg, bool dark)
        {
            string key = cfg.Wallpaper + "/" + dark + "/" + cfg.PhotoStamp;
            if (key == wallKey && wallCache != null) return wallCache;
            ImageSource img = null;
            if (cfg.Wallpaper == HomeConfig.Photo && File.Exists(HomeConfig.PhotoPath))
            {
                try
                {
                    var b = new BitmapImage();
                    b.BeginInit(); b.UriSource = new Uri(HomeConfig.PhotoPath); b.CacheOption = BitmapCacheOption.OnLoad; b.CreateOptions = BitmapCreateOptions.IgnoreImageCache; b.EndInit();
                    b.Freeze();
                    img = b;
                }
                catch { }
            }
            if (img == null) img = Generated(cfg.Wallpaper == HomeConfig.Photo ? 0 : cfg.Wallpaper, dark);
            wallKey = key; wallCache = img;
            return img;
        }

        private static ImageSource Generated(int id, bool dark)
        {
            // base colour and three soft blobs, the same recipes as the Android wallpapers
            string[][] light = { new[] { "#FBF6EA", "#E9A07A", "#F0CB8A", "#A9C7D2" }, new[] { "#F6EEF8", "#C8A2E8", "#F2B27A", "#B7A8E8" }, new[] { "#EAF4F6", "#8CCBD6", "#8FB1E8", "#BFE3E0" }, new[] { "#EFF5E8", "#A6D3A0", "#D8DE9A", "#9CCFB5" }, new[] { "#F1F1EF", "#CFCFCB", "#E2E2DE", "#B9B9B5" } };
            string[][] darkSet = { new[] { "#15110E", "#B5532F", "#B8862F", "#5E7C8C" }, new[] { "#16101F", "#7A3FBF", "#E0703C", "#3A2A7A" }, new[] { "#0B1620", "#1F7A8C", "#2B5BA8", "#10424F" }, new[] { "#0E1710", "#2F7D4A", "#8A9A2F", "#1B4D32" }, new[] { "#121212", "#4A4A4A", "#2A2A2A", "#5A5A5A" } };
            var set = (dark ? darkSet : light)[Math.Max(0, Math.Min(4, id))];
            int w = 1600, h = 1000;
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRectangle(Ui.B(set[0]), null, new Rect(0, 0, w, h));
                double[][] spots = { new[] { 0.2, 0.18, 0.95 }, new[] { 0.88, 0.5, 0.8 }, new[] { 0.3, 0.88, 0.9 } };
                for (int i = 0; i < 3; i++)
                {
                    var col = (Color)ColorConverter.ConvertFromString(set[i + 1]);
                    double r = w * spots[i][2];
                    var brush = new RadialGradientBrush(Color.FromArgb((byte)(dark ? 0xB0 : 0xA0), col.R, col.G, col.B), Color.FromArgb(0, col.R, col.G, col.B));
                    dc.DrawEllipse(brush, null, new Point(w * spots[i][0], h * spots[i][1]), r, r);
                }
            }
            var bmp = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(dv);
            bmp.Freeze();
            return bmp;
        }

        // ---------------------------------------------------------------- pieces

        private static string Greeting(string name)
        {
            int hour = DateTime.Now.Hour;
            string part = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
            return part + ", " + name;
        }

        private UIElement ClockBlock(Palette p, HomeConfig cfg)
        {
            var box = new StackPanel { Margin = new Thickness(0, 20, 0, 0) };
            var time = Ui.Text("", 68, p.Ink, Fonts.Serif);
            box.Children.Add(time);
            TextBlock date = null, weather = null;
            if (cfg.Date) { date = Ui.Text("", 16, p.Muted, Fonts.Sans); box.Children.Add(date); }
            if (cfg.Weather) { weather = Ui.Text("", 15, p.Muted, Fonts.Sans); weather.Margin = new Thickness(0, 6, 0, 0); box.Children.Add(weather); }
            bool twelve = cfg.HourFormat == 1 || (cfg.HourFormat == 0 && CultureInfo.CurrentCulture.DateTimeFormat.ShortTimePattern.Contains("h"));
            Action tick = () =>
            {
                var now = DateTime.Now;
                time.Text = now.ToString(twelve ? "h:mm" : "HH:mm");
                if (date != null) date.Text = now.ToString("dddd, d MMMM");
            };
            tick();
            clock = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            clock.Tick += (s, e) => tick();
            clock.Start();
            if (weather != null)
            {
                Action<WeatherInfo> show = i => weather.Text = i == null ? (cfg.City.Length == 0 ? "Choose a place for the weather in Settings, Home page" : "") : i.Temp + "°" + (i.Fahrenheit ? "F" : "C") + " · " + i.Words + " · " + i.Place;
                show(Weather.Cached());
                Weather.RefreshAsync(cfg).ContinueWith(t => Dispatcher.BeginInvoke(new Action(() => show(t.Result))));
            }
            return box;
        }

        /// <summary>The second headline line, with a soft band of light passing through it.</summary>
        private static UIElement Shimmer(Palette p, string text, double size)
        {
            var t = Ui.Text(text, size, p.Accent, Fonts.Serif);
            t.FontStyle = FontStyles.Italic;
            var light = p.Dark ? Color.FromRgb(0xFF, 0xD9, 0xB8) : Color.FromRgb(0xE9, 0xA0, 0x7A);
            var shift = new TranslateTransform(-1, 0);
            var brush = new LinearGradientBrush(new GradientStopCollection
            {
                new GradientStop(p.Accent.Color, 0.35), new GradientStop(light, 0.5), new GradientStop(p.Accent.Color, 0.65)
            }, 0) { RelativeTransform = shift };
            t.Foreground = brush;
            var anim = new DoubleAnimation(-1, 1, TimeSpan.FromSeconds(3.2)) { RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
            Timeline.SetDesiredFrameRate(anim, 30);
            shift.BeginAnimation(TranslateTransform.XProperty, anim);
            return t;
        }

        private static Border Bento(Palette p)
        {
            var b = Ui.Box(p.Surface, 18, p.Line);
            b.Padding = new Thickness(18, 16, 18, 16);
            return b;
        }

        private static Border Chip(Palette p, string icon, string label, bool strong, Action click)
        {
            var b = Ui.Box(strong ? p.Accent : p.Surface, 18, strong ? null : p.Line);
            b.Padding = new Thickness(12, 7, 14, 7);
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(Icons.Make(icon, strong ? p.OnAccent : p.Ink, 16));
            var t = Ui.Text(label, 13.5, strong ? p.OnAccent : p.Ink, Fonts.Sans, FontWeights.Medium);
            t.Margin = new Thickness(8, 0, 0, 0); t.MaxWidth = 180; t.VerticalAlignment = VerticalAlignment.Center;
            row.Children.Add(t);
            b.Child = row;
            Ui.Button(b, strong ? p.Accent : p.Surface, strong ? p.Accent : p.Hover, click);
            return b;
        }

        private static Border Stat(Palette p, string label, long value, string caption)
        {
            var b = Bento(p);
            var sp = new StackPanel();
            sp.Children.Add(Ui.Tag(label, p.Muted));
            var num = Ui.Text("0", 38, p.Ink, Fonts.Serif);
            num.Margin = new Thickness(0, 8, 0, 0);
            sp.Children.Add(num);
            sp.Children.Add(Ui.Text(caption, 13, p.Muted, Fonts.Sans));
            b.Child = sp;
            // the number counts up, as on Android
            if (value > 0)
            {
                var start = DateTime.Now.AddMilliseconds(500);
                var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(30) };
                t.Tick += (s, e) =>
                {
                    double k = Math.Max(0, Math.Min(1, (DateTime.Now - start).TotalMilliseconds / 1100));
                    k = 1 - Math.Pow(1 - k, 3);
                    num.Text = ((long)(value * k)).ToString("N0");
                    if (k >= 1) t.Stop();
                };
                t.Start();
            }
            return b;
        }

        private static Border Tile(Palette p, string icon, string title, Action click)
        {
            var b = Bento(p);
            b.Padding = new Thickness(6, 16, 6, 14);
            var sp = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
            var ic = Icons.Make(icon, p.Accent, 24);
            sp.Children.Add(ic);
            var t = Ui.Text(title, 13, p.Ink, Fonts.Sans, FontWeights.Medium);
            t.Margin = new Thickness(0, 8, 0, 0); t.TextAlignment = TextAlignment.Center; t.HorizontalAlignment = HorizontalAlignment.Center;
            sp.Children.Add(t);
            b.Child = sp;
            Ui.Button(b, p.Surface, p.Hover, click);
            return b;
        }

        private UIElement BookmarkRow(Palette p, Bookmark bm)
        {
            string label = string.IsNullOrWhiteSpace(bm.Title) ? Urls.Host(bm.Url) : bm.Title;
            var row = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(4, 8, 8, 8), Margin = new Thickness(0, 6, 0, 0) };
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var letter = Ui.Box(Ui.B(p.Accent.Color.ToString(), 0x24), 18);
            letter.Width = 36; letter.Height = 36;
            var lt = Ui.Text(label.Substring(0, 1).ToUpperInvariant(), 16, p.Accent, Fonts.SerifText);
            lt.HorizontalAlignment = HorizontalAlignment.Center; lt.VerticalAlignment = VerticalAlignment.Center;
            letter.Child = lt;
            g.Children.Add(letter);
            var texts = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            texts.Children.Add(Ui.Text(label, 15, p.Ink, Fonts.Sans, FontWeights.Medium));
            texts.Children.Add(Ui.Text(Urls.Host(bm.Url), 12, p.Muted, Fonts.Sans));
            Grid.SetColumn(texts, 1);
            g.Children.Add(texts);
            row.Child = g;
            Ui.Button(row, Brushes.Transparent, p.Hover, () => a.Open(bm.Url));
            return row;
        }

        /// <summary>Four equal tiles in a row with gaps between them.</summary>
        private class UniformGrid4
        {
            public readonly Grid Panel = new Grid();
            private int n;
            public UniformGrid4() { for (int i = 0; i < 4; i++) Panel.ColumnDefinitions.Add(new ColumnDefinition()); }
            public void Add(UIElement e)
            {
                ((FrameworkElement)e).Margin = new Thickness(n == 0 ? 0 : 4, 0, n == 3 ? 0 : 4, 0);
                Grid.SetColumn(e, n++);
                Panel.Children.Add(e);
            }
        }
    }
}
