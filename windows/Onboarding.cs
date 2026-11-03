using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shell;
using System.Windows.Threading;

namespace Facet
{
    /// <summary>
    /// The animated welcome and quick setup from the Android app: the logo assembles itself, then a few choices
    /// (profile and your name, search engine, theme, accent and background) and a summary. Shown once; it can be
    /// opened again from About Facet.
    /// </summary>
    public class Onboarding : Grid
    {
        private readonly MainWindow w;
        private readonly IFacetHost h;
        private readonly Action finished;
        private readonly Grid stage = new Grid();
        private readonly StackPanel dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        private Border primary, back, skip;
        private int step;
        private string profileName, userName;
        private DispatcherTimer rotator;

        public Onboarding(MainWindow window, Action finished)
        {
            w = window;
            h = window;
            this.finished = finished;
            profileName = h.Profile.Name;
            userName = h.Home.Name;
            Background = h.P.Bg;
            WindowChrome.SetIsHitTestVisibleInChrome(this, false);
            Build();
            Enter(0, true);
        }

        private void Build()
        {
            Children.Clear();
            var p = h.P;
            Background = new NewTabGrid(p).Brush;
            RowDefinitions.Clear();
            RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            stage.Width = 560;
            stage.Margin = new Thickness(24, 60, 24, 10);
            Children.Add(stage);
            var foot = new Grid { Margin = new Thickness(32, 0, 32, 32), Width = 560 };
            foot.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            foot.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            foot.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            back = Ui.PillButton(p, "Back", false, () => { Save(); if (step > 0) Enter(step - 1, false); });
            skip = Ui.PillButton(p, "Skip", false, () => { Save(); End(); });
            var left = new StackPanel { Orientation = Orientation.Horizontal };
            left.Children.Add(back);
            foot.Children.Add(left);
            Grid.SetColumn(dots, 1);
            dots.VerticalAlignment = VerticalAlignment.Center;
            foot.Children.Add(dots);
            var right = new StackPanel { Orientation = Orientation.Horizontal };
            right.Children.Add(skip);
            primary = Ui.PillButton(p, "Get started", true, Next);
            primary.Margin = new Thickness(10, 0, 0, 0);
            right.Children.Add(primary);
            Grid.SetColumn(right, 2);
            foot.Children.Add(right);
            Grid.SetRow(foot, 1);
            Children.Add(foot);
        }

        private void Next() { Save(); if (step >= 5) End(); else Enter(step + 1, true); }

        private void Save()
        {
            if (profileName != null && profileName.Trim().Length > 0 && profileName.Trim() != h.Profile.Name) Profiles.Rename(h.Profile.Id, profileName.Trim());
            h.Home.Name = (userName ?? "").Trim();
            h.Home.Save();
            h.Prefs.Save();
        }

        private void End()
        {
            if (rotator != null) rotator.Stop();
            Save();
            finished();
            Kit.FadeOut(this, () => { var parent = Parent as Panel; if (parent != null) parent.Children.Remove(this); h.HomeChanged(); });
        }

        private void Enter(int newStep, bool forward)
        {
            if (rotator != null) { rotator.Stop(); rotator = null; }
            step = newStep;
            var old = stage.Children.Count > 0 ? stage.Children[0] : null;
            if (old != null) { old.IsHitTestVisible = false; var o = old; Kit.FadeOut(o, () => stage.Children.Remove(o), false); }
            var page = BuildStep(step);
            stage.Children.Add(page);
            int delay = 0;
            foreach (UIElement c in page.Children) { var x = c; var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80 + delay) }; x.Opacity = 0; t.Tick += (s, e) => { t.Stop(); Kit.FadeIn(x); }; t.Start(); delay += 70; }
            ((TextBlock)primary.Child).Text = step == 0 ? "Get started" : step == 5 ? "Start browsing" : "Continue";
            back.Visibility = step == 0 ? Visibility.Hidden : Visibility.Visible;
            skip.Visibility = step <= 4 ? Visibility.Visible : Visibility.Hidden;
            dots.Children.Clear();
            if (step >= 1 && step <= 4)
                for (int i = 1; i <= 4; i++)
                    dots.Children.Add(new Border { Width = i == step ? 22 : 8, Height = 8, CornerRadius = new CornerRadius(4), Margin = new Thickness(3, 0, 3, 0), Background = i == step ? h.P.Accent : h.P.Line });
        }

        private StackPanel BuildStep(int i)
        {
            switch (i)
            {
                case 0: return Intro();
                case 1: return ProfileStep();
                case 2: return SearchStep();
                case 3: return ThemeStep();
                case 4: return LookStep();
                default: return DoneStep();
            }
        }

        private StackPanel Column() { return new StackPanel { VerticalAlignment = VerticalAlignment.Center }; }

        private void Title(StackPanel col, string title, string sub)
        {
            var t = Ui.Text(title, 34, h.P.Ink, Fonts.Serif);
            t.TextWrapping = TextWrapping.Wrap; t.TextTrimming = TextTrimming.None;
            col.Children.Add(t);
            var s = Ui.Text(sub, 15, h.P.Muted, Fonts.Sans);
            s.TextWrapping = TextWrapping.Wrap; s.TextTrimming = TextTrimming.None; s.Margin = new Thickness(0, 10, 0, 18); s.LineHeight = 22;
            col.Children.Add(s);
        }

        private StackPanel Card(StackPanel col)
        {
            var k = new Kit(h.P);
            Border card;
            var sp = k.Group(out card);
            col.Children.Add(card);
            return sp;
        }

        private StackPanel Intro()
        {
            var col = Column();
            var logo = new LogoView(170, true, h.P.Accent.Color) { HorizontalAlignment = HorizontalAlignment.Center };
            col.Children.Add(logo);
            var hello = Ui.Tag("Welcome to", h.P.Muted);
            hello.HorizontalAlignment = HorizontalAlignment.Center; hello.Margin = new Thickness(0, 22, 0, 0);
            col.Children.Add(hello);
            var name = Ui.Text("Facet", 56, h.P.Ink, Fonts.Serif);
            name.HorizontalAlignment = HorizontalAlignment.Center;
            col.Children.Add(name);
            var phrase = Ui.Text("Private by default.", 18, h.P.Accent, Fonts.Serif);
            phrase.HorizontalAlignment = HorizontalAlignment.Center; phrase.Margin = new Thickness(0, 6, 0, 0);
            col.Children.Add(phrase);
            string[] phrases = { "Private by default.", "Fast and free of clutter.", "Made to feel like yours.", "Nothing leaves your PC." };
            int n = 0;
            rotator = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(2400) };
            rotator.Tick += (s, e) =>
            {
                n = (n + 1) % phrases.Length;
                Kit.FadeOut(phrase, () => { phrase.Text = phrases[n]; Kit.FadeIn(phrase); }, true);
            };
            rotator.Start();
            return col;
        }

        private StackPanel ProfileStep()
        {
            var col = Column();
            Title(col, "Name your profile", "A profile keeps its own tabs, bookmarks, cookies and logins. You can add more whenever you like, for example Work.");
            var k = new Kit(h.P);
            col.Children.Add(Ui.Tag("Profile name", h.P.Muted));
            var g = Card(col);
            var f = k.Field(profileName, "Profile name", false);
            f.TextChanged += (s, e) => profileName = f.Text;
            g.Children.Add(f);
            var t2 = Ui.Tag("What should we call you? (optional)", h.P.Muted);
            t2.Margin = new Thickness(0, 18, 0, 0);
            col.Children.Add(t2);
            g = Card(col);
            var u = k.Field(userName, "Your name", false);
            u.TextChanged += (s, e) => userName = u.Text;
            g.Children.Add(u);
            col.Children.Add(k.Note("Used for a greeting on the home page. It stays on this PC."));
            return col;
        }

        private StackPanel SearchStep()
        {
            var col = Column();
            Title(col, "Pick a search engine", "This is what the address bar uses. Change it any time in Settings.");
            var k = new Kit(h.P);
            var g = Card(col);
            foreach (var e in Urls.Engines)
            {
                var eng = e;
                k.Add(g, k.Choice(eng.Name, null, h.Prefs.SearchEngine == eng.Id, () => { h.Prefs.SearchEngine = eng.Id; h.Prefs.Save(); Redraw(); }));
            }
            return col;
        }

        private StackPanel ThemeStep()
        {
            var col = Column();
            Title(col, "Choose your look", "Paper is Facet's warm, quiet theme. Pick light, dark, pure black, or follow Windows.");
            var k = new Kit(h.P);
            var g = Card(col);
            for (int i = 0; i < 4; i++)
            {
                int mode = i;
                k.Add(g, k.Choice(Palette.ThemeNames[i], null, h.Prefs.Theme == i, () => { h.Prefs.Theme = mode; h.Prefs.Save(); Restyle(); }));
            }
            return col;
        }

        private StackPanel LookStep()
        {
            var col = Column();
            Title(col, "Make it yours", "Accent colour and background. All of it can change later under Home page.");
            var k = new Kit(h.P);
            var chips = new WrapPanel();
            for (int i = 0; i < Palette.Accents.Length; i++)
            {
                int a = i;
                var chip = Ui.PillButton(h.P, Palette.Accents[i][0], h.Prefs.Accent == i, () => { h.Prefs.Accent = a; h.Prefs.Save(); Restyle(); });
                chip.Margin = new Thickness(0, 0, 8, 8);
                chips.Children.Add(chip);
            }
            col.Children.Add(Ui.Tag("Accent colour", h.P.Muted));
            chips.Margin = new Thickness(0, 10, 0, 10);
            col.Children.Add(chips);
            col.Children.Add(Ui.Tag("Background", h.P.Muted));
            var g = Card(col);
            k.Add(g, k.Choice("Paper", "The soft Paper grid", h.Home.Wallpaper == HomeConfig.Paper, () => { h.Home.Wallpaper = HomeConfig.Paper; h.Home.Save(); Redraw(); }));
            for (int i = 0; i < HomeConfig.Wallpapers.Length; i++)
            {
                int wp = i;
                k.Add(g, k.Choice(HomeConfig.Wallpapers[i], null, h.Home.Wallpaper == i, () => { h.Home.Wallpaper = wp; h.Home.Save(); Redraw(); }));
            }
            g = Card(col);
            ((FrameworkElement)g.Parent).Margin = new Thickness(0, 12, 0, 0);
            k.Add(g, k.Toggle(null, "Clock on the home page", "Time and date, big and calm", h.Home.Clock, v => { h.Home.Clock = v; h.Home.Save(); }));
            return col;
        }

        private StackPanel DoneStep()
        {
            var col = Column();
            var t = Ui.Text("You're all set.", 40, h.P.Ink, Fonts.Serif);
            col.Children.Add(t);
            var k = new Kit(h.P);
            var g = Card(col);
            ((FrameworkElement)g.Parent).Margin = new Thickness(0, 18, 0, 0);
            k.Add(g, k.Info("Profile", Profiles.Current(h.Prefs).Name));
            if (h.Home.Name.Length > 0) k.Add(g, k.Info("Name", h.Home.Name));
            k.Add(g, k.Info("Search", Urls.EngineById(h.Prefs.SearchEngine).Name));
            k.Add(g, k.Info("Theme", Palette.ThemeNames[Math.Max(0, Math.Min(3, h.Prefs.Theme))]));
            k.Add(g, k.Info("Accent", Palette.Accents[Math.Max(0, Math.Min(Palette.Accents.Length - 1, h.Prefs.Accent))][0]));
            k.Add(g, k.Info("Background", h.Home.Wallpaper == HomeConfig.Paper ? "Paper" : h.Home.Wallpaper == HomeConfig.Photo ? "Your photo" : HomeConfig.Wallpapers[h.Home.Wallpaper]));
            col.Children.Add(k.Note("Everything here lives in Settings if you change your mind."));
            return col;
        }

        /// <summary>Redraws the current step in place (a choice changed).</summary>
        private void Redraw()
        {
            stage.Children.Clear();
            stage.Children.Add(BuildStep(step));
        }

        /// <summary>The theme or accent changed: restyle the window behind, and this screen with it.</summary>
        private void Restyle()
        {
            Save();
            w.ApplyThemeUnder(this);
            Build();
            stage.Children.Clear();
            stage.Children.Add(BuildStep(step));
            Enter(step, true);
        }
    }

    /// <summary>The faint ruled-paper grid behind the home page and the welcome.</summary>
    public class NewTabGrid
    {
        public readonly Brush Brush;

        public NewTabGrid(Palette p)
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
            br.Freeze();
            Brush = br;
        }
    }
}
