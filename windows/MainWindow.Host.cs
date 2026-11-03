using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using CefSharp;

namespace Facet
{
    /// <summary>
    /// Profiles, per-profile bookmarks and history, Research, Reader view, the AI assistant, crash-safe sessions and
    /// what the Settings pages ask of the window (IFacetHost). The features of the Android 2.0 releases.
    /// </summary>
    public partial class MainWindow : IFacetHost
    {
        private Profile profile;
        private ProfileData data;
        private HomeConfig home;

        // ------------------------------------------------------------ IFacetHost

        Palette IFacetHost.P { get { return p; } }
        Prefs IFacetHost.Prefs { get { return prefs; } }
        Profile IFacetHost.Profile { get { return profile; } }
        ProfileData IFacetHost.Data { get { return data; } }
        HomeConfig IFacetHost.Home { get { return home; } }
        BrowserTab IFacetHost.Current { get { return current; } }
        void IFacetHost.Push(UIElement page) { PushPage(page); }
        void IFacetHost.Back() { PopPage(); }
        void IFacetHost.Toast(string text) { Toast(text); }
        void IFacetHost.Choose(string title, string[] options, int selected, Action<int> pick) { Choose(title, options, selected, pick); }
        void IFacetHost.Input(string title, string hint, string initial, bool secret, Action<string> save) { Input(title, hint, initial, secret, save); }
        void IFacetHost.ClearData() { ConfirmClearData(); }
        void IFacetHost.ShowUpdates() { ShowUpdates(); }
        void IFacetHost.ShowExtensions() { ShowExtensions(); }
        void IFacetHost.ShowOnboarding() { ClosePages(); ShowOnboarding(); }
        void IFacetHost.OpenUrl(string url) { ClosePages(); NewTab(url, current != null && current.IsPrivate); }

        public void Confirm(string title, string message, string ok, Action yes)
        {
            ShowAsk(new Ask { Title = title, Message = message, Ok = ok, Cancel = "Cancel", Answer = (y, _) => { if (y) yes(); } });
        }

        private void ConfirmClearData()
        {
            Confirm("Clear browsing data?", "Cookies, cache and site data are deleted for " + profile.Name + ". You'll be signed out of most sites.", "Clear", ClearData);
        }

        /// <summary>Rebuilds the window in the new colours and reopens the page you were on.</summary>
        public void ApplyTheme()
        {
            var top = pages.Count > 0 ? pages[pages.Count - 1] : null;
            pages.Clear();
            BuildChrome();
            ApplyTitleBar();
            if (top is AppearancePage) { OpenSettings(false); PushPage(new AppearancePage(this), false); }
            else if (top is HomeSettingsPage) { OpenSettings(false); PushPage(new HomeSettingsPage(this), false); }
            else if (top is TabsPage) { OpenSettings(false); PushPage(new TabsPage(this), false); }
            else if (top != null) OpenSettings(false);
        }

        public void HomeChanged()
        {
            if (newTabView != null) newTabView.Rebuild();
        }

        // ------------------------------------------------------------ profiles

        private void LoadProfile()
        {
            profile = Profiles.Current(prefs);
            data = Profiles.Data(prefs, profile);
        }

        private void SaveProfileData() { Profiles.SaveData(prefs, profile, data); }

        /// <summary>Saves this profile's tabs, closes them and opens the other profile's tabs.</summary>
        public void SwitchProfile(string id)
        {
            if (id == profile.Id) return;
            SaveState();
            var leaving = profile;
            ClosePages();
            foreach (var t in tabs.ToArray()) { host.Children.Remove(t.Browser); try { t.Browser.Dispose(); } catch { } }
            tabs.Clear();
            current = null;
            if (leaving.Kind == Profile.Temporary) { Profiles.Forget(leaving.Id); Profiles.Remove(leaving.Id); }
            prefs.ProfileId = id;
            prefs.Save();
            LoadProfile();
            RestoreSession();
            Toast(profile.Kind == Profile.Temporary ? "Temporary session. Everything here is erased when you leave." : "Switched to " + profile.Name);
        }

        public void CreateProfile(int kind)
        {
            string title = kind == Profile.Research ? "New research profile" : kind == Profile.Temporary ? "Temporary session" : "New profile";
            string hint = kind == Profile.Research ? "The topic you're researching" : kind == Profile.Temporary ? "A name for this session" : "For example Work";
            Input(title, hint, kind == Profile.Temporary ? "Temporary" : "", false, name => SwitchProfile(Profiles.Add(name, kind).Id));
        }

        public void DeleteProfile(string id)
        {
            var target = Profiles.All().FirstOrDefault(x => x.Id == id);
            if (target == null || id == "personal") return;
            Confirm("Delete " + target.Name + "?", "Its tabs, bookmarks, notes, history, cookies and logins are deleted from this PC.", "Delete", () =>
            {
                if (id == profile.Id) SwitchProfile("personal");
                Profiles.Remove(id);
                Toast(target.Name + " deleted");
                RefreshPages();
            });
        }

        private void RestoreSession()
        {
            if (prefs.RestoreTabs && data.SessionUrls.Count > 0)
            {
                // only the selected tab loads now; the others load when first opened
                var groups = data.SessionGroups ?? new List<string>();
                for (int i = 0; i < data.SessionUrls.Count; i++)
                {
                    var t = NewTab(data.SessionUrls[i], false, false, true);
                    if (i < groups.Count && !string.IsNullOrEmpty(groups[i])) t.Group = groups[i];
                }
                KeepGroupsTogether();
                SelectTab(tabs[Math.Max(0, Math.Min(data.SessionSelected, tabs.Count - 1))]);
            }
            else NewTab(null, false);
        }

        private void RefreshPages()
        {
            foreach (var page in pages) { var r = page as IRefreshable; if (r != null) r.Rebuild(); }
        }

        // ------------------------------------------------------------ crash-safe sessions

        private DispatcherTimer sessionTimer;
        private long savedBlocked, savedSecure;

        /// <summary>Tabs are saved shortly after every change, so a crash or power cut loses at most a few seconds.</summary>
        private void QueueSaveSession()
        {
            if (sessionTimer == null)
            {
                sessionTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                sessionTimer.Tick += (s, e) => { sessionTimer.Stop(); SaveState(); };
            }
            sessionTimer.Stop();
            sessionTimer.Start();
        }

        // ------------------------------------------------------------ history and research highlights

        private void OnPageLoaded(BrowserTab t)
        {
            QueueSaveSession();
            if (t.IsPrivate || profile.Kind == Profile.Temporary) return;
            History.Add(profile.Id, t.Url, t.Title);
            if (profile.Kind == Profile.Research)
            {
                var hl = Research.Load(profile.Id).HighlightsFor(t.Url);
                if (hl.Count > 0) PageCall(t, "__facet.applyHighlights(" + JsonSerializer.Serialize(hl) + ")");
            }
        }

        private static string pageScript;

        private static string PageScript()
        {
            if (pageScript == null)
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("page.js"))
                using (var r = new StreamReader(s)) pageScript = r.ReadToEnd();
            return pageScript;
        }

        /// <summary>Injects Facet's page helper (reader view, highlights, page text) if needed, then runs a call on it.</summary>
        private static void PageCall(BrowserTab t, string call)
        {
            try { using (var f = t.Browser.GetMainFrame()) f.ExecuteJavaScriptAsync(PageScript() + ";" + call); } catch { }
        }

        private static async Task<string> PageEval(BrowserTab t, string expr)
        {
            try
            {
                var r = await t.Browser.EvaluateScriptAsync(PageScript() + ";" + expr);
                return r.Success && r.Result != null ? r.Result.ToString() : "";
            }
            catch { return ""; }
        }

        public void SaveCurrentToResearch()
        {
            if (current == null || current.ShowsNewTab || current.IsPrivate) return;
            var r = Research.Load(profile.Id);
            r.SavePage(current.Url, current.Title);
            r.Save(profile.Id);
            Toast("Saved to " + profile.Name);
        }

        private async void HighlightSelection(BrowserTab t)
        {
            if (t == null || t.ShowsNewTab || t.IsPrivate) return;
            string text = await PageEval(t, "__facet.highlight()");
            if (string.IsNullOrWhiteSpace(text)) { Toast("Select some text first, then choose Highlight"); return; }
            var r = Research.Load(profile.Id);
            r.AddHighlight(t.Url, t.Title, text);
            r.Save(profile.Id);
            Toast("Highlighted and saved to " + profile.Name);
        }

        private void ShowResearch() { if (!(pages.Count > 0 && pages[pages.Count - 1] is ResearchPage)) PushPage(new ResearchPage(this)); }
        private void ShowHistory() { if (!(pages.Count > 0 && pages[pages.Count - 1] is HistoryPage)) PushPage(new HistoryPage(this)); }
        private void ShowDownloads() { if (!(pages.Count > 0 && pages[pages.Count - 1] is DownloadsPage)) PushPage(new DownloadsPage(this)); }

        public void ExportText(string suggestedName, string text)
        {
            var dlg = new Microsoft.Win32.SaveFileDialog { FileName = string.Join("_", suggestedName.Split(Path.GetInvalidFileNameChars())), Filter = "Markdown (*.md)|*.md|Text (*.txt)|*.txt" };
            if (dlg.ShowDialog(this) != true) return;
            try { File.WriteAllText(dlg.FileName, text); Toast("Exported"); } catch { Toast("Couldn't save the file"); }
        }

        // ------------------------------------------------------------ wallpaper

        public void PickWallpaper()
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose a picture for the home page", Filter = "Pictures (*.jpg, *.jpeg, *.png, *.bmp)|*.jpg;*.jpeg;*.png;*.bmp" };
            if (dlg.ShowDialog(this) != true) return;
            try
            {
                // stored at a sensible size: the home page never needs more than a 2400 px picture
                var src = new System.Windows.Media.Imaging.BitmapImage();
                src.BeginInit();
                src.UriSource = new Uri(dlg.FileName);
                src.DecodePixelWidth = 2400;
                src.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                src.EndInit();
                var enc = new System.Windows.Media.Imaging.JpegBitmapEncoder { QualityLevel = 90 };
                enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(src));
                using (var f = File.Create(HomeConfig.PhotoPath)) enc.Save(f);
                home.Wallpaper = HomeConfig.Photo;
                home.PhotoStamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                home.Save();
                HomeChanged();
                RefreshPages();
            }
            catch { Toast("Couldn't use that picture"); }
        }

        // ------------------------------------------------------------ reader view

        private Border readerBar;

        private void ToggleReader()
        {
            var t = current;
            if (t == null || t.ShowsNewTab) return;
            if (readerBar != null && readerBar.Visibility == Visibility.Visible) { CloseReader(); return; }
            PageCall(t, "__facet.reader(true," + prefs.ReaderSize + "," + prefs.ReaderWidth + "," + prefs.ReaderTheme + ")");
            ShowReaderBar();
        }

        private void ReaderChanged()
        {
            prefs.Save();
            if (current != null) PageCall(current, "__facet.reader(true," + prefs.ReaderSize + "," + prefs.ReaderWidth + "," + prefs.ReaderTheme + ")");
            ShowReaderBar();
        }

        private void CloseReader()
        {
            if (current != null) PageCall(current, "__facet.reader(false)");
            if (readerBar == null || readerBar.Visibility != Visibility.Visible) return;
            var bar = readerBar;
            Kit.FadeOut(bar, () => { bar.Visibility = Visibility.Collapsed; bar.BeginAnimation(OpacityProperty, null); bar.Opacity = 1; }, false);
        }

        /// <summary>Reader view's controls, in a Facet bar over the page (text size, width, theme, close).</summary>
        private void ShowReaderBar()
        {
            bool fresh = readerBar == null || readerBar.Visibility != Visibility.Visible;
            if (readerBar == null || readerBar.Parent == null)
            {
                readerBar = Ui.Box(p.Bg, 22, p.Line);
                readerBar.HorizontalAlignment = HorizontalAlignment.Center; readerBar.VerticalAlignment = VerticalAlignment.Bottom;
                readerBar.Margin = new Thickness(0, 0, 0, 22); readerBar.Padding = new Thickness(8, 6, 8, 6);
                readerBar.Effect = new DropShadowEffect { BlurRadius = 18, ShadowDepth = 3, Opacity = 0.22, Color = Colors.Black, RenderingBias = RenderingBias.Performance };
                // the same layer as the find bar: over the page, under Settings
                ((Grid)findBar.Parent).Children.Add(readerBar);
                fresh = true;
            }
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            Action<string, bool, Action> pill = (text, on, act) =>
            {
                var b = Ui.PillButton(p, text, on, act);
                b.Padding = new Thickness(14, 7, 14, 7); b.Margin = new Thickness(2, 0, 2, 0);
                row.Children.Add(b);
            };
            Action gap = () => row.Children.Add(new Border { Width = 1, Background = p.Line, Margin = new Thickness(6, 4, 6, 4) });
            pill("A−", false, () => { prefs.ReaderSize = Math.Max(14, prefs.ReaderSize - 2); ReaderChanged(); });
            pill("A+", false, () => { prefs.ReaderSize = Math.Min(30, prefs.ReaderSize + 2); ReaderChanged(); });
            gap();
            pill("Narrow", prefs.ReaderWidth == 0, () => { prefs.ReaderWidth = 0; ReaderChanged(); });
            pill("Medium", prefs.ReaderWidth == 1, () => { prefs.ReaderWidth = 1; ReaderChanged(); });
            pill("Wide", prefs.ReaderWidth == 2, () => { prefs.ReaderWidth = 2; ReaderChanged(); });
            gap();
            pill("Paper", prefs.ReaderTheme == 0, () => { prefs.ReaderTheme = 0; ReaderChanged(); });
            pill("Dark", prefs.ReaderTheme == 1, () => { prefs.ReaderTheme = 1; ReaderChanged(); });
            pill("Sepia", prefs.ReaderTheme == 2, () => { prefs.ReaderTheme = 2; ReaderChanged(); });
            gap();
            row.Children.Add(Ui.IconButton(p, "close", 13, CloseReader, 32));
            readerBar.Child = row;
            if (fresh) { readerBar.Visibility = Visibility.Visible; Kit.FadeIn(readerBar); }
        }

        // ------------------------------------------------------------ AI assistant

        private void AskAi(bool summarize)
        {
            var t = current;
            var ai = AiConfig.Load();
            if (t == null || t.ShowsNewTab) return;
            if (t.IsPrivate) { Toast("The assistant never runs in private tabs"); return; }
            if (!ai.Ready) { PushPage(new AiPage(this)); return; }
            if (summarize) RunAi(t, ai, null);
            else Input("Ask about this page", "Your question about " + Urls.Host(t.Url), "", false, q => { if (!string.IsNullOrWhiteSpace(q)) RunAi(t, ai, q.Trim()); });
        }

        private async void RunAi(BrowserTab t, AiConfig ai, string question)
        {
            var page = new AiAnswerPage(this, question == null ? "Summary" : "Answer", question, Urls.Host(t.Url));
            PushPage(page);
            string json = await PageEval(t, "__facet.pageText()");
            string text = "", title = t.Title;
            try { using (var doc = JsonDocument.Parse(json)) { text = doc.RootElement.GetProperty("text").GetString(); title = doc.RootElement.GetProperty("title").GetString(); } } catch { }
            if (string.IsNullOrWhiteSpace(text)) { page.Show(null, "Facet couldn't read any text on this page."); return; }
            string system = "You help someone understand a web page. Answer in plain language, briefly, using only the page text given. If the page doesn't answer the question, say so.";
            string user = (question == null ? "Summarize this page in a few short paragraphs or bullet points." : question) +
                          "\n\nPage title: " + title + "\nAddress: " + t.Url + "\n\nPage text:\n" + text;
            try { page.Show(await ai.AskAsync(system, user), null); }
            catch (Exception e) { page.Show(null, "The assistant couldn't answer: " + e.Message); }
        }

        // ------------------------------------------------------------ remembered site permissions

        /// <summary>
        /// A remembered answer for the site is used straight away; notifications are always declined (Facet doesn't
        /// deliver them yet), as on Android. Returns false when Facet should ask.
        /// </summary>
        private bool AnswerFromMemory(Ask a)
        {
            if (a.PermissionHost == null || a.PermissionType == null) return false;
            if (a.PermissionType == SitePerm.Notifications) { a.Answer(false, null); return true; }
            var saved = SitePerms.Find(a.PermissionHost, a.PermissionType);
            if (saved == null) return false;
            if (saved.Allowed) SitePerms.Touch(a.PermissionHost, a.PermissionType);
            a.Answer(saved.Allowed, null);
            return true;
        }

        // ------------------------------------------------------------ welcome

        private void ShowOnboarding()
        {
            var o = new Onboarding(this, () => { prefs.Onboarded = true; prefs.Save(); });
            // below the title bar, so the window can still be moved, resized and closed
            Grid.SetRow(o, 1);
            Grid.SetRowSpan(o, 3);
            root.Children.Add(o);
        }
    }
}
