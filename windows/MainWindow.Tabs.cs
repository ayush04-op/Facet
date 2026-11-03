using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace Facet
{
    /// <summary>
    /// Tab groups and the vertical tab list, as on Android: a tab can belong to one named group, a group's tabs sit
    /// side by side and fold away when its name is clicked, and the tabs can be shown along the top or on the side.
    /// </summary>
    public partial class MainWindow
    {
        private readonly HashSet<string> collapsedGroups = new HashSet<string>();
        private Border sideEl;
        private StackPanel sideList;

        // ------------------------------------------------------------ groups

        private IEnumerable<string> GroupNames() { return tabs.Where(x => x.Group != null).Select(x => x.Group).Distinct(); }

        /// <summary>Each group gets one of the six accent colours, picked from its name so it keeps it.</summary>
        private SolidColorBrush GroupBrush(string name, byte alpha = 255)
        {
            int h = 0;
            foreach (char c in name) h = (h * 31 + c) & 0x7FFFFFFF;
            return Ui.B(Palette.Accents[h % Palette.Accents.Length][p.Dark ? 2 : 1], alpha);
        }

        /// <summary>Puts a tab in a group (null takes it out), next to the group's other tabs.</summary>
        private void SetGroup(BrowserTab t, string name)
        {
            string old = t.Group;
            t.Group = string.IsNullOrWhiteSpace(name) ? null : name.Trim();
            if (t.Group != null) collapsedGroups.Remove(t.Group);
            int i = tabs.IndexOf(t);
            tabs.Remove(t);
            int at = t.Group != null ? tabs.FindLastIndex(x => x.Group == t.Group) : -1;
            // leaving a group: step out past its last tab, so the group stays in one piece
            if (at < 0 && old != null) at = tabs.FindLastIndex(x => x.Group == old);
            tabs.Insert(at >= 0 ? at + 1 : Math.Min(i, tabs.Count), t);
            KeepGroupsTogether();
            QueueSaveSession();
            RefreshTabs();
        }

        /// <summary>Orders the tabs so each group's tabs are side by side, where the group's first tab was.</summary>
        private void KeepGroupsTogether()
        {
            var order = new List<BrowserTab>();
            foreach (var t in tabs)
            {
                if (order.Contains(t)) continue;
                if (t.Group == null) order.Add(t);
                else order.AddRange(tabs.Where(x => x.Group == t.Group));
            }
            tabs.Clear();
            tabs.AddRange(order);
        }

        private void ToggleGroup(string name)
        {
            if (!collapsedGroups.Remove(name))
            {
                // folding away the group you're in: go to a tab outside it first
                if (current != null && current.Group == name)
                {
                    var other = tabs.FirstOrDefault(x => x.Group != name && !closing.Contains(x));
                    if (other == null) return;
                    collapsedGroups.Add(name);
                    SelectTab(other);
                    return;
                }
                collapsedGroups.Add(name);
            }
            RefreshTabs();
        }

        private void NewGroupFor(BrowserTab t)
        {
            Input("New tab group", "A short name, for example Trip or Work", "", false, n => { if (!string.IsNullOrWhiteSpace(n)) SetGroup(t, n); });
        }

        private void RenameGroup(string name)
        {
            Input("Rename group", "The new name for this group", name, false, n =>
            {
                if (string.IsNullOrWhiteSpace(n) || n.Trim() == name) return;
                foreach (var x in tabs) if (x.Group == name) x.Group = n.Trim();
                if (collapsedGroups.Remove(name)) collapsedGroups.Add(n.Trim());
                KeepGroupsTogether();
                QueueSaveSession();
                RefreshTabs();
            });
        }

        private void Ungroup(string name)
        {
            foreach (var x in tabs) if (x.Group == name) x.Group = null;
            collapsedGroups.Remove(name);
            QueueSaveSession();
            RefreshTabs();
        }

        private void CloseGroup(string name)
        {
            foreach (var x in tabs.Where(x => x.Group == name).ToArray()) RemoveTab(x);
            collapsedGroups.Remove(name);
            QueueSaveSession();
        }

        private void ToggleVerticalTabs()
        {
            prefs.VerticalTabs = !prefs.VerticalTabs;
            prefs.Save();
            ApplyTheme();
        }

        // ------------------------------------------------------------ menus

        /// <summary>Right-click on a tab: groups, tab layout and closing.</summary>
        private void ShowTabMenu(BrowserTab t)
        {
            var items = new List<Tuple<string, string, Action>>();
            items.Add(Item("plus", "New tab\tCtrl+T", () => NewTab(null, false)));
            items.Add(null);
            items.Add(Item("tabs", t.Group == null ? "Add to new group" : "Move to new group", () => NewGroupFor(t)));
            foreach (var n in GroupNames().ToList())
            {
                if (n == t.Group) continue;
                var name = n;
                items.Add(Item(null, "Move to " + name, () => SetGroup(t, name)));
            }
            if (t.Group != null) items.Add(Item(null, "Remove from group", () => SetGroup(t, null)));
            items.Add(null);
            items.Add(Item("tabs", prefs.VerticalTabs ? "Show tabs along the top" : "Show tabs on the side", ToggleVerticalTabs));
            items.Add(null);
            if (tabs.Count > 1) items.Add(Item(null, "Close other tabs", () =>
            {
                SelectTab(t);
                foreach (var x in tabs.ToArray()) if (x != t) RemoveTab(x);
                QueueSaveSession();
            }));
            items.Add(Item("close", "Close tab\tCtrl+W", () => CloseTab(t)));
            ShowPopupMenu(items, this, PlacementMode.MousePoint, 0);
        }

        private void ShowGroupMenu(string name)
        {
            var items = new List<Tuple<string, string, Action>>();
            items.Add(Item("plus", "New tab in group", () => { var nt = NewTab(null, false); SetGroup(nt, name); }));
            items.Add(Item(null, collapsedGroups.Contains(name) ? "Expand group" : "Collapse group", () => ToggleGroup(name)));
            items.Add(Item("settings", "Rename group", () => RenameGroup(name)));
            items.Add(Item(null, "Ungroup", () => Ungroup(name)));
            items.Add(null);
            items.Add(Item("close", "Close group", () => CloseGroup(name)));
            ShowPopupMenu(items, this, PlacementMode.MousePoint, 0);
        }

        // ------------------------------------------------------------ tabs along the top

        /// <summary>A group's name in the tab strip, in the group's colour. Click folds its tabs away.</summary>
        private Border StripGroupLabel(string name, int count)
        {
            bool folded = collapsedGroups.Contains(name);
            var b = Ui.Box(GroupBrush(name, 0x2A), 9);
            b.Height = 26; b.Margin = new Thickness(0, 3, 4, 3); b.Padding = new Thickness(9, 0, 10, 0);
            var sp = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            sp.Children.Add(new Ellipse { Width = 8, Height = 8, Fill = GroupBrush(name), VerticalAlignment = VerticalAlignment.Center });
            var t = Ui.Text(name, 12.5, p.Ink, Fonts.Sans, FontWeights.Medium);
            t.Margin = new Thickness(7, 0, 0, 0); t.MaxWidth = 140; t.VerticalAlignment = VerticalAlignment.Center;
            sp.Children.Add(t);
            if (folded)
            {
                var c = Ui.Text(count.ToString(), 11.5, p.Muted, Fonts.Mono);
                c.Margin = new Thickness(7, 0, 0, 0); c.VerticalAlignment = VerticalAlignment.Center;
                sp.Children.Add(c);
            }
            b.Child = sp;
            b.ToolTip = folded ? "Show this group's tabs" : "Fold this group away";
            Ui.Button(b, GroupBrush(name, 0x2A), GroupBrush(name, 0x44), () => ToggleGroup(name));
            b.MouseRightButtonUp += (s, e) => { ShowGroupMenu(name); e.Handled = true; };
            return b;
        }

        /// <summary>The coloured line under a grouped tab in the strip.</summary>
        private UIElement GroupLine(string name)
        {
            return new Border { Height = 2, CornerRadius = new CornerRadius(1), Background = GroupBrush(name), VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(10, 0, 10, 1), IsHitTestVisible = false };
        }

        // ------------------------------------------------------------ tabs on the side

        /// <summary>The vertical tab list: a New tab button, then the tabs, each group under its name.</summary>
        private Border BuildSidebar()
        {
            sideEl = new Border { Width = 248, Background = p.Bg, BorderBrush = p.Line, BorderThickness = new Thickness(0, 0, 1, 0) };
            var dock = new DockPanel();
            var add = Ui.Box(Brushes.Transparent, 9);
            add.Height = 34; add.Margin = new Thickness(8, 2, 8, 6);
            var ap = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            ap.Children.Add(Icons.Make("plus", p.Ink, 15));
            var at = Ui.Text("New tab", 13, p.Ink, Fonts.Sans, FontWeights.Medium);
            at.Margin = new Thickness(10, 0, 0, 0); at.VerticalAlignment = VerticalAlignment.Center;
            ap.Children.Add(at);
            add.Child = ap;
            Ui.Button(add, Brushes.Transparent, p.Hover, () => NewTab(null, false));
            DockPanel.SetDock(add, Dock.Top);
            dock.Children.Add(add);
            sideList = new StackPanel { Margin = new Thickness(8, 0, 8, 10) };
            dock.Children.Add(new ScrollViewer { Content = sideList, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
            sideEl.Child = dock;
            return sideEl;
        }

        private void RefreshSideTabs()
        {
            if (sideList == null) return;
            sideList.Children.Clear();
            string prev = null;
            foreach (var t in tabs)
            {
                if (closing.Contains(t)) continue;
                if (t.Group != null && t.Group != prev) sideList.Children.Add(SideGroupHeader(t.Group, tabs.Count(x => x.Group == t.Group)));
                prev = t.Group;
                if (t.Group != null && collapsedGroups.Contains(t.Group)) continue;
                sideList.Children.Add(SideRow(t));
            }
            // the title bar has no tabs now: it shows the page title instead
            var title = Ui.Text(current == null || current.ShowsNewTab ? "Facet" : current.Title, 13, p.Muted, Fonts.Sans);
            title.VerticalAlignment = VerticalAlignment.Center; title.Margin = new Thickness(6, 4, 0, 0); title.MaxWidth = Math.Max(200, ActualWidth - 420);
            title.IsHitTestVisible = false;
            tabStrip.Children.Add(title);
        }

        private UIElement SideGroupHeader(string name, int count)
        {
            bool folded = collapsedGroups.Contains(name);
            var b = Ui.Box(Brushes.Transparent, 9);
            b.Height = 30; b.Margin = new Thickness(0, 8, 0, 2); b.Padding = new Thickness(8, 0, 8, 0);
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.Children.Add(new Ellipse { Width = 9, Height = 9, Fill = GroupBrush(name), VerticalAlignment = VerticalAlignment.Center });
            var t = Ui.Text(name, 14, p.Ink, Fonts.Serif);
            t.Margin = new Thickness(10, 0, 0, 0); t.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(t, 1);
            g.Children.Add(t);
            var c = Ui.Tag(folded ? count + " hidden" : count.ToString(), p.Muted);
            c.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(c, 2);
            g.Children.Add(c);
            b.Child = g;
            Ui.Button(b, Brushes.Transparent, p.Hover, () => ToggleGroup(name));
            b.MouseRightButtonUp += (s, e) => { ShowGroupMenu(name); e.Handled = true; };
            return b;
        }

        private UIElement SideRow(BrowserTab tab)
        {
            bool sel = tab == current;
            var row = Ui.Box(sel ? p.Surface : Brushes.Transparent, 9, sel ? p.Line : null);
            row.Height = 36; row.Margin = new Thickness(tab.Group != null ? 12 : 0, 1, 0, 1);
            chips[tab] = row;
            if (tab == growing)
            {
                growing = null;
                row.BeginAnimation(HeightProperty, new DoubleAnimation(0, 36, TimeSpan.FromMilliseconds(200)) { EasingFunction = Anim.Out });
                Anim.Opacity(row, 0, 1, 200);
            }
            var g = new Grid { Margin = new Thickness(10, 0, 4, 0) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var ic = Icons.Make(tab.IsPrivate ? "private" : "globe", tab.IsPrivate ? p.Accent : p.Muted, 15);
            ((FrameworkElement)ic).Margin = new Thickness(0, 0, 9, 0);
            g.Children.Add(ic);
            var title = Ui.Text(tab.ShowsNewTab ? "New tab" : tab.Title, 13, sel ? p.Ink : p.Muted, Fonts.Sans, sel ? FontWeights.Medium : FontWeights.Normal);
            title.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(title, 1);
            g.Children.Add(title);
            var x = Ui.IconButton(p, "close", 12, () => CloseTab(tab), 22);
            Grid.SetColumn(x, 2);
            g.Children.Add(x);
            row.Child = g;
            Ui.Button(row, sel ? p.Surface : Brushes.Transparent, sel ? p.Surface : p.Hover, () => SelectTab(tab));
            row.MouseUp += (s, e) => { if (e.ChangedButton == System.Windows.Input.MouseButton.Middle) CloseTab(tab); };
            row.MouseRightButtonUp += (s, e) => { ShowTabMenu(tab); e.Handled = true; };
            if (tab.Group == null) return row;
            // a grouped tab hangs off a line in the group's colour
            var wrap = new Grid();
            wrap.Children.Add(new Border { Width = 2, CornerRadius = new CornerRadius(1), Background = GroupBrush(tab.Group), HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(4, 3, 0, 3) });
            wrap.Children.Add(row);
            return wrap;
        }
    }
}
