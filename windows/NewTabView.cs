using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;

namespace Facet
{
    /// <summary>The page shown in an empty tab: hero, privacy counters and bookmarks, on a faint grid.</summary>
    public class NewTabView : Grid
    {
        private readonly Palette p;
        private readonly Prefs prefs;
        private readonly Action<string> open;
        private readonly StackPanel column = new StackPanel { Width = 680, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(24, 40, 24, 40) };

        public NewTabView(Palette p, Prefs prefs, Action<string> open)
        {
            this.p = p; this.prefs = prefs; this.open = open;
            Background = GridBrush();
            var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = column };
            Children.Add(scroll);
            Rebuild();
        }

        private Brush GridBrush()
        {
            var back = new DrawingGroup();
            using (var dc = back.Open())
            {
                dc.DrawRectangle(p.Bg, null, new Rect(0, 0, 48, 48));
                var pen = new Pen(Ui.B(p.Line.Color.ToString(), 0x80), 1);
                dc.DrawLine(pen, new Point(0, 0.5), new Point(48, 0.5));
                dc.DrawLine(pen, new Point(0.5, 0), new Point(0.5, 48));
            }
            var br = new DrawingBrush(back) { TileMode = TileMode.Tile, Viewport = new Rect(0, 0, 48, 48), ViewportUnits = BrushMappingMode.Absolute };
            return br;
        }

        public void Rebuild()
        {
            column.Children.Clear();
            column.Children.Add(Ui.Tag("Facet · private by default", p.Accent));

            var hero = new TextBlock { FontFamily = Fonts.Serif, FontSize = 56, Foreground = p.Ink, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 14, 0, 0) };
            hero.Inlines.Add(new System.Windows.Documents.Run("Browse, "));
            hero.Inlines.Add(new System.Windows.Documents.Run("quietly.") { FontStyle = FontStyles.Italic, Foreground = p.Accent });
            column.Children.Add(hero);

            var sub = Ui.Text("A fast, uncluttered browser. No accounts, no telemetry, no clutter.", 16, p.Muted, Fonts.Sans);
            sub.TextWrapping = TextWrapping.Wrap; sub.TextTrimming = TextTrimming.None; sub.Margin = new Thickness(0, 14, 0, 0);
            column.Children.Add(sub);

            var stats = new UniformGrid { Columns = 2, Margin = new Thickness(0, 28, 0, 0) };
            stats.Children.Add(Stat("Trackers blocked", prefs.TrackersBlocked + Stats.Blocked, new Thickness(0, 0, 6, 0)));
            stats.Children.Add(Stat("Secure page loads", prefs.SecureLoads + Stats.Secure, new Thickness(6, 0, 0, 0)));
            column.Children.Add(stats);

            var bm = Ui.Box(p.Surface, 18, p.Line);
            bm.Padding = new Thickness(20, 18, 20, 12);
            bm.Margin = new Thickness(0, 14, 0, 0);
            var bp = new StackPanel();
            bp.Children.Add(Ui.Tag("Bookmarks", p.Accent));
            if (prefs.Bookmarks.Count == 0)
            {
                var none = Ui.Text("Nothing saved yet. Use the star in the address bar.", 14, p.Muted, Fonts.Sans);
                none.Margin = new Thickness(0, 12, 0, 6);
                bp.Children.Add(none);
            }
            int shown = 0;
            foreach (var b in prefs.Bookmarks)
            {
                if (shown++ >= 8) break;
                var url = b.Url;
                var row = new Border { Padding = new Thickness(8, 9, 8, 9), CornerRadius = new CornerRadius(8), Margin = new Thickness(0, 4, 0, 0) };
                var sp = new StackPanel();
                sp.Children.Add(Ui.Text(string.IsNullOrWhiteSpace(b.Title) ? Urls.Host(b.Url) : b.Title, 14.5, p.Ink, Fonts.Sans, FontWeights.Medium));
                sp.Children.Add(Ui.Text(Urls.Host(b.Url), 12, p.Muted, Fonts.Sans));
                row.Child = sp;
                Ui.Button(row, Brushes.Transparent, p.Hover, () => open(url));
                bp.Children.Add(row);
            }
            bm.Child = bp;
            column.Children.Add(bm);

            Kit.FadeIn(column);
        }

        private UIElement Stat(string label, long value, Thickness margin)
        {
            var card = Ui.Box(p.Surface, 18, p.Line);
            card.Padding = new Thickness(20, 18, 20, 18);
            card.Margin = margin;
            var sp = new StackPanel();
            sp.Children.Add(Ui.Tag(label, p.Accent));
            var n = Ui.Text(value.ToString("N0"), 38, p.Ink, Fonts.SerifText);
            n.Margin = new Thickness(0, 10, 0, 0);
            sp.Children.Add(n);
            card.Child = sp;
            return card;
        }
    }
}
