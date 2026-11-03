using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace Facet
{
    /// <summary>Paper scrollbars and eased wheel scrolling, for Facet's own pages and (via CSS) for websites.</summary>
    public static class Scrolling
    {
        private static string Hex(SolidColorBrush b, byte alpha)
        {
            var c = b.Color;
            return string.Format("#{0:X2}{1:X2}{2:X2}{3:X2}", alpha, c.R, c.G, c.B);
        }

        /// <summary>A slim rounded thumb on a clear track; it darkens on hover and turns accent-coloured while dragged.</summary>
        public static Style ScrollBarStyle(Palette p)
        {
            string thumb = Hex(p.Muted, 0x66), hover = Hex(p.Muted, 0xA6), drag = Hex(p.Accent, 0xE6);
            string thumbTemplate =
                "<ControlTemplate TargetType='Thumb'>" +
                "  <Border x:Name='b' CornerRadius='4' Background='" + thumb + "' Margin='{TemplateBinding Padding}'/>" +
                "  <ControlTemplate.Triggers>" +
                "    <Trigger Property='IsMouseOver' Value='True'><Setter TargetName='b' Property='Background' Value='" + hover + "'/></Trigger>" +
                "    <Trigger Property='IsDragging' Value='True'><Setter TargetName='b' Property='Background' Value='" + drag + "'/></Trigger>" +
                "  </ControlTemplate.Triggers>" +
                "</ControlTemplate>";
            string clearButton =
                "<Style TargetType='RepeatButton'><Setter Property='Focusable' Value='False'/><Setter Property='IsTabStop' Value='False'/>" +
                "<Setter Property='Template'><Setter.Value><ControlTemplate TargetType='RepeatButton'><Border Background='Transparent'/></ControlTemplate></Setter.Value></Setter></Style>";
            Func<bool, string> track = vertical =>
                "<ControlTemplate TargetType='ScrollBar'>" +
                "  <Grid Background='Transparent'>" +
                "    <Track x:Name='PART_Track' IsDirectionReversed='" + (vertical ? "True" : "False") + "'>" +
                "      <Track.DecreaseRepeatButton><RepeatButton Style='{StaticResource clear}' Command='" + (vertical ? "ScrollBar.PageUpCommand" : "ScrollBar.PageLeftCommand") + "'/></Track.DecreaseRepeatButton>" +
                "      <Track.IncreaseRepeatButton><RepeatButton Style='{StaticResource clear}' Command='" + (vertical ? "ScrollBar.PageDownCommand" : "ScrollBar.PageRightCommand") + "'/></Track.IncreaseRepeatButton>" +
                "      <Track.Thumb><Thumb Padding='" + (vertical ? "3,3,3,3" : "3,3,3,3") + "' Template='{StaticResource thumb}'/></Track.Thumb>" +
                "    </Track>" +
                "  </Grid>" +
                "</ControlTemplate>";
            string xaml =
                "<Style xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' xmlns:x='http://schemas.microsoft.com/winfx/2006/xaml' TargetType='ScrollBar'>" +
                "  <Style.Resources>" +
                "    <ControlTemplate x:Key='thumb' TargetType='Thumb'>" + thumbTemplate.Substring("<ControlTemplate TargetType='Thumb'>".Length) +
                "    " + clearButton.Replace("<Style TargetType='RepeatButton'>", "<Style x:Key='clear' TargetType='RepeatButton'>") +
                "  </Style.Resources>" +
                "  <Setter Property='Background' Value='Transparent'/>" +
                "  <Setter Property='Width' Value='12'/>" +
                "  <Setter Property='MinWidth' Value='12'/>" +
                "  <Setter Property='Template'><Setter.Value>" + track(true) + "</Setter.Value></Setter>" +
                "  <Style.Triggers>" +
                "    <Trigger Property='Orientation' Value='Horizontal'>" +
                "      <Setter Property='Width' Value='Auto'/><Setter Property='MinWidth' Value='0'/>" +
                "      <Setter Property='Height' Value='12'/><Setter Property='MinHeight' Value='12'/>" +
                "      <Setter Property='Template'><Setter.Value>" + track(false) + "</Setter.Value></Setter>" +
                "    </Trigger>" +
                "  </Style.Triggers>" +
                "</Style>";
            return (Style)XamlReader.Parse(xaml);
        }

        /// <summary>The same look for websites. Pages that style their own scrollbars keep theirs (their CSS comes later and wins).</summary>
        public static string PageCss(Palette p)
        {
            Func<SolidColorBrush, double, string> rgba = (b, a) => string.Format(System.Globalization.CultureInfo.InvariantCulture,
                "rgba({0},{1},{2},{3})", b.Color.R, b.Color.G, b.Color.B, a);
            return
                "::-webkit-scrollbar{width:12px;height:12px}" +
                "::-webkit-scrollbar-track,::-webkit-scrollbar-corner{background:transparent}" +
                "::-webkit-scrollbar-thumb{background-color:" + rgba(p.Muted, 0.45) + ";border-radius:8px;border:3px solid transparent;background-clip:content-box}" +
                "::-webkit-scrollbar-thumb:hover{background-color:" + rgba(p.Muted, 0.7) + "}" +
                "::-webkit-scrollbar-thumb:active{background-color:" + rgba(p.Accent, 0.9) + "}";
        }

        /// <summary>Script that adds PageCss to a document as early as possible, once per document.</summary>
        public static string PageScript(Palette p)
        {
            return "(function(){if(window.__facetScroll)return;window.__facetScroll=1;" +
                   "var css=" + System.Text.Json.JsonSerializer.Serialize(PageCss(p)) + ";" +
                   "var add=function(){if(!document.documentElement)return false;var s=document.createElement('style');s.id='facet-scrollbars';s.textContent=css;" +
                   "(document.head||document.documentElement).prepend(s);return true;};" +
                   "if(!add())document.addEventListener('readystatechange',add,{once:true});})();";
        }

        // ---------------------------------------------------------------- eased wheel scrolling

        private static readonly DependencyProperty OffsetProperty = DependencyProperty.RegisterAttached(
            "AnimatedOffset", typeof(double), typeof(Scrolling), new PropertyMetadata(0.0, (d, e) => ((ScrollViewer)d).ScrollToVerticalOffset((double)e.NewValue)));

        private static readonly DependencyProperty TargetProperty = DependencyProperty.RegisterAttached(
            "Target", typeof(double), typeof(Scrolling), new PropertyMetadata(-1.0));

        /// <summary>Each wheel notch glides to its new position instead of jumping; quick notches add up.</summary>
        public static void Smooth(ScrollViewer sv)
        {
            sv.PreviewMouseWheel += (s, e) =>
            {
                if (sv.ScrollableHeight <= 0) return;
                double target = (double)sv.GetValue(TargetProperty);
                if (target < 0) target = sv.VerticalOffset;
                target = Math.Max(0, Math.Min(sv.ScrollableHeight, target - e.Delta));
                sv.SetValue(TargetProperty, target);
                var a = new DoubleAnimation(sv.VerticalOffset, target, TimeSpan.FromMilliseconds(320)) { EasingFunction = Anim.Out };
                a.Completed += (o, x) => { if ((double)sv.GetValue(TargetProperty) == target) sv.SetValue(TargetProperty, -1.0); };
                sv.BeginAnimation(OffsetProperty, a);
                e.Handled = true;
            };
            // dragging the scrollbar or using the keyboard: stop gliding where we are
            sv.PreviewMouseLeftButtonDown += (s, e) => Stop(sv);
            sv.PreviewKeyDown += (s, e) => Stop(sv);
        }

        private static void Stop(ScrollViewer sv)
        {
            if ((double)sv.GetValue(TargetProperty) < 0) return;
            // pin the base value first: removing the animation falls back to it, and 0 would jump to the top
            sv.SetValue(OffsetProperty, sv.VerticalOffset);
            sv.BeginAnimation(OffsetProperty, null);
            sv.SetValue(TargetProperty, -1.0);
        }
    }
}
