using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace Facet.Setup
{
    /// <summary>The Paper palette, Prism fonts and a few controls, matching the browser.</summary>
    public static class Paper
    {
        public static readonly SolidColorBrush Bg = B("#FBF6EA"), Surface = B("#F3EBDA"), Ink = B("#1E1915"), Muted = B("#7A6E62"),
            Line = B("#E2D6BF"), Accent = B("#B5532F"), AccentSoft = B("#E58A5F"), AccentDeep = B("#8F3E20"), Hover = B("#1E1915", 0x12),
            OnAccent = B("#FBF6EA"), Danger = B("#B3261E");

        private static readonly Uri FontBase = new Uri("pack://application:,,,/Fonts/");
        public static readonly FontFamily Serif = new FontFamily(FontBase, "./#Anthropic Serif Display");
        public static readonly FontFamily Sans = new FontFamily(FontBase, "./#Anthropic Sans Text");
        public static readonly FontFamily Mono = new FontFamily("Consolas");

        public static readonly IEasingFunction Out = Frozen(new QuarticEase { EasingMode = EasingMode.EaseOut });
        public static readonly IEasingFunction InOut = Frozen(new SineEase { EasingMode = EasingMode.EaseInOut });

        private static IEasingFunction Frozen(EasingFunctionBase e) { e.Freeze(); return e; }

        public static SolidColorBrush B(string hex, byte alpha = 255)
        {
            var c = (Color)ColorConverter.ConvertFromString(hex);
            c.A = alpha;
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }

        public static TextBlock Text(string s, double size, Brush color, FontFamily font, FontWeight? weight = null)
        {
            return new TextBlock
            {
                Text = s, FontSize = size, Foreground = color, FontFamily = font, FontWeight = weight ?? FontWeights.Normal,
                TextWrapping = TextWrapping.Wrap
            };
        }

        public static DoubleAnimation To(double to, int ms, IEasingFunction ease = null, int delayMs = 0)
        {
            return new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = ease ?? Out, BeginTime = TimeSpan.FromMilliseconds(delayMs) };
        }

        /// <summary>Fades and lifts an element into place, after an optional delay.</summary>
        public static void Rise(UIElement e, int delayMs, double from = 14)
        {
            var t = new TranslateTransform(0, from);
            e.RenderTransform = t;
            e.Opacity = 0;
            e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(320)) { BeginTime = TimeSpan.FromMilliseconds(delayMs), EasingFunction = Out });
            t.BeginAnimation(TranslateTransform.YProperty, To(0, 460, Out, delayMs));
        }

        /// <summary>A pill button with a hover fade and a slight press.</summary>
        public static Border Pill(string text, bool primary, Action click, bool danger = false)
        {
            Brush fill = primary ? (danger ? Danger : Accent) : Brushes.Transparent;
            var b = new Border
            {
                CornerRadius = new CornerRadius(22), Padding = new Thickness(24, 11, 24, 11), Cursor = Cursors.Hand,
                BorderBrush = primary ? null : Line, BorderThickness = new Thickness(primary ? 0 : 1),
                RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform(1, 1)
            };
            var bg = new SolidColorBrush(((SolidColorBrush)fill).Color);
            b.Background = bg;
            var label = Text(text, 14.5, primary ? OnAccent : Ink, Sans, FontWeights.Medium);
            label.TextAlignment = TextAlignment.Center;
            b.Child = label;
            Color normal = bg.Color, hover = primary ? Shade(bg.Color, 0.88) : Hover.Color;
            if (normal.A == 0) normal = Color.FromArgb(0, hover.R, hover.G, hover.B);
            bool pressed = false;
            b.MouseEnter += (s, e) => bg.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(hover, TimeSpan.FromMilliseconds(140)));
            b.MouseLeave += (s, e) => { pressed = false; bg.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(normal, TimeSpan.FromMilliseconds(200))); Press(b, 1); };
            b.MouseLeftButtonDown += (s, e) => { pressed = true; Press(b, 0.97); e.Handled = true; };
            b.MouseLeftButtonUp += (s, e) => { Press(b, 1); if (pressed) { pressed = false; click(); } e.Handled = true; };
            return b;
        }

        public static TextBlock Link(string text, Action click)
        {
            var t = Text(text, 13.5, Accent, Sans, FontWeights.Medium);
            t.Cursor = Cursors.Hand;
            t.MouseEnter += (s, e) => t.TextDecorations = TextDecorations.Underline;
            t.MouseLeave += (s, e) => t.TextDecorations = null;
            t.MouseLeftButtonUp += (s, e) => click();
            return t;
        }

        private static void Press(UIElement e, double to)
        {
            var s = (ScaleTransform)e.RenderTransform;
            s.BeginAnimation(ScaleTransform.ScaleXProperty, To(to, 120));
            s.BeginAnimation(ScaleTransform.ScaleYProperty, To(to, 120));
        }

        private static Color Shade(Color c, double k)
        {
            return Color.FromArgb(c.A, (byte)(c.R * k), (byte)(c.G * k), (byte)(c.B * k));
        }

        /// <summary>A row with a label and a paper switch.</summary>
        public static UIElement Option(string title, string sub, bool value, Action<bool> changed)
        {
            var g = new Grid { Margin = new Thickness(0, 6, 0, 6), Cursor = Cursors.Hand, Background = Brushes.Transparent };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            col.Children.Add(Text(title, 14.5, Ink, Sans, FontWeights.Medium));
            if (!string.IsNullOrEmpty(sub)) { var st = Text(sub, 12.5, Muted, Sans); st.Margin = new Thickness(0, 2, 0, 0); col.Children.Add(st); }
            g.Children.Add(col);

            bool on = value;
            var track = new Border { Width = 42, Height = 24, CornerRadius = new CornerRadius(12), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(16, 0, 0, 0) };
            var fill = new SolidColorBrush(on ? Accent.Color : Line.Color);
            track.Background = fill;
            var shift = new TranslateTransform(on ? 18 : 0, 0);
            track.Child = new Ellipse { Width = 18, Height = 18, Fill = Bg, Margin = new Thickness(3), HorizontalAlignment = HorizontalAlignment.Left, RenderTransform = shift };
            Grid.SetColumn(track, 1);
            g.Children.Add(track);
            g.MouseLeftButtonUp += (s, e) =>
            {
                on = !on;
                shift.BeginAnimation(TranslateTransform.XProperty, To(on ? 18 : 0, 220));
                fill.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(on ? Accent.Color : Line.Color, TimeSpan.FromMilliseconds(220)));
                changed(on);
            };
            return g;
        }

        /// <summary>A faint ruled-paper grid, as on Facet's new tab page.</summary>
        public static Brush RuledGrid()
        {
            var g = new DrawingGroup();
            using (var dc = g.Open())
            {
                dc.DrawRectangle(Bg, null, new Rect(0, 0, 44, 44));
                var pen = new Pen(B("#E2D6BF", 0x70), 1);
                dc.DrawLine(pen, new Point(0, 0.5), new Point(44, 0.5));
                dc.DrawLine(pen, new Point(0.5, 0), new Point(0.5, 44));
            }
            var br = new DrawingBrush(g) { TileMode = TileMode.Tile, Viewport = new Rect(0, 0, 44, 44), ViewportUnits = BrushMappingMode.Absolute };
            br.Freeze();
            return br;
        }
    }
}
