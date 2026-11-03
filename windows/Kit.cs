using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Facet
{
    public class SwitchControl : Border
    {
        private readonly Ellipse knob;
        private readonly TranslateTransform shift = new TranslateTransform();
        private bool on;
        private readonly Palette pal;
        public Action<bool> Changed;

        public SwitchControl(Palette p, bool value)
        {
            on = value;
            pal = p;
            Width = 42; Height = 24; CornerRadius = new CornerRadius(12);
            Cursor = System.Windows.Input.Cursors.Hand;
            knob = new Ellipse { Width = 18, Height = 18, Fill = p.Bg, Margin = new Thickness(3), HorizontalAlignment = HorizontalAlignment.Left, RenderTransform = shift };
            Child = knob;
            Apply(p, false);
            MouseLeftButtonUp += (s, e) => { on = !on; Apply(p, true); if (Changed != null) Changed(on); e.Handled = true; };
        }

        private void Apply(Palette p, bool animate)
        {
            Background = on ? p.Accent : p.Line;
            double to = on ? 18 : 0;
            if (animate) shift.BeginAnimation(TranslateTransform.XProperty, Anim.To(to, 200));
            else shift.X = to;
        }

        public void Toggle() { on = !on; Apply(pal, true); if (Changed != null) Changed(on); }
        public bool IsOn { get { return on; } }
    }

    /// <summary>Grouped-card rows shared by the Settings and Software update pages.</summary>
    public class Kit
    {
        private readonly Palette p;
        public Kit(Palette p) { this.p = p; }

        // How the transitions stay smooth:
        //  - An element that was just built starts invisible, and its animation starts only after WPF has laid it out
        //    and drawn it once. Starting straight away let a heavy page (Settings) spend the first ~100 ms of its own
        //    animation being built, so it appeared already part-way through: a hitch at the start.
        //  - While it moves, the element is drawn from a bitmap cache, so each frame the GPU just moves and fades one
        //    picture instead of re-rendering every line of text. The cache is dropped afterwards to keep text crisp.

        private static void Smooth(UIElement e, AnimationTimeline driver, Action start, bool fresh)
        {
            // EnableClearType keeps text identical inside the cache, so nothing changes when it is dropped
            e.CacheMode = new BitmapCache { SnapsToDevicePixels = true, EnableClearType = true };
            driver.Completed += (s, a) => e.CacheMode = null;
            if (fresh) e.Dispatcher.BeginInvoke(start, DispatcherPriority.Background);
            else start();
        }

        /// <summary>Hides a fresh element until it is ready to animate.</summary>
        private static void Hold(UIElement e)
        {
            e.BeginAnimation(UIElement.OpacityProperty, null);
            e.Opacity = 0;
        }

        public static void FadeIn(UIElement e, Action done = null)
        {
            Hold(e);
            var t = new TranslateTransform(0, 12);
            e.RenderTransform = t;
            var rise = Anim.To(0, 300);
            if (done != null) rise.Completed += (s, a) => done();
            Smooth(e, rise, () =>
            {
                e.BeginAnimation(UIElement.OpacityProperty, Anim.To(1, 220));
                t.BeginAnimation(TranslateTransform.YProperty, rise);
            }, true);
        }

        /// <summary>The reverse of FadeIn: fades and sinks a little, then calls done.</summary>
        public static void FadeOut(UIElement e, Action done, bool slide = true)
        {
            var fade = Anim.To(0, 180, Anim.In);
            fade.Completed += (s, a) => done();
            Smooth(e, fade, () =>
            {
                if (slide)
                {
                    var t = new TranslateTransform(0, 0);
                    e.RenderTransform = t;
                    t.BeginAnimation(TranslateTransform.YProperty, Anim.To(8, 180, Anim.In));
                }
                e.BeginAnimation(UIElement.OpacityProperty, fade);
            }, false);
        }

        /// <summary>A sub-page arriving from the right, over the page it was opened from. done runs once it has landed.</summary>
        public static void SlideIn(UIElement e, Action done)
        {
            Hold(e);
            var t = new TranslateTransform(48, 0);
            e.RenderTransform = t;
            var slide = Anim.To(0, 300);
            slide.Completed += (s, a) => done();
            Smooth(e, slide, () =>
            {
                e.BeginAnimation(UIElement.OpacityProperty, Anim.To(1, 200));
                t.BeginAnimation(TranslateTransform.XProperty, slide);
            }, true);
        }

        /// <summary>The reverse of SlideIn: back out to the right, uncovering the page underneath.</summary>
        public static void SlideOut(UIElement e, Action done)
        {
            var t = new TranslateTransform(0, 0);
            e.RenderTransform = t;
            var slide = Anim.To(48, 220, Anim.In);
            slide.Completed += (s, a) => done();
            Smooth(e, slide, () =>
            {
                t.BeginAnimation(TranslateTransform.XProperty, slide);
                e.BeginAnimation(UIElement.OpacityProperty, Anim.To(0, 220, Anim.In));
            }, false);
        }

        /// <summary>Scales a card up from 96% while it fades in, growing out of the given corner (0..1 on each axis).</summary>
        public static void PopIn(UIElement e, Point origin)
        {
            Hold(e);
            var s = new ScaleTransform(0.96, 0.96);
            e.RenderTransform = s;
            e.RenderTransformOrigin = origin;
            var grow = Anim.To(1, 220);
            Smooth(e, grow, () =>
            {
                e.BeginAnimation(UIElement.OpacityProperty, Anim.To(1, 160));
                s.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
                s.BeginAnimation(ScaleTransform.ScaleYProperty, Anim.To(1, 220));
            }, true);
        }

        public UIElement Section(string name)
        {
            var t = Ui.Tag(name, p.Accent);
            t.Margin = new Thickness(6, 24, 0, 8);
            return t;
        }

        public StackPanel Group(out Border card)
        {
            var sp = new StackPanel();
            card = Ui.Box(p.Surface, 16, p.Line);
            card.Child = sp;
            card.ClipToBounds = true;
            return sp;
        }

        public void Add(StackPanel g, UIElement row)
        {
            if (g.Children.Count > 0)
                g.Children.Add(new Border { Height = 1, Background = p.Line, Margin = new Thickness(16, 0, 0, 0) });
            g.Children.Add(row);
        }

        private Grid Base(string icon)
        {
            var g = new Grid { Margin = new Thickness(16, 13, 16, 13) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = icon != null ? new GridLength(50) : new GridLength(0) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            if (icon != null)
            {
                var chip = Ui.Box(Ui.B(((SolidColorBrush)p.Accent).Color.ToString(), 0x22), 10);
                chip.Width = 34; chip.Height = 34; chip.HorizontalAlignment = HorizontalAlignment.Left;
                chip.Child = Icons.Make(icon, p.Accent, 19);
                g.Children.Add(chip);
            }
            return g;
        }

        private StackPanel Texts(string title, string sub)
        {
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            col.Children.Add(Ui.Text(title, 15, p.Ink, Fonts.Sans, FontWeights.Medium));
            if (!string.IsNullOrEmpty(sub))
            {
                var s = Ui.Text(sub, 12.5, p.Muted, Fonts.Sans);
                s.TextWrapping = TextWrapping.Wrap;
                s.TextTrimming = TextTrimming.None;
                s.Margin = new Thickness(0, 3, 0, 0);
                col.Children.Add(s);
            }
            Grid.SetColumn(col, 1);
            return col;
        }

        private UIElement Hoverable(Grid g, Action click)
        {
            var b = new Border { Child = g };
            Ui.Button(b, Brushes.Transparent, p.Hover, click);
            return b;
        }

        public UIElement Toggle(string icon, string title, string sub, bool value, Action<bool> onChange)
        {
            var g = Base(icon);
            g.Children.Add(Texts(title, sub));
            var sw = new SwitchControl(p, value) { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 0, 0) };
            sw.Changed = onChange;
            Grid.SetColumn(sw, 2);
            g.Children.Add(sw);
            return Hoverable(g, () => sw.Toggle());
        }

        /// <summary>One option of a set (search engine, theme, protection level): a check marks the chosen one.</summary>
        public UIElement Choice(string title, string sub, bool selected, Action click)
        {
            var g = Base(null);
            g.Children.Add(Texts(title, sub));
            if (selected)
            {
                var c = Icons.Make("check", p.Accent, 18);
                Grid.SetColumn(c, 2);
                g.Children.Add(c);
            }
            return Hoverable(g, click);
        }

        /// <summary>A clickable title / subtitle row, with an optional small action button on the right (remove, cancel).</summary>
        public UIElement Item(string title, string sub, Action click, string actionIcon = null, Action action = null)
        {
            var g = Base(null);
            g.Children.Add(Texts(title, sub));
            if (actionIcon != null && action != null)
            {
                var btn = Ui.IconButton(p, actionIcon, 14, action, 30);
                btn.VerticalAlignment = VerticalAlignment.Center;
                btn.Margin = new Thickness(10, 0, 0, 0);
                Grid.SetColumn(btn, 2);
                g.Children.Add(btn);
            }
            return Hoverable(g, click);
        }

        /// <summary>A text field inside a group (notes, names).</summary>
        public TextBox Field(string text, string hint, bool multiline)
        {
            var t = new TextBox
            {
                Text = text ?? "", Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = p.Ink, CaretBrush = p.Ink,
                SelectionBrush = p.Accent, FontFamily = Fonts.Sans, FontSize = 15, Margin = new Thickness(16, 12, 16, 12),
                AcceptsReturn = multiline, TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
                MinHeight = multiline ? 120 : 0, VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Disabled
            };
            if (!string.IsNullOrEmpty(hint)) t.ToolTip = hint;
            return t;
        }

        /// <summary>A switch row with one extra action button (the Extensions page's remove button).</summary>
        public UIElement ToggleAction(string icon, string title, string sub, bool value, Action<bool> onChange, string actionIcon, Action action)
        {
            var g = Base(icon);
            g.Children.Add(Texts(title, sub));
            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var btn = Ui.IconButton(p, actionIcon, 17, action, 32);
            btn.Margin = new Thickness(10, 0, 8, 0);
            right.Children.Add(btn);
            var sw = new SwitchControl(p, value) { VerticalAlignment = VerticalAlignment.Center };
            sw.Changed = onChange;
            right.Children.Add(sw);
            Grid.SetColumn(right, 2);
            g.Children.Add(right);
            return new Border { Child = g };
        }

        public UIElement Value(string icon, string title, string value, string sub, Action click)
        {
            var g = Base(icon);
            g.Children.Add(Texts(title, sub));
            var right = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var v = Ui.Text(value, 13.5, p.Muted, Fonts.Sans);
            v.MaxWidth = 220; v.Margin = new Thickness(10, 0, 6, 0);
            right.Children.Add(v);
            right.Children.Add(Icons.Make("forward", p.Muted, 15));
            Grid.SetColumn(right, 2);
            g.Children.Add(right);
            return Hoverable(g, click);
        }

        public UIElement Info(string label, string value)
        {
            var g = new Grid { Margin = new Thickness(16, 11, 16, 11) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(Ui.Text(label, 14, p.Muted, Fonts.Sans));
            var v = Ui.Text(value, 14, p.Ink, Fonts.Sans, FontWeights.Medium);
            v.MaxWidth = 380; v.TextAlignment = TextAlignment.Right;
            Grid.SetColumn(v, 1);
            g.Children.Add(v);
            return g;
        }

        public UIElement Note(string text)
        {
            var t = Ui.Text(text, 12.5, p.Muted, Fonts.Sans);
            t.TextWrapping = TextWrapping.Wrap; t.TextTrimming = TextTrimming.None;
            t.Margin = new Thickness(6, 12, 6, 0);
            return t;
        }

        /// <summary>Page frame: back button, serif title, scrolling column of max 640 px.</summary>
        public Grid Page(string title, Action onBack, out StackPanel body)
        {
            var root = new Grid { Background = p.Bg };
            root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(24, 18, 24, 4), HorizontalAlignment = HorizontalAlignment.Center, Width = 640 };
            head.Children.Add(Ui.IconButton(p, "back", 22, onBack, 40));
            var t = Ui.Text(title, 32, p.Ink, Fonts.Serif);
            t.Margin = new Thickness(10, 0, 0, 0);
            t.VerticalAlignment = VerticalAlignment.Center;
            head.Children.Add(t);
            root.Children.Add(head);
            body = new StackPanel { Width = 640, Margin = new Thickness(0, 0, 0, 40) };
            var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = new Grid { Children = { body }, Margin = new Thickness(24, 0, 24, 0) } };
            Scrolling.Smooth(scroll);
            body.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetRow(scroll, 1);
            root.Children.Add(scroll);
            return root;
        }
    }
}
