using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Shell;
using Path = System.Windows.Shapes.Path;
using System.Windows.Threading;

namespace Facet.Setup
{
    public enum Mode { Install, Update, Uninstall }

    /// <summary>The paper-themed setup window: a cube that assembles itself on the left, the steps on the right.</summary>
    public class InstallerWindow : Window
    {
        [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        private readonly Mode mode;
        private string dir;
        private bool desktopShortcut = true, launchAfter = true, deleteData;
        private bool busy;

        private readonly Grid stage = new Grid();
        private readonly Canvas cube = new Canvas { Width = 150, Height = 150 };
        private readonly Path[] faces = new Path[3];
        private readonly TranslateTransform floatT = new TranslateTransform();
        private Ellipse shadow;
        private Border closeBtn;

        private Border progressFill, progressTrack, sheen;
        private TextBlock statusText, percentText;
        private double shownFraction;

        public InstallerWindow(Mode mode, string dir)
        {
            this.mode = mode;
            this.dir = dir;
            Title = mode == Mode.Uninstall ? "Remove Facet" : "Facet Setup";
            Width = 800; Height = 520; ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Background = Paper.Bg;
            FontFamily = Paper.Sans;
            UseLayoutRounding = true;
            TextOptions.SetTextRenderingMode(this, TextRenderingMode.ClearType);
            RenderOptions.SetClearTypeHint(this, ClearTypeHint.Enabled);
            try { Icon = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/Facet.ico")); } catch { }
            // no Windows title bar; the whole top strip drags the window
            WindowChrome.SetWindowChrome(this, new WindowChrome { CaptionHeight = 44, GlassFrameThickness = new Thickness(0), ResizeBorderThickness = new Thickness(0), CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false });
            SourceInitialized += (s, e) =>
            {
                try { int round = 2; DwmSetWindowAttribute(new WindowInteropHelper(this).Handle, 33, ref round, 4); } catch { }
            };
            Closing += (s, e) => { if (busy) e.Cancel = true; };

            Content = Build();
            Loaded += (s, e) =>
            {
                AssembleCube();
                if (mode == Mode.Update) StartWork();
                else ShowStep(mode == Mode.Uninstall ? UninstallWelcome() : Welcome());
            };
        }

        // ------------------------------------------------------------------ frame

        private UIElement Build()
        {
            var root = new Grid();
            root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(300) });
            root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            // left: the brand panel on ruled paper
            var left = new Grid { Background = Paper.RuledGrid() };
            left.Children.Add(new Border
            {
                Background = new LinearGradientBrush(Color.FromArgb(0, 0xF3, 0xEB, 0xDA), Color.FromArgb(0xFF, 0xF3, 0xEB, 0xDA), 90)
            });
            left.Children.Add(new Border { Width = 1, Background = Paper.Line, HorizontalAlignment = HorizontalAlignment.Right });
            var brand = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            var cubeHost = new Grid { Width = 170, Height = 190 };
            shadow = new Ellipse
            {
                Width = 96, Height = 14, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 8),
                Fill = new RadialGradientBrush(Color.FromArgb(0x55, 0x8F, 0x3E, 0x20), Color.FromArgb(0, 0x8F, 0x3E, 0x20)),
                RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform(1, 1), Opacity = 0
            };
            cubeHost.Children.Add(shadow);
            cube.RenderTransform = floatT;
            cube.VerticalAlignment = VerticalAlignment.Top;
            cube.Margin = new Thickness(0, 10, 0, 0);
            for (int i = 0; i < 3; i++)
            {
                faces[i] = new Path
                {
                    Data = Geometry.Parse(Paper.CubeFaces[i]), Fill = Paper.CubeColors[i], Opacity = 0,
                    RenderTransform = new TranslateTransform(), Stretch = Stretch.None
                };
                // the cube spans 48 x 56 of its 108-unit canvas: scale it up and centre it in the 150 px stage
                var fit = new TransformGroup();
                fit.Children.Add(new ScaleTransform(2.0, 2.0));
                fit.Children.Add(new TranslateTransform(75 - 54 * 2.0, 75 - 54 * 2.0));
                var holder = new Canvas { RenderTransform = fit };
                holder.Children.Add(faces[i]);
                cube.Children.Add(holder);
            }
            cubeHost.Children.Add(cube);
            brand.Children.Add(cubeHost);
            var name = Paper.Text("Facet", 44, Paper.Ink, Paper.Serif);
            name.HorizontalAlignment = HorizontalAlignment.Center; name.Margin = new Thickness(0, 6, 0, 0);
            brand.Children.Add(name);
            var ver = Paper.Text(("version " + Setup.Version).ToUpperInvariant(), 11, Paper.Accent, Paper.Mono);
            ver.HorizontalAlignment = HorizontalAlignment.Center; ver.Margin = new Thickness(0, 4, 0, 0);
            brand.Children.Add(ver);
            var tag = Paper.Text("A fast, private,\nuncluttered browser.", 14, Paper.Muted, Paper.Sans);
            tag.TextAlignment = TextAlignment.Center; tag.HorizontalAlignment = HorizontalAlignment.Center; tag.Margin = new Thickness(0, 14, 0, 0);
            brand.Children.Add(tag);
            Paper.Rise(name, 650); Paper.Rise(ver, 760); Paper.Rise(tag, 860);
            left.Children.Add(brand);
            root.Children.Add(left);

            // right: the current step
            stage.Margin = new Thickness(44, 52, 44, 36);
            Grid.SetColumn(stage, 1);
            root.Children.Add(stage);

            // close button, top right
            closeBtn = new Border { Width = 46, Height = 40, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Cursor = Cursors.Hand, Background = Brushes.Transparent };
            var x = new Path { Data = Geometry.Parse("M0,0 L10,10 M10,0 L0,10"), Stroke = Paper.Ink, StrokeThickness = 1.1, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            closeBtn.Child = x;
            var closeBg = new SolidColorBrush(Color.FromArgb(0, 0xC4, 0x2B, 0x1C));
            closeBtn.Background = closeBg;
            closeBtn.MouseEnter += (s, e) => { closeBg.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Color.FromArgb(0xFF, 0xC4, 0x2B, 0x1C), TimeSpan.FromMilliseconds(120))); x.Stroke = Brushes.White; };
            closeBtn.MouseLeave += (s, e) => { closeBg.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(Color.FromArgb(0, 0xC4, 0x2B, 0x1C), TimeSpan.FromMilliseconds(180))); x.Stroke = Paper.Ink; };
            closeBtn.MouseLeftButtonUp += (s, e) => { if (!busy) Close(); };
            WindowChrome.SetIsHitTestVisibleInChrome(closeBtn, true);
            Grid.SetColumnSpan(closeBtn, 2);
            root.Children.Add(closeBtn);
            return root;
        }

        // ------------------------------------------------------------------ the cube

        /// <summary>The three faces slide together into the Facet cube, then it floats.</summary>
        private void AssembleCube()
        {
            var from = new[] { new Vector(0, -34), new Vector(-30, 18), new Vector(30, 18) };
            for (int i = 0; i < 3; i++)
            {
                var t = (TranslateTransform)faces[i].RenderTransform;
                t.X = from[i].X; t.Y = from[i].Y;
                int delay = 120 + i * 130;
                faces[i].BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(380)) { BeginTime = TimeSpan.FromMilliseconds(delay) });
                t.BeginAnimation(TranslateTransform.XProperty, Paper.To(0, 900, Paper.Out, delay));
                t.BeginAnimation(TranslateTransform.YProperty, Paper.To(0, 900, Paper.Out, delay));
            }
            shadow.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(700)) { BeginTime = TimeSpan.FromMilliseconds(500) });
            // gentle float, shadow breathing with it
            var bob = new DoubleAnimation(0, -7, TimeSpan.FromMilliseconds(2400)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Paper.InOut, BeginTime = TimeSpan.FromMilliseconds(1100) };
            floatT.BeginAnimation(TranslateTransform.YProperty, bob);
            var squash = new DoubleAnimation(1, 0.86, TimeSpan.FromMilliseconds(2400)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Paper.InOut, BeginTime = TimeSpan.FromMilliseconds(1100) };
            ((ScaleTransform)shadow.RenderTransform).BeginAnimation(ScaleTransform.ScaleXProperty, squash);
        }

        /// <summary>While working, light runs across the faces one after another.</summary>
        private void PulseCube(bool on)
        {
            for (int i = 0; i < 3; i++)
            {
                if (!on) { faces[i].BeginAnimation(OpacityProperty, Paper.To(1, 300)); continue; }
                faces[i].BeginAnimation(OpacityProperty, new DoubleAnimation(1, 0.55, TimeSpan.FromMilliseconds(600))
                {
                    AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Paper.InOut, BeginTime = TimeSpan.FromMilliseconds(i * 260)
                });
            }
        }

        /// <summary>After uninstalling, the faces drift apart and fade.</summary>
        private void ScatterCube()
        {
            floatT.BeginAnimation(TranslateTransform.YProperty, null);
            var to = new[] { new Vector(0, -40), new Vector(-38, 26), new Vector(38, 26) };
            for (int i = 0; i < 3; i++)
            {
                var t = (TranslateTransform)faces[i].RenderTransform;
                t.BeginAnimation(TranslateTransform.XProperty, Paper.To(to[i].X, 900, Paper.Out, i * 90));
                t.BeginAnimation(TranslateTransform.YProperty, Paper.To(to[i].Y, 900, Paper.Out, i * 90));
                faces[i].BeginAnimation(OpacityProperty, Paper.To(0.12, 900, Paper.Out, i * 90));
            }
            shadow.BeginAnimation(OpacityProperty, Paper.To(0, 600));
        }

        // ------------------------------------------------------------------ steps

        /// <summary>The old step slides away, the new one's lines rise in one after another.</summary>
        private void ShowStep(Panel step)
        {
            var old = stage.Children.Count > 0 ? stage.Children[stage.Children.Count - 1] : null;
            if (old != null)
            {
                old.IsHitTestVisible = false;
                var t = new TranslateTransform();
                old.RenderTransform = t;
                old.CacheMode = new BitmapCache { EnableClearType = true };
                var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(200)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn } };
                fade.Completed += (s, e) => stage.Children.Remove(old);
                old.BeginAnimation(OpacityProperty, fade);
                t.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(-28, TimeSpan.FromMilliseconds(200)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn } });
            }
            stage.Children.Add(step);
            int delay = old != null ? 140 : 380;
            var sp = step as StepPanel;
            var lines = new System.Collections.Generic.List<UIElement>();
            foreach (UIElement child in (sp != null ? sp.Lines : step).Children) lines.Add(child);
            if (sp != null && sp.Footer != null) lines.Add(sp.Footer);
            foreach (var line in lines) { Paper.Rise(line, delay); delay += 55; }
        }

        private static TextBlock TagLine(string s)
        {
            var t = Paper.Text(s.ToUpperInvariant(), 11, Paper.Accent, Paper.Mono);
            t.Margin = new Thickness(0, 0, 0, 8);
            return t;
        }

        private static TextBlock Heading(string s)
        {
            var t = Paper.Text(s, 32, Paper.Ink, Paper.Serif);
            t.Margin = new Thickness(0, 0, 0, 10);
            return t;
        }

        private static TextBlock Body(string s)
        {
            var t = Paper.Text(s, 14.5, Paper.Muted, Paper.Sans);
            t.LineHeight = 22;
            return t;
        }

        private Grid Footer(string note, params Border[] buttons)
        {
            var g = new Grid { VerticalAlignment = VerticalAlignment.Bottom };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            if (note != null)
            {
                var n = Paper.Text(note, 12, Paper.Muted, Paper.Sans);
                n.VerticalAlignment = VerticalAlignment.Center; n.Margin = new Thickness(0, 0, 16, 0);
                g.Children.Add(n);
            }
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            for (int i = 0; i < buttons.Length; i++) { if (i > 0) buttons[i].Margin = new Thickness(10, 0, 0, 0); row.Children.Add(buttons[i]); }
            Grid.SetColumn(row, 1);
            g.Children.Add(row);
            return g;
        }

        /// <summary>Steps are a column with the footer pinned to the bottom.</summary>
        private static Grid Column(out StackPanel top)
        {
            var g = new Grid();
            g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            top = new StackPanel();
            g.Children.Add(top);
            return g;
        }

        private static void AddFooter(Grid col, UIElement footer)
        {
            Grid.SetRow(footer, 1);
            col.Children.Add(footer);
        }

        private Panel Welcome()
        {
            string existing = Setup.InstalledDir();
            bool update = existing != null;
            if (update) dir = existing;
            desktopShortcut = !update || File.Exists(Setup.DesktopLink());

            StackPanel top;
            var col = Column(out top);
            var step = new StackPanel();
            step.Children.Add(TagLine(update ? "Update" : "Setup"));
            step.Children.Add(Heading(update ? "Update Facet" : "Install Facet"));
            step.Children.Add(Body(update
                ? "A newer Facet is ready to replace the one on this PC. Your tabs, bookmarks, extensions and settings stay as they are."
                : "Tracker blocking, HTTPS-only browsing, extensions and the Paper themes, with Chromium built in. Facet collects nothing."));

            var loc = new Border { Background = Paper.Surface, BorderBrush = Paper.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(14), Padding = new Thickness(16, 12, 16, 12), Margin = new Thickness(0, 22, 0, 10) };
            var lg = new Grid();
            lg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            lg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var lcol = new StackPanel();
            lcol.Children.Add(Paper.Text("Install location", 12, Paper.Muted, Paper.Sans));
            var path = Paper.Text(dir, 13.5, Paper.Ink, Paper.Sans);
            path.TextWrapping = TextWrapping.NoWrap; path.TextTrimming = TextTrimming.CharacterEllipsis; path.Margin = new Thickness(0, 3, 0, 0); path.ToolTip = dir;
            lcol.Children.Add(path);
            lg.Children.Add(lcol);
            if (!update)
            {
                var change = Paper.Link("Change", () =>
                {
                    using (var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = "Choose where to install Facet", SelectedPath = Directory.Exists(dir) ? dir : System.IO.Path.GetDirectoryName(dir), ShowNewFolderButton = true })
                    {
                        if (dlg.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;
                        dir = Setup.SafeDir(dlg.SelectedPath);
                        path.Text = dir; path.ToolTip = dir;
                    }
                });
                change.VerticalAlignment = VerticalAlignment.Center; change.Margin = new Thickness(14, 0, 0, 0);
                Grid.SetColumn(change, 1);
                lg.Children.Add(change);
            }
            loc.Child = lg;
            step.Children.Add(loc);
            step.Children.Add(Paper.Option("Desktop shortcut", null, desktopShortcut, v => desktopShortcut = v));
            step.Children.Add(Paper.Option("Open Facet when setup finishes", null, launchAfter, v => launchAfter = v));
            top.Children.Add(step);
            AddFooter(col, Footer("Installs for you only. No administrator rights needed.", Paper.Pill(update ? "Update" : "Install", true, StartWork)));
            return Flatten(col, step);
        }

        private Panel UninstallWelcome()
        {
            StackPanel top;
            var col = Column(out top);
            var step = new StackPanel();
            step.Children.Add(TagLine("Uninstall"));
            step.Children.Add(Heading("Remove Facet?"));
            step.Children.Add(Body("Facet, its Start menu and desktop shortcuts, and its place in Default apps will be removed from this PC."));
            var opt = Paper.Option("Also delete my browsing data", "Bookmarks, saved tabs, cookies, extensions and settings", false, v => deleteData = v);
            ((FrameworkElement)opt).Margin = new Thickness(0, 22, 0, 0);
            step.Children.Add(opt);
            top.Children.Add(step);
            AddFooter(col, Footer(null, Paper.Pill("Keep Facet", false, Close), Paper.Pill("Remove", true, StartWork, true)));
            return Flatten(col, step);
        }

        private static Panel Flatten(Grid col, StackPanel step) { return new StepPanel(col, step); }

        /// <summary>A step laid out as a column with a pinned footer; ShowStep animates its lines, then the footer.</summary>
        private class StepPanel : Grid
        {
            public readonly StackPanel Lines;
            public readonly UIElement Footer;

            public StepPanel(Grid col, StackPanel lines)
            {
                Children.Add(col);
                Lines = lines;
                foreach (UIElement c in col.Children) if (GetRow(c) == 1) Footer = c;
            }
        }

        // ------------------------------------------------------------------ work

        private Panel ProgressStep(string tag, string title)
        {
            var step = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            step.Children.Add(TagLine(tag));
            step.Children.Add(Heading(title));
            statusText = Body("Getting ready");
            step.Children.Add(statusText);

            var bar = new Grid { Margin = new Thickness(0, 26, 0, 0) };
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(56) });
            progressTrack = new Border { Height = 8, CornerRadius = new CornerRadius(4), Background = Paper.Line, VerticalAlignment = VerticalAlignment.Center, ClipToBounds = true };
            progressFill = new Border { Height = 8, CornerRadius = new CornerRadius(4), Background = Paper.Accent, HorizontalAlignment = HorizontalAlignment.Left, Width = 0, ClipToBounds = true };
            // a soft band of light that keeps moving along the filled part, so the bar never looks stuck
            sheen = new Border
            {
                Width = 90, HorizontalAlignment = HorizontalAlignment.Left,
                Background = new LinearGradientBrush(new GradientStopCollection
                {
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0), new GradientStop(Color.FromArgb(0x70, 255, 255, 255), 0.5), new GradientStop(Color.FromArgb(0, 255, 255, 255), 1)
                }, 0),
                RenderTransform = new TranslateTransform(-90, 0)
            };
            progressFill.Child = new Grid { Children = { sheen } };
            var trackGrid = new Grid();
            trackGrid.Children.Add(progressTrack);
            trackGrid.Children.Add(progressFill);
            bar.Children.Add(trackGrid);
            percentText = Paper.Text("0%", 12.5, Paper.Muted, Paper.Mono);
            percentText.HorizontalAlignment = HorizontalAlignment.Right; percentText.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(percentText, 1);
            bar.Children.Add(percentText);
            step.Children.Add(bar);
            ((TranslateTransform)sheen.RenderTransform).BeginAnimation(TranslateTransform.XProperty,
                new DoubleAnimation(-90, 420, TimeSpan.FromMilliseconds(1500)) { RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Paper.InOut });
            shownFraction = 0;
            return step;
        }

        private void SetProgress(double f, string status)
        {
            if (statusText != null && statusText.Text != status) statusText.Text = status;
            if (f < shownFraction) return;
            shownFraction = f;
            double w = Math.Max(0, progressTrack.ActualWidth * f);
            progressFill.BeginAnimation(WidthProperty, new DoubleAnimation(w, TimeSpan.FromMilliseconds(380)) { EasingFunction = Paper.Out });
            percentText.Text = (int)Math.Round(f * 100) + "%";
        }

        private void StartWork()
        {
            busy = true;
            closeBtn.Visibility = Visibility.Hidden;
            bool uninstall = mode == Mode.Uninstall;
            if (mode == Mode.Update)
            {
                if (dir == null) dir = Setup.InstalledDir() ?? Setup.DefaultDir;
                desktopShortcut = File.Exists(Setup.DesktopLink());
            }
            ShowStep(ProgressStep(uninstall ? "Uninstall" : mode == Mode.Update ? "Update" : "Installing",
                uninstall ? "Removing Facet" : mode == Mode.Update ? "Updating Facet" : "Setting up Facet"));
            PulseCube(true);
            string target = dir;
            bool desk = desktopShortcut, data = deleteData;
            double lastSent = -1; string lastStatus = null;
            Action<double, string> report = (f, s) =>
            {
                if (f - lastSent < 0.004 && s == lastStatus && f < 1) return;
                lastSent = f; lastStatus = s;
                Dispatcher.BeginInvoke(new Action(() => SetProgress(f, s)));
            };
            Task.Run(() =>
            {
                if (mode == Mode.Update)
                {
                    report(0, "Waiting for Facet to close");
                    var sw = Stopwatch.StartNew();
                    while (Setup.RunningFacets(target).Count > 0 && sw.ElapsedMilliseconds < 20000) Thread.Sleep(250);
                }
                if (uninstall) Setup.Uninstall(target, data, report);
                else Setup.Install(target, desk, report);
            }).ContinueWith(t => Dispatcher.Invoke(() =>
            {
                busy = false;
                closeBtn.Visibility = Visibility.Visible;
                PulseCube(false);
                if (t.IsFaulted) { ShowStep(Failed(t.Exception.GetBaseException().Message)); return; }
                SetProgress(1, "Done");
                var finish = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(450) };
                finish.Tick += (s, e) => { finish.Stop(); Finished(); };
                finish.Start();
            }));
        }

        private void Finished()
        {
            if (mode == Mode.Uninstall) { ScatterCube(); ShowStep(Done("Facet has been removed", "Thanks for trying Facet.", Paper.Pill("Close", true, Close))); return; }
            string exe = System.IO.Path.Combine(dir, "Facet.exe");
            if (mode == Mode.Update)
            {
                Launch(exe);
                ShowStep(Done("Facet is up to date", "Version " + Setup.Version + " is installed. Facet is opening again."));
                var close = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1600) };
                close.Tick += (s, e) => { close.Stop(); Close(); };
                close.Start();
                return;
            }
            if (launchAfter) Launch(exe);
            ShowStep(Done("Facet is ready", "Find it in the Start menu" + (desktopShortcut ? " and on your desktop" : "") + ". To open links in Facet, make it your default browser.",
                Paper.Pill("Make default browser", false, () =>
                {
                    try { Process.Start(new ProcessStartInfo("ms-settings:defaultapps?registeredAppUser=Facet") { UseShellExecute = true }); } catch { }
                }),
                Paper.Pill(launchAfter ? "Done" : "Open Facet", true, () => { if (!launchAfter) Launch(exe); Close(); })));
        }

        private static void Launch(string exe)
        {
            try { Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = System.IO.Path.GetDirectoryName(exe) }); } catch { }
        }

        private Panel Done(string title, string text, params Border[] buttons)
        {
            StackPanel top;
            var col = Column(out top);
            var step = new StackPanel();
            // a check that draws itself inside a circle
            var mark = new Grid { Width = 56, Height = 56, RenderTransformOrigin = new Point(0.5, 0.5) };
            var ring = new Ellipse { Fill = Paper.B("#B5532F", 0x1C), Stroke = Paper.Accent, StrokeThickness = 2 };
            var check = new Path
            {
                Data = Geometry.Parse("M17,29 L25,37 L40,21"), Stroke = Paper.Accent, StrokeThickness = 3, StrokeStartLineCap = PenLineCap.Round,
                StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round, StrokeDashArray = new DoubleCollection { 14, 14 }, StrokeDashOffset = 14
            };
            mark.Children.Add(ring);
            mark.Children.Add(check);
            check.BeginAnimation(Shape.StrokeDashOffsetProperty, new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(520)) { BeginTime = TimeSpan.FromMilliseconds(380), EasingFunction = Paper.Out });
            var pop = new ScaleTransform(0.6, 0.6);
            mark.RenderTransform = pop;
            pop.BeginAnimation(ScaleTransform.ScaleXProperty, new DoubleAnimation(0.6, 1, TimeSpan.FromMilliseconds(520)) { BeginTime = TimeSpan.FromMilliseconds(200), EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.5 } });
            pop.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.6, 1, TimeSpan.FromMilliseconds(520)) { BeginTime = TimeSpan.FromMilliseconds(200), EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.5 } });
            // wrapped, so the step's rise-in moves the wrapper and the pop keeps its own transform
            step.Children.Add(new Border { Child = mark, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 0, 0, 18) });
            step.Children.Add(Heading(title));
            step.Children.Add(Body(text));
            top.Children.Add(step);
            if (buttons.Length > 0) AddFooter(col, Footer(null, buttons));
            return Flatten(col, step);
        }

        private Panel Failed(string message)
        {
            StackPanel top;
            var col = Column(out top);
            var step = new StackPanel();
            step.Children.Add(TagLine("Something went wrong"));
            step.Children.Add(Heading(mode == Mode.Uninstall ? "Facet couldn't be removed" : "Setup couldn't finish"));
            step.Children.Add(Body(message));
            top.Children.Add(step);
            AddFooter(col, Footer(null, Paper.Pill("Close", false, Close), Paper.Pill("Try again", true, StartWork)));
            return Flatten(col, step);
        }
    }
}
