using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

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
            if (animate) shift.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(to, TimeSpan.FromMilliseconds(180)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
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

        public static void FadeIn(UIElement e)
        {
            e.Opacity = 0;
            var t = new TranslateTransform(0, 14);
            e.RenderTransform = t;
            e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(260)));
            t.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(300)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
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
            body.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetRow(scroll, 1);
            root.Children.Add(scroll);
            return root;
        }
    }
}
