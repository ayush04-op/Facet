using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace Facet
{
    /// <summary>
    /// A damped spring as an easing curve: runs from 0 to 1, overshoots a little and settles (the same curve the
    /// Android logo uses). bounce 0 is critically damped; around 0.25 is lively.
    /// </summary>
    public class SpringEase : EasingFunctionBase
    {
        public double Bounce { get; set; } = 0.15;
        public double Response { get; set; } = 0.6;

        protected override double EaseInCore(double t)
        {
            if (t >= 1) return 1;
            double damping = Math.Max(0.05, Math.Min(1, 1 - Bounce));
            double omega = 2 * Math.PI / Response;
            double time = t * Response * 2.2;
            if (damping >= 1) return 1 - (1 + omega * time) * Math.Exp(-omega * time);
            double wd = omega * Math.Sqrt(1 - damping * damping);
            double decay = Math.Exp(-damping * omega * time);
            return 1 - decay * (Math.Cos(wd * time) + (damping * omega / wd) * Math.Sin(wd * time));
        }

        protected override Freezable CreateInstanceCore() { return new SpringEase { Bounce = Bounce, Response = Response }; }
    }

    /// <summary>
    /// The Facet logo, animated as on Android: its four pieces (sage arc, sand swirl, terracotta arc, pen tip) turn in
    /// around the centre and settle with a spring, a soft glow sits behind, a band of light sweeps across now and
    /// then, and the whole mark floats gently. Shared by the browser and the installer.
    /// </summary>
    public class LogoView : Grid
    {
        // file, starting angle, start (fraction of the 1.5 s assembly), starting scale
        private static readonly object[][] Pieces =
        {
            new object[] { "logo_teal.png", -110.0, 0.00, 0.86 },
            new object[] { "logo_swirl.png", 90.0, 0.12, 0.86 },
            new object[] { "logo_red.png", -80.0, 0.24, 0.86 },
            new object[] { "logo_tip.png", 0.0, 0.55, 0.2 },
        };

        private const double BuildMs = 1500;
        private readonly double size;
        private readonly Grid mark = new Grid();
        private readonly TranslateTransform floatT = new TranslateTransform();
        private readonly Image[] images = new Image[4];

        public LogoView(double size, bool glow = true, Color? glowColor = null)
        {
            this.size = size;
            Width = size; Height = size;
            var c = glowColor ?? Color.FromRgb(0xD4, 0x69, 0x3F);
            if (glow)
            {
                Children.Add(new Ellipse
                {
                    Width = size * 1.04, Height = size * 1.04,
                    Fill = new RadialGradientBrush(Color.FromArgb(0x44, c.R, c.G, c.B), Color.FromArgb(0, c.R, c.G, c.B))
                });
            }
            double inner = size * 0.84;
            mark.Width = inner; mark.Height = inner;
            for (int i = 0; i < Pieces.Length; i++)
            {
                var img = new Image
                {
                    Source = new BitmapImage(new Uri("pack://application:,,,/Assets/" + (string)Pieces[i][0])),
                    Width = inner, Height = inner, Opacity = 0, RenderTransformOrigin = new Point(0.5, 0.5)
                };
                RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
                var g = new TransformGroup();
                g.Children.Add(new ScaleTransform());
                g.Children.Add(new RotateTransform());
                img.RenderTransform = g;
                images[i] = img;
                mark.Children.Add(img);
            }
            // a band of light, shown only where the logo has pixels
            var sweep = new Rectangle
            {
                Width = inner, Height = inner,
                Fill = new LinearGradientBrush(new GradientStopCollection
                {
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 0.0), new GradientStop(Color.FromArgb(0x88, 255, 255, 255), 0.5),
                    new GradientStop(Color.FromArgb(0, 255, 255, 255), 1.0)
                }, new Point(0, 0.2), new Point(1, 0.8)) { RelativeTransform = new TranslateTransform(-1.3, 0) },
                OpacityMask = new VisualBrush(mark) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                IsHitTestVisible = false
            };
            var holder = new Grid { Width = inner, Height = inner, RenderTransform = floatT };
            holder.Children.Add(mark);
            holder.Children.Add(sweep);
            Children.Add(holder);
            Loaded += (s, e) => { Replay(); StartLoops(sweep); };
        }

        /// <summary>Plays the assembly from the start.</summary>
        public void Replay()
        {
            var spring = new SpringEase { Bounce = 0.15, Response = 0.6 };
            for (int i = 0; i < Pieces.Length; i++)
            {
                double rot = (double)Pieces[i][1], from = (double)Pieces[i][2], scale = (double)Pieces[i][3];
                var begin = TimeSpan.FromMilliseconds(from * BuildMs);
                var dur = TimeSpan.FromMilliseconds(0.5 * BuildMs);
                var g = (TransformGroup)images[i].RenderTransform;
                var sc = (ScaleTransform)g.Children[0];
                var rt = (RotateTransform)g.Children[1];
                images[i].BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(0.5 * BuildMs / 2.5)) { BeginTime = begin });
                var grow = new DoubleAnimation(scale, 1, dur) { BeginTime = begin, EasingFunction = spring };
                sc.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
                sc.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
                rt.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(rot, 0, dur) { BeginTime = begin, EasingFunction = spring });
            }
        }

        private void StartLoops(Rectangle sweep)
        {
            // gentle float, 4.2 s per cycle, as on Android
            floatT.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(-0.018 * size, 0.018 * size, TimeSpan.FromMilliseconds(2100))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
            // the light passes in the first 40% of each 4.2 s cycle, after the assembly
            var pass = new DoubleAnimationUsingKeyFrames { Duration = TimeSpan.FromMilliseconds(4200), RepeatBehavior = RepeatBehavior.Forever, BeginTime = TimeSpan.FromMilliseconds(BuildMs + 200) };
            pass.KeyFrames.Add(new LinearDoubleKeyFrame(-1.3, KeyTime.FromTimeSpan(TimeSpan.Zero)));
            pass.KeyFrames.Add(new EasingDoubleKeyFrame(1.3, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1680))) { EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
            pass.KeyFrames.Add(new DiscreteDoubleKeyFrame(1.3, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(4200))));
            ((TranslateTransform)((LinearGradientBrush)sweep.Fill).RelativeTransform).BeginAnimation(TranslateTransform.XProperty, pass);
        }
    }
}
