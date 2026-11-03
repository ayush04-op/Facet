using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using Microsoft.Win32;

namespace Facet
{
    /// <summary>Paper / Paper Dark palette, the same colours as the Android app.</summary>
    public class Palette
    {
        public bool Dark, Amoled;
        public SolidColorBrush Bg, Surface, Ink, Muted, Line, Accent, Hover, OnAccent;

        /// <summary>The accent colours (the same six as Android): light-theme and dark-theme shade of each.</summary>
        public static readonly string[][] Accents =
        {
            new[] { "Terracotta", "#B5532F", "#D4693F" },
            new[] { "Ocean", "#1F6F8B", "#4FA3C2" },
            new[] { "Forest", "#3C7A4E", "#6DB584" },
            new[] { "Violet", "#6B4FBF", "#9C84E8" },
            new[] { "Rose", "#B23A5E", "#E0708F" },
            new[] { "Amber", "#B27A12", "#E0A93C" },
        };

        public static readonly string[] ThemeNames = { "Match Windows", "Paper (light)", "Paper Dark", "Paper AMOLED" };

        /// <summary>mode: 0 follow Windows, 1 Paper, 2 Paper Dark, 3 Paper AMOLED (pure black). accent: index into Accents.</summary>
        public static Palette For(int mode, int accent = 0)
        {
            bool amoled = mode == 3;
            bool dark = amoled || mode == 2 || (mode == 0 && SystemIsDark());
            var p = new Palette { Dark = dark, Amoled = amoled };
            if (amoled)
            {
                p.Bg = Ui.B("#000000"); p.Surface = Ui.B("#0D0B09"); p.Ink = Ui.B("#F3EBDA");
                p.Muted = Ui.B("#978A7A"); p.Line = Ui.B("#221C16"); p.Accent = Ui.B("#D4693F");
                p.Hover = Ui.B("#F3EBDA", 0x1A); p.OnAccent = Ui.B("#000000");
            }
            else if (dark)
            {
                p.Bg = Ui.B("#15110E"); p.Surface = Ui.B("#211B16"); p.Ink = Ui.B("#F3EBDA");
                p.Muted = Ui.B("#A09282"); p.Line = Ui.B("#3A3028"); p.Accent = Ui.B("#D4693F");
                p.Hover = Ui.B("#F3EBDA", 0x1C); p.OnAccent = Ui.B("#15110E");
            }
            else
            {
                p.Bg = Ui.B("#FBF6EA"); p.Surface = Ui.B("#F3EBDA"); p.Ink = Ui.B("#1E1915");
                p.Muted = Ui.B("#7A6E62"); p.Line = Ui.B("#E2D6BF"); p.Accent = Ui.B("#B5532F");
                p.Hover = Ui.B("#1E1915", 0x14); p.OnAccent = Ui.B("#FBF6EA");
            }
            if (accent > 0 && accent < Accents.Length) p.Accent = Ui.B(Accents[accent][dark ? 2 : 1]);
            return p;
        }

        private static bool SystemIsDark()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    var v = k?.GetValue("AppsUseLightTheme");
                    return v is int i && i == 0;
                }
            }
            catch { return false; }
        }
    }

    /// <summary>Prism Serif for headings and Prism Sans for text. Hardcoded: there is no setting to change them.</summary>
    public static class Fonts
    {
        private static readonly Uri Base = new Uri("pack://application:,,,/Fonts/");
        public static readonly FontFamily Serif = new FontFamily(Base, "./#Anthropic Serif Display");
        public static readonly FontFamily SerifText = new FontFamily(Base, "./#Anthropic Serif Text");
        public static readonly FontFamily Sans = new FontFamily(Base, "./#Anthropic Sans Text");
        public static readonly FontFamily Mono = new FontFamily("Consolas");
    }

    public static class Ui
    {
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
            var t = new TextBlock();
            t.Text = s;
            t.FontSize = size;
            t.Foreground = color;
            t.FontFamily = font;
            t.FontWeight = weight ?? FontWeights.Normal;
            t.TextTrimming = TextTrimming.CharacterEllipsis;
            return t;
        }

        public static TextBlock Tag(string s, Brush color)
        {
            var t = Text(s.ToUpperInvariant(), 10.5, color, Fonts.Mono);
            return t;
        }

        public static Border Box(Brush bg, double radius, Brush border = null, double thickness = 1)
        {
            var b = new Border();
            b.Background = bg;
            b.CornerRadius = new CornerRadius(radius);
            if (border != null) { b.BorderBrush = border; b.BorderThickness = new Thickness(thickness); }
            return b;
        }

        /// <summary>Makes a Border react to hover and click. The hover colour fades in, and the button gives a little on press.</summary>
        public static void Button(Border b, Brush normal, Brush hover, Action onClick, bool press = true)
        {
            Color h = hover is SolidColorBrush hs ? hs.Color : Colors.Transparent;
            Color n = normal is SolidColorBrush ns ? ns.Color : Colors.Transparent;
            // Fade from a see-through version of the hover colour, not from transparent white.
            if (n.A == 0) n = Color.FromArgb(0, h.R, h.G, h.B);
            var fill = new SolidColorBrush(n);
            b.Background = fill;
            b.Cursor = Cursors.Hand;
            bool pressed = false;
            b.MouseEnter += (s, e) => Anim.Color(fill, h, 120);
            b.MouseLeave += (s, e) => { pressed = false; Anim.Color(fill, n, 180); if (press) Anim.Scale(b, 1, 120); };
            // icons dip noticeably; wide rows and tabs only a touch
            b.MouseLeftButtonDown += (s, e) => { pressed = true; if (press) Anim.Scale(b, b.ActualWidth > 120 ? 0.985 : 0.92, 90); e.Handled = true; };
            b.MouseLeftButtonUp += (s, e) =>
            {
                e.Handled = true;
                if (press) Anim.Scale(b, 1, 160);
                // Only a press that started on this button counts, so a drag that ends here doesn't click it.
                if (pressed) { pressed = false; onClick(); }
            };
        }

        public static Border IconButton(Palette p, string icon, double size, Action onClick, double box = 34)
        {
            var b = Box(Brushes.Transparent, box / 2);
            b.Width = box; b.Height = box;
            b.Child = Icons.Make(icon, p.Ink, size);
            Button(b, Brushes.Transparent, p.Hover, onClick);
            return b;
        }

        public static Border PillButton(Palette p, string text, bool primary, Action onClick)
        {
            var b = Box(primary ? p.Accent : Brushes.Transparent, 22, primary ? null : p.Line);
            b.Padding = new Thickness(20, 10, 20, 10);
            b.Child = Text(text, 14, primary ? p.OnAccent : p.Ink, Fonts.Sans, FontWeights.Medium);
            ((TextBlock)b.Child).TextAlignment = TextAlignment.Center;
            Button(b, primary ? p.Accent : Brushes.Transparent, primary ? p.Accent : p.Hover, onClick);
            return b;
        }
    }

    /// <summary>Small animation helpers. Everything eases out so motion settles quickly.</summary>
    public static class Anim
    {
        // Quartic ease-out: quick to respond, long gentle settle (the curve Windows 11 and Chrome use for UI motion).
        public static readonly IEasingFunction Out = Frozen(new QuarticEase { EasingMode = EasingMode.EaseOut });
        public static readonly IEasingFunction In = Frozen(new CubicEase { EasingMode = EasingMode.EaseIn });

        private static IEasingFunction Frozen(EasingFunctionBase e) { e.Freeze(); return e; }

        [System.Runtime.InteropServices.DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr h);
        [System.Runtime.InteropServices.DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr h, IntPtr dc);
        [System.Runtime.InteropServices.DllImport("gdi32.dll")] private static extern int GetDeviceCaps(IntPtr dc, int index);

        /// <summary>The main display's refresh rate in Hz (60 when it can't be read).</summary>
        public static int RefreshRate()
        {
            try
            {
                var dc = GetDC(IntPtr.Zero);
                int hz = GetDeviceCaps(dc, 116 /* VREFRESH */);
                ReleaseDC(IntPtr.Zero, dc);
                return hz > 1 ? hz : 60;
            }
            catch { return 60; }
        }

        /// <summary>WPF animates at 60 fps unless told otherwise; match the screen so 120/144 Hz displays get every frame.</summary>
        public static void MatchDisplay()
        {
            Timeline.DesiredFrameRateProperty.OverrideMetadata(typeof(Timeline), new FrameworkPropertyMetadata { DefaultValue = RefreshRate() });
        }

        public static DoubleAnimation To(double to, int ms, IEasingFunction ease = null)
        {
            return new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = ease ?? Out };
        }

        public static void Color(SolidColorBrush b, Color to, int ms)
        {
            b.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = Out });
        }

        public static void Scale(UIElement e, double to, int ms)
        {
            var s = e.RenderTransform as ScaleTransform;
            if (s == null) { s = new ScaleTransform(1, 1); e.RenderTransform = s; e.RenderTransformOrigin = new Point(0.5, 0.5); }
            var a = To(to, ms);
            s.BeginAnimation(ScaleTransform.ScaleXProperty, a);
            s.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        public static void Opacity(UIElement e, double from, double to, int ms)
        {
            e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(from, to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = Out });
        }
    }

    /// <summary>All icons are drawn from path data on a 24x24 grid, so there are no image files.</summary>
    public static class Icons
    {
        public static string Data(string name)
        {
            switch (name)
            {
                case "back": return "M15,5 L8,12 L15,19";
                case "forward": return "M9,5 L16,12 L9,19";
                case "refresh": return "M18.5,12 A6.5,6.5 0 1 1 14.5,6.1 M17.2,2.8 L17.2,7.6 L12.4,7.6";
                case "plus": return "M12,5 L12,19 M5,12 L19,12";
                case "close": return "M6,6 L18,18 M18,6 L6,18";
                case "menu": return "M5,12 L5,12.01 M12,12 L12,12.01 M19,12 L19,12.01";
                case "lock": return "M6,11 L18,11 L18,20 L6,20 Z M8.5,11 L8.5,8.5 C8.5,4.5 15.5,4.5 15.5,8.5 L15.5,11";
                case "search": return "M11,5 A6,6 0 1 1 10.99,5 M15.5,15.5 L20,20";
                case "shield": return "M12,3 L19,6 L19,12 C19,16.5 16,19.5 12,21 C8,19.5 5,16.5 5,12 L5,6 Z";
                case "star": return "M12,3.4 L14.2,9.4 L20.5,9.6 L15.6,13.6 L17.3,19.7 L12,16.2 L6.7,19.7 L8.4,13.6 L3.5,9.6 L9.8,9.4 Z";
                case "download": return "M12,4 L12,15 M7.5,10.5 L12,15 L16.5,10.5 M5,15 L5,19.5 L19,19.5 L19,15";
                case "settings": return "M4,8 L20,8 M4,16 L20,16 M9,5.6 A2.4,2.4 0 1 1 8.99,5.6 M15,13.6 A2.4,2.4 0 1 1 14.99,13.6";
                case "info": return "M12,3.5 A8.5,8.5 0 1 1 11.99,3.5 M12,11 L12,16.5 M12,7.8 L12,7.9";
                case "trash": return "M5,7 L19,7 M7,7 L8,20 L16,20 L17,7 M10,7 L10,4 L14,4 L14,7";
                case "check": return "M5,12.5 L10,17.5 L19,7";
                case "globe": return "M12,4 A8,8 0 1 1 11.99,4 M4,12 L20,12 M12,4 C8,8 8,16 12,20 C16,16 16,8 12,4";
                case "private": return "M3.5,10 L20.5,10 M6.5,10 L8.5,4.5 L15.5,4.5 L17.5,10 M8,13.2 A2.8,2.8 0 1 1 7.99,13.2 M16,13.2 A2.8,2.8 0 1 1 15.99,13.2";
                case "link": return "M10,14 C8,12 8,10 10,8 L12,6 C14,4 17,5 18,7 M14,10 C16,12 16,14 14,16 L12,18 C10,20 7,19 6,17";
                case "device": return "M8,3 L16,3 A1.6,1.6 0 0 1 17.6,4.6 L17.6,19.4 A1.6,1.6 0 0 1 16,21 L8,21 A1.6,1.6 0 0 1 6.4,19.4 L6.4,4.6 A1.6,1.6 0 0 1 8,3 Z M10.5,17.5 L13.5,17.5";
                case "tabs": return "M4,6 L20,6 L20,18 L4,18 Z";
                case "print": return "M7,9 L7,4 L17,4 L17,9 M7,17 L5,17 L5,9 L19,9 L19,17 L17,17 M7,14 L17,14 L17,20 L7,20 Z";
                case "puzzle": return "M5,8 L9,8 C9,5 13,5 13,8 L17,8 L17,12 C20,12 20,16 17,16 L17,20 L5,20 L5,16 C8,16 8,12 5,12 Z";
                case "zoom": return "M10.5,4.5 A6,6 0 1 1 10.49,4.5 M15,15 L20,20 M8,10.5 L13,10.5 M10.5,8 L10.5,13";
                case "copy": return "M8,8 L19,8 L19,20 L8,20 Z M5,16 L5,4 L16,4";
                case "image": return "M4,5 L20,5 L20,19 L4,19 Z M4,16 L9,11 L13,15 L16,12 L20,16 M15.5,8.5 L15.5,8.51";
                case "paste": return "M9,4 L15,4 L15,7 L9,7 Z M9,5.5 L6,5.5 L6,20 L18,20 L18,5.5 L15,5.5";
                case "cut": return "M7,4 L17,20 M17,4 L7,20 M7,17 A2.5,2.5 0 1 0 7.01,17 M17,17 A2.5,2.5 0 1 0 17.01,17";
                case "minimize": return "M6,12 L18,12";
                case "maximize": return "M6.5,6.5 L17.5,6.5 L17.5,17.5 L6.5,17.5 Z";
                case "restore": return "M6,9 L15,9 L15,18 L6,18 Z M9,9 L9,6 L18,6 L18,15 L15,15";
                default: return "M12,12 L12,12.01";
            }
        }

        // parsed once and frozen: the toolbar and tab strip redraw icons often
        private static readonly System.Collections.Generic.Dictionary<string, Geometry> Parsed = new System.Collections.Generic.Dictionary<string, Geometry>();

        private static Geometry Shape(string name)
        {
            Geometry g;
            if (!Parsed.TryGetValue(name, out g))
            {
                g = Geometry.Parse(Data(name));
                g.Freeze();
                Parsed[name] = g;
            }
            return g;
        }

        /// <summary>stroke is in 24x24 grid units; 0 means the usual weight.</summary>
        public static UIElement Make(string name, Brush color, double size, double stroke = 0)
        {
            var path = new Path();
            path.Data = Shape(name);
            path.Stroke = color;
            path.StrokeThickness = stroke > 0 ? stroke : name == "menu" ? 3.2 : 1.8;
            path.StrokeStartLineCap = PenLineCap.Round;
            path.StrokeEndLineCap = PenLineCap.Round;
            path.StrokeLineJoin = PenLineJoin.Round;
            var canvas = new Canvas { Width = 24, Height = 24 };
            canvas.Children.Add(path);
            var box = new Viewbox { Width = size, Height = size, Child = canvas };
            box.HorizontalAlignment = HorizontalAlignment.Center;
            box.VerticalAlignment = VerticalAlignment.Center;
            return box;
        }
    }
}
