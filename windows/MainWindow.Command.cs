using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;

namespace Facet
{
    /// <summary>The command bar (Ctrl+K), as on Android: one box that finds tabs, bookmarks, history, settings and actions.</summary>
    public partial class MainWindow
    {
        private class Cmd
        {
            public string Icon, Title, Sub, Group;
            public Action Run;
        }

        private List<Cmd> Commands()
        {
            var list = new List<Cmd>();
            Action<string, string, string, Action> act = (icon, title, sub, run) => list.Add(new Cmd { Icon = icon, Title = title, Sub = sub, Group = "Actions", Run = run });
            act("plus", "New tab", "Ctrl+T", () => NewTab(null, false));
            act("private", "New private tab", "Ctrl+Shift+T", () => NewTab(null, true));
            act("settings", "Settings", null, ShowSettings);
            act("tabs", "Profiles", "Switch, add or remove profiles", () => PushPage(new ProfilesPage(this)));
            act("copy", "Research", "Notes, saved pages and highlights", ShowResearch);
            act("refresh", "History", "Ctrl+H", ShowHistory);
            act("download", "Downloads", "Ctrl+J", ShowDownloads);
            act("puzzle", "Extensions", null, ShowExtensions);
            act("shield", "Privacy and security", "Protection level, HTTPS-only, JavaScript", () => { OpenSettings(true); PushPage(new PrivacyPage(this)); });
            act("lock", "Site permissions", null, () => { OpenSettings(true); PushPage(new SitePermissionsPage(this)); });
            act("device", "Home page settings", "Accent, background, clock, weather", () => { OpenSettings(true); PushPage(new HomeSettingsPage(this)); });
            act("settings", "Appearance", "Theme and accent colour", () => { OpenSettings(true); PushPage(new AppearancePage(this)); });
            act("info", "AI assistant", null, () => { OpenSettings(true); PushPage(new AiPage(this)); });
            act("refresh", "Software update", null, ShowUpdates);
            if (current != null && !current.ShowsNewTab)
            {
                act("copy", "Reader view", "F9", ToggleReader);
                act("search", "Find in page", "Ctrl+F", OpenFind);
                act("print", "Print", "Ctrl+P", Print);
                if (!current.IsPrivate)
                {
                    act("copy", "Save to research", null, SaveCurrentToResearch);
                    if (AiConfig.Load().Enabled) { act("info", "Summarize page", null, () => AskAi(true)); act("info", "Ask about this page", null, () => AskAi(false)); }
                }
            }
            foreach (var t in tabs)
            {
                var tab = t;
                list.Add(new Cmd { Icon = tab.IsPrivate ? "private" : "globe", Title = tab.ShowsNewTab ? "New tab" : tab.Title, Sub = tab.ShowsNewTab ? "" : Urls.Host(tab.Url), Group = "Tabs", Run = () => SelectTab(tab) });
            }
            foreach (var b in data.Bookmarks)
            {
                var bm = b;
                list.Add(new Cmd { Icon = "star", Title = string.IsNullOrWhiteSpace(bm.Title) ? Urls.Host(bm.Url) : bm.Title, Sub = bm.Url, Group = "Bookmarks", Run = () => OpenFromCommand(bm.Url) });
            }
            foreach (var e in History.All(profile.Id).Take(400))
            {
                var he = e;
                list.Add(new Cmd { Icon = "refresh", Title = string.IsNullOrWhiteSpace(he.Title) ? Urls.Host(he.Url) : he.Title, Sub = he.Url, Group = "History", Run = () => OpenFromCommand(he.Url) });
            }
            return list;
        }

        private void OpenFromCommand(string url)
        {
            if (current != null && current.ShowsNewTab) { ClosePages(); current.Go(url); }
            else NewTab(url, current != null && current.IsPrivate);
        }

        private void ShowCommandBar()
        {
            Border card;
            var scrim = Scrim(out card, 620);
            card.VerticalAlignment = VerticalAlignment.Top;
            card.Margin = new Thickness(0, 60, 0, 0);
            card.Padding = new Thickness(10);
            var all = Commands();
            var sp = new StackPanel();
            var boxRow = new Grid { Margin = new Thickness(8, 4, 8, 8) };
            boxRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            boxRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            boxRow.Children.Add(Icons.Make("search", p.Muted, 18));
            var box = new TextBox
            {
                Background = Brushes.Transparent, BorderThickness = new Thickness(0), Foreground = p.Ink, CaretBrush = p.Ink, SelectionBrush = p.Accent,
                FontFamily = Fonts.Sans, FontSize = 17, Margin = new Thickness(10, 0, 0, 0), VerticalContentAlignment = VerticalAlignment.Center
            };
            Grid.SetColumn(box, 1);
            boxRow.Children.Add(box);
            sp.Children.Add(boxRow);
            sp.Children.Add(new Border { Height = 1, Background = p.Line, Margin = new Thickness(0, 0, 0, 6) });
            var results = new StackPanel();
            var scroll = new ScrollViewer { Content = results, MaxHeight = 420, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            sp.Children.Add(scroll);
            var hint = Ui.Text("Tabs, bookmarks, history, settings and actions.  ↑ ↓ to move, Enter to open, Esc to close.", 12, p.Muted, Fonts.Sans);
            hint.Margin = new Thickness(10, 8, 10, 2);
            sp.Children.Add(hint);
            card.Child = sp;

            var shown = new List<Tuple<Cmd, Border>>();
            int selected = 0;
            Action highlight = () =>
            {
                for (int i = 0; i < shown.Count; i++) shown[i].Item2.Background = i == selected ? p.Hover : Brushes.Transparent;
                if (selected < shown.Count) shown[selected].Item2.BringIntoView();
            };
            Action<Cmd> run = c => { CloseModal(); asking = false; c.Run(); };
            Action fill = () =>
            {
                results.Children.Clear();
                shown.Clear();
                string q = box.Text.Trim();
                var matches = all.Where(c => q.Length == 0 ? c.Group != "History" : (c.Title ?? "").IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0 || (c.Sub ?? "").IndexOf(q, StringComparison.OrdinalIgnoreCase) >= 0).ToList();
                var top = new List<Cmd>();
                if (q.Length > 0)
                {
                    bool looksLikeUrl = q.IndexOf(' ') < 0 && (q.Contains(".") || q.StartsWith("http"));
                    top.Add(new Cmd { Icon = looksLikeUrl ? "globe" : "search", Title = looksLikeUrl ? "Go to " + q : "Search " + Urls.EngineById(prefs.SearchEngine).Name + " for “" + q + "”", Group = "", Run = () => OpenFromCommand(Urls.Resolve(q, prefs.SearchEngine)) });
                }
                string group = null;
                foreach (var c in top.Concat(matches.GroupBy(x => x.Group).OrderBy(g => Array.IndexOf(new[] { "Tabs", "Actions", "Bookmarks", "History" }, g.Key)).SelectMany(g => g.Take(q.Length == 0 ? 6 : 8))).Take(40))
                {
                    var cmd = c;
                    if (cmd.Group != group && cmd.Group.Length > 0)
                    {
                        group = cmd.Group;
                        var tag = Ui.Tag(group, p.Accent);
                        tag.Margin = new Thickness(12, 10, 0, 4);
                        results.Children.Add(tag);
                    }
                    var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 8, 10, 8), Cursor = Cursors.Hand };
                    var g2 = new Grid();
                    g2.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    g2.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    g2.Children.Add(Icons.Make(cmd.Icon, p.Ink, 17));
                    var texts = new StackPanel { Margin = new Thickness(12, 0, 0, 0) };
                    texts.Children.Add(Ui.Text(cmd.Title, 14, p.Ink, Fonts.Sans, FontWeights.Medium));
                    if (!string.IsNullOrEmpty(cmd.Sub)) texts.Children.Add(Ui.Text(cmd.Sub, 12, p.Muted, Fonts.Sans));
                    Grid.SetColumn(texts, 1);
                    g2.Children.Add(texts);
                    row.Child = g2;
                    int index = shown.Count;
                    row.MouseEnter += (s, e) => { selected = index; highlight(); };
                    row.MouseLeftButtonUp += (s, e) => { e.Handled = true; run(cmd); };
                    shown.Add(Tuple.Create(cmd, row));
                    results.Children.Add(row);
                }
                if (shown.Count == 0) results.Children.Add(kitNote("Nothing matches."));
                selected = 0;
                highlight();
            };
            box.TextChanged += (s, e) => fill();
            box.PreviewKeyDown += (s, e) =>
            {
                if (e.Key == Key.Down) { selected = Math.Min(shown.Count - 1, selected + 1); highlight(); e.Handled = true; }
                else if (e.Key == Key.Up) { selected = Math.Max(0, selected - 1); highlight(); e.Handled = true; }
                else if (e.Key == Key.Enter) { if (selected < shown.Count) run(shown[selected].Item1); e.Handled = true; }
                else if (e.Key == Key.Escape) { CloseModal(); asking = false; e.Handled = true; }
            };
            scrim.MouseLeftButtonUp += (s, e) => asking = false;
            fill();
            OpenModal(scrim, card);
            asking = true; // Facet's own shortcuts wait while the bar is open
            Dispatcher.BeginInvoke(new Action(() => box.Focus()), DispatcherPriority.Background);
        }

        private UIElement kitNote(string text) { return new Kit(p).Note(text); }

        /// <summary>A link to an extension file (.xpi from Mozilla's add-ons site, or a .crx) was opened: offer to add it.</summary>
        private void OfferExtensionFile(string url, string name)
        {
            ShowAsk(new Ask
            {
                Title = "Add this extension?",
                Message = name + " from " + Urls.Host(url) + ". Facet runs the parts of extensions that change pages; extensions can read and change the pages they run on, so only add ones you trust.",
                Ok = "Add", Cancel = "Cancel",
                Answer = (ok, _) =>
                {
                    if (!ok) return;
                    RunInstall(() =>
                    {
                        byte[] bytes;
                        using (var c = new HttpClient()) bytes = c.GetByteArrayAsync(url).GetAwaiter().GetResult();
                        string tmp = System.IO.Path.Combine(System.IO.Path.GetTempPath(), name);
                        System.IO.File.WriteAllBytes(tmp, bytes);
                        try { return Extensions.InstallFile(tmp); } finally { try { System.IO.File.Delete(tmp); } catch { } }
                    });
                }
            });
        }

        /// <summary>The welcome changed the theme or accent: rebuild the window behind it and keep it on top.</summary>
        public void ApplyThemeUnder(UIElement top)
        {
            var parent = ((FrameworkElement)top).Parent as Panel;
            if (parent != null) parent.Children.Remove(top);
            pages.Clear();
            BuildChrome();
            ApplyTitleBar();
            Grid.SetRow(top, 1);
            Grid.SetRowSpan(top, 3);
            root.Children.Add(top);
        }
    }
}
