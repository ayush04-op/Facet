using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Facet
{
    /// <summary>What the Settings, Research, History and Downloads pages need from the window.</summary>
    public interface IFacetHost
    {
        Palette P { get; }
        Prefs Prefs { get; }
        Profile Profile { get; }
        ProfileData Data { get; }
        HomeConfig Home { get; }
        BrowserTab Current { get; }
        void Push(UIElement page);
        void Back();
        void Toast(string text);
        void Choose(string title, string[] options, int selected, Action<int> pick);
        void Input(string title, string hint, string initial, bool secret, Action<string> save);
        void Confirm(string title, string message, string ok, Action yes);
        void ApplyTheme();
        void HomeChanged();
        void OpenUrl(string url);
        void SwitchProfile(string id);
        void CreateProfile(int kind);
        void DeleteProfile(string id);
        void ClearData();
        void ShowUpdates();
        void ShowExtensions();
        void ShowOnboarding();
        void PickWallpaper();
        void SaveCurrentToResearch();
        void ExportText(string suggestedName, string text);
    }

    /// <summary>Pages that refresh their contents in place when you come back to them.</summary>
    public interface IRefreshable { void Rebuild(); }

    /// <summary>A Settings-style page: back button, serif title, a scrolling column of grouped cards.</summary>
    public abstract class PageBase : Grid, IRefreshable
    {
        protected readonly IFacetHost h;
        protected readonly Kit kit;
        protected readonly StackPanel body;

        protected PageBase(IFacetHost host, string title)
        {
            h = host;
            kit = new Kit(host.P);
            Children.Add(kit.Page(title, host.Back, out body));
        }

        public void Rebuild() { body.Children.Clear(); Build(); }
        protected abstract void Build();

        protected StackPanel Group(string section)
        {
            if (section != null) body.Children.Add(kit.Section(section));
            Border card;
            var sp = kit.Group(out card);
            body.Children.Add(card);
            return sp;
        }

        protected void Note(string text) { body.Children.Add(kit.Note(text)); }
        protected Prefs P { get { return h.Prefs; } }
        protected void Saved() { h.Prefs.Save(); }
    }

    // ---------------------------------------------------------------- Settings

    public class SettingsPage : PageBase
    {
        public SettingsPage(IFacetHost h) : base(h, "Settings") { Rebuild(); }

        protected override void Build()
        {
            var g = Group("Software");
            string avail = P.AvailableUpdate;
            kit.Add(g, kit.Value("download", "Software update", avail.Length > 0 ? avail + " available" : "Version " + AppInfo.Version, "Updates, this build and device status", h.ShowUpdates));

            g = Group("You");
            kit.Add(g, kit.Value("tabs", "Profiles", h.Profile.Name, "Separate tabs, bookmarks, cookies and logins", () => h.Push(new ProfilesPage(h))));
            kit.Add(g, kit.Value("device", "Home page", HomeConfig.Wallpapers.Length > h.Home.Wallpaper ? HomeConfig.Wallpapers[h.Home.Wallpaper] : h.Home.Wallpaper == HomeConfig.Photo ? "Your photo" : "Paper",
                "Accent colour, background, clock and weather", () => h.Push(new HomeSettingsPage(h))));
            kit.Add(g, kit.Value("settings", "Appearance", Palette.ThemeNames[Math.Max(0, Math.Min(3, P.Theme))], null, () => h.Push(new AppearancePage(h))));

            g = Group("Privacy and security");
            kit.Add(g, kit.Value("shield", "Privacy and security", ProtectionName(P.ProtectionLevel), "Trackers, cookies, HTTPS-only, JavaScript", () => h.Push(new PrivacyPage(h))));
            int reminders = SitePerms.Reminders().Select(x => x.Host).Distinct().Count();
            kit.Add(g, kit.Value("lock", "Site permissions", reminders > 0 ? reminders + " to review" : "Camera, microphone, location", null, () => h.Push(new SitePermissionsPage(h))));

            g = Group("Browsing");
            kit.Add(g, kit.Value("search", "Search engine", Urls.EngineById(P.SearchEngine).Name, null, () => h.Push(new EnginePage(h))));
            kit.Add(g, kit.Value("tabs", "Tabs and groups", P.VerticalTabs ? "On the side" : "Along the top", "Tab layout, tab groups, restoring tabs", () => h.Push(new TabsPage(h))));
            kit.Add(g, kit.Value("refresh", "History", History.All(h.Profile.Id).Count + " pages", "Pages you visited in this profile", () => h.Push(new HistoryPage(h))));
            kit.Add(g, kit.Value("download", "Downloads", Downloads.All().Count(x => x.State == 0) > 0 ? "In progress" : Downloads.All().Count + " files", null, () => h.Push(new DownloadsPage(h))));
            int on = Extensions.All.FindAll(x => x.Enabled).Count;
            kit.Add(g, kit.Value("puzzle", "Extensions", Extensions.All.Count == 0 ? "None" : on + " of " + Extensions.All.Count + " on", "Userscripts and page-changing extensions", h.ShowExtensions));
            var ai = AiConfig.Load();
            kit.Add(g, kit.Value("info", "AI assistant", ai.Enabled ? (ai.Ready ? "On" : "Needs a key") : "Off", "Optional, with your own key", () => h.Push(new AiPage(h))));

            g = Group("Data");
            kit.Add(g, kit.Value("trash", "Clear browsing data", "Cookies, cache, site data", null, h.ClearData));

            g = Group("Activity");
            kit.Add(g, kit.Info("Trackers blocked", (P.TrackersBlocked + Stats.Blocked).ToString("N0")));
            kit.Add(g, kit.Info("Secure page loads", (P.SecureLoads + Stats.Secure).ToString("N0")));

            g = Group("About");
            kit.Add(g, kit.Value("info", "About Facet", "Version " + AppInfo.Version, null, () => h.Push(new AboutPage(h))));
        }

        public static string ProtectionName(int level) { return level == 0 ? "Standard" : level == 1 ? "Strict" : "Custom"; }
    }

    public class EnginePage : PageBase
    {
        public EnginePage(IFacetHost h) : base(h, "Search engine") { Rebuild(); }

        protected override void Build()
        {
            var g = Group(null);
            foreach (var e in Urls.Engines)
            {
                var eng = e;
                kit.Add(g, kit.Choice(eng.Name, null, eng.Id == P.SearchEngine, () => { P.SearchEngine = eng.Id; Saved(); Rebuild(); }));
            }
        }
    }

    public class TabsPage : PageBase
    {
        public TabsPage(IFacetHost h) : base(h, "Tabs and groups") { Rebuild(); }

        protected override void Build()
        {
            var g = Group("Tab layout");
            kit.Add(g, kit.Choice("Along the top", "Tabs in the title bar, as in most browsers.", !P.VerticalTabs, () => Layout(false)));
            kit.Add(g, kit.Choice("On the side", "A list on the left, grouped by tab group. Easy to scan with many tabs.", P.VerticalTabs, () => Layout(true)));
            g = Group("Sessions");
            kit.Add(g, kit.Toggle("tabs", "Restore tabs on launch", "Reopens your tabs and groups, also after a crash. Private tabs are never saved.", P.RestoreTabs, v => { P.RestoreTabs = v; Saved(); }));
            Note("Right-click a tab to put it in a group. Click a group's name to fold its tabs away, or right-click it to rename, ungroup or close it. Each profile keeps its own tabs and groups.");
        }

        private void Layout(bool vertical)
        {
            if (P.VerticalTabs == vertical) return;
            P.VerticalTabs = vertical;
            Saved();
            h.ApplyTheme();
        }
    }

    public class AppearancePage : PageBase
    {
        public AppearancePage(IFacetHost h) : base(h, "Appearance") { Rebuild(); }

        protected override void Build()
        {
            var g = Group("Theme");
            string[] subs = { "Follows light or dark mode in Windows", null, null, "Pure black, for the deepest contrast on OLED screens" };
            for (int i = 0; i < 4; i++)
            {
                int mode = i;
                kit.Add(g, kit.Choice(Palette.ThemeNames[i], subs[i], P.Theme == i, () => { P.Theme = mode; Saved(); h.ApplyTheme(); }));
            }
            g = Group("Accent colour");
            for (int i = 0; i < Palette.Accents.Length; i++)
            {
                int a = i;
                kit.Add(g, kit.Choice(Palette.Accents[i][0], null, P.Accent == i, () => { P.Accent = a; Saved(); h.ApplyTheme(); }));
            }
            Note("Facet always uses Prism Serif for headings and Prism Sans for text.");
        }
    }

    // ---------------------------------------------------------------- privacy

    public class PrivacyPage : PageBase
    {
        public PrivacyPage(IFacetHost h) : base(h, "Privacy and security") { Rebuild(); }

        protected override void Build()
        {
            var g = Group("Tracker and cookie protection");
            kit.Add(g, kit.Choice("Standard", "Blocks known ad and analytics trackers and their cookies. Fewest broken sites.", P.ProtectionLevel == 0, () => Level(0)));
            kit.Add(g, kit.Choice("Strict", "Also blocks fingerprinters and cryptominers. Some sites may need Standard.", P.ProtectionLevel == 1, () => Level(1)));
            kit.Add(g, kit.Choice("Custom", "Choose the switches yourself.", P.ProtectionLevel == 2, () => Level(2)));
            if (P.ProtectionLevel == 2)
            {
                kit.Add(g, kit.Toggle("shield", "Block ads and trackers", "Including fingerprinters and cryptominers.", P.BlockTrackers, v => { P.BlockTrackers = v; Saved(); }));
                kit.Add(g, kit.Toggle("private", "Block tracking cookies", "Cookies from tracking sites aren't sent or saved when they're third-party.", P.BlockThirdPartyCookies, v => { P.BlockThirdPartyCookies = v; Saved(); }));
            }
            kit.Add(g, kit.Value("info", "What was blocked on this page", "", "Trackers by site, for the tab you're on", () => h.Push(new BlockedPage(h))));

            g = Group("Connections");
            kit.Add(g, kit.Toggle("lock", "HTTPS-only mode", "Opens pages over HTTPS instead of HTTP.", P.HttpsOnly, v => { P.HttpsOnly = v; Saved(); }));
            kit.Add(g, kit.Toggle("settings", "JavaScript", "Turning this off blocks most trackers but breaks many sites. Applies to tabs you open from now on.", P.Javascript, v => { P.Javascript = v; Saved(); }));
            kit.Add(g, kit.Info("Passkeys", "Experimental (depends on the engine)"));

            g = Group("Activity");
            kit.Add(g, kit.Info("Trackers blocked", (P.TrackersBlocked + Stats.Blocked).ToString("N0")));
            kit.Add(g, kit.Info("Secure page loads", (P.SecureLoads + Stats.Secure).ToString("N0")));

            g = Group("Data");
            kit.Add(g, kit.Value("trash", "Clear browsing data", "Cookies, cache, site data", null, h.ClearData));
            kit.Add(g, kit.Value("refresh", "Clear history", History.All(h.Profile.Id).Count + " pages", null, () =>
                h.Confirm("Clear history?", "Facet forgets the pages you visited in " + h.Profile.Name + ".", "Clear", () => { History.Clear(h.Profile.Id); h.Toast("History cleared"); Rebuild(); })));
        }

        private void Level(int l) { P.ProtectionLevel = l; Saved(); Rebuild(); }
    }

    /// <summary>The trackers blocked on the current tab's page, by site.</summary>
    public class BlockedPage : PageBase
    {
        public BlockedPage(IFacetHost h) : base(h, "Blocked on this page") { Rebuild(); }

        protected override void Build()
        {
            var t = h.Current;
            var blocked = t != null && !t.ShowsNewTab ? PageBlocks.For(t.EngineId) : new Dictionary<string, int>();
            var g = Group(null);
            kit.Add(g, kit.Info("Page", t != null && !t.ShowsNewTab ? Urls.Host(t.Url) : "No page open"));
            kit.Add(g, kit.Info("Blocked requests", blocked.Values.Sum().ToString()));
            kit.Add(g, kit.Info("Protection", SettingsPage.ProtectionName(P.ProtectionLevel)));
            g = Group("By site");
            if (blocked.Count == 0) kit.Add(g, kit.Info("Nothing blocked", "so far"));
            foreach (var kv in blocked.OrderByDescending(x => x.Value)) kit.Add(g, kit.Info(kv.Key, kv.Value.ToString()));
            Note("Change how strict protection is under Settings, Privacy and security.");
        }
    }

    /// <summary>Shown when the lock in the address bar is clicked.</summary>
    public class SiteInfoPage : PageBase
    {
        private readonly string host;
        private readonly bool secure;

        public SiteInfoPage(IFacetHost h, string host, bool secure) : base(h, string.IsNullOrEmpty(host) ? "This page" : host)
        {
            this.host = host; this.secure = secure;
            Rebuild();
        }

        protected override void Build()
        {
            var t = h.Current;
            var blocked = t != null ? PageBlocks.For(t.EngineId) : new Dictionary<string, int>();
            var g = Group("Connection");
            kit.Add(g, kit.Info("Encryption", secure ? "Secure (HTTPS)" : "Not secure"));
            g = Group("Protection");
            kit.Add(g, kit.Info("Trackers blocked here", blocked.Values.Sum().ToString()));
            kit.Add(g, kit.Value("info", "See what was blocked", "", "By site", () => h.Push(new BlockedPage(h))));
            kit.Add(g, kit.Value("shield", "Protection level", SettingsPage.ProtectionName(P.ProtectionLevel), null, () => h.Push(new PrivacyPage(h))));
            if (!string.IsNullOrEmpty(host))
            {
                g = Group("This site");
                kit.Add(g, kit.Value("lock", "Site permissions", "", "Camera, microphone, location, notifications", () => h.Push(new SitePermissionPage(h, host))));
            }
        }
    }

    // ---------------------------------------------------------------- site permissions

    public class SitePermissionsPage : PageBase
    {
        public SitePermissionsPage(IFacetHost h) : base(h, "Site permissions") { Rebuild(); }

        protected override void Build()
        {
            var reminders = SitePerms.Reminders();
            if (reminders.Count > 0)
            {
                var sites = reminders.Select(x => x.Host).Distinct().ToList();
                var r = Group("Worth a look");
                kit.Add(r, kit.Value("info", sites.Count + (sites.Count == 1 ? " site has" : " sites have") + " had access for over a month", "",
                    string.Join(", ", sites.Take(3)) + (sites.Count > 3 ? " and more" : ""), () => h.Push(new PermissionRemindersPage(h))));
            }
            var all = SitePerms.All();
            var g = Group("By permission");
            foreach (var t in SitePerm.Types)
            {
                var type = t;
                var list = all.Where(x => x.Type == type).ToList();
                int allowed = list.Count(x => x.Allowed);
                string sub = type == SitePerm.Notifications ? "Not available yet, always blocked" : list.Count == 0 ? "Ask every time" : allowed + " allowed, " + (list.Count - allowed) + " blocked";
                kit.Add(g, kit.Value(null, SitePerm.Title(type), "", sub, () => h.Push(new PermissionTypePage(h, type))));
            }
            var hosts = SitePerms.Hosts();
            g = Group("All sites");
            if (hosts.Count == 0) kit.Add(g, kit.Info("No saved answers", ""));
            foreach (var host in hosts)
            {
                var x = host;
                int n = all.Count(p => p.Host == x);
                kit.Add(g, kit.Value(null, x, "", n + (n == 1 ? " setting" : " settings"), () => h.Push(new SitePermissionPage(h, x))));
            }
            if (hosts.Count > 0)
            {
                g = Group("Reset");
                kit.Add(g, kit.Value("trash", "Reset all site permissions", "", "Facet will ask again on every site", () =>
                    h.Confirm("Reset all site permissions?", "Every site will have to ask again.", "Reset", () => { SitePerms.ClearAll(); Rebuild(); })));
            }
            Note("Facet asks each time a site wants your camera, microphone or location, until you tick Remember for that site.");
        }
    }

    public class PermissionTypePage : PageBase
    {
        private readonly string type;
        public PermissionTypePage(IFacetHost h, string type) : base(h, SitePerm.Title(type)) { this.type = type; Rebuild(); }

        protected override void Build()
        {
            var list = SitePerms.All().Where(x => x.Type == type).OrderBy(x => x.Host).ToList();
            if (type == SitePerm.Notifications) Note("Facet doesn't deliver web notifications yet, so every request is declined.");
            var g = Group("Allowed");
            if (!list.Any(x => x.Allowed)) kit.Add(g, kit.Info("None", ""));
            foreach (var p in list.Where(x => x.Allowed))
            {
                var x = p;
                kit.Add(g, kit.Value(null, x.Host, "", "Allowed " + SitePerms.Ago(x.Granted) + " · last used " + SitePerms.Ago(x.LastUsed), () =>
                    h.Choose(x.Host, new[] { "Block", "Forget this site (ask again)" }, -1, i => { if (i == 0) SitePerms.Set(x.Host, type, false); else SitePerms.Remove(x.Host, type); Rebuild(); })));
            }
            g = Group("Blocked");
            if (!list.Any(x => !x.Allowed)) kit.Add(g, kit.Info("None", ""));
            foreach (var p in list.Where(x => !x.Allowed))
            {
                var x = p;
                kit.Add(g, kit.Value(null, x.Host, "", "Blocked " + SitePerms.Ago(x.Granted), () =>
                    h.Choose(x.Host, new[] { "Allow", "Forget this site (ask again)" }, -1, i => { if (i == 0) SitePerms.Set(x.Host, type, true); else SitePerms.Remove(x.Host, type); Rebuild(); })));
            }
        }
    }

    public class SitePermissionPage : PageBase
    {
        private readonly string host;
        public SitePermissionPage(IFacetHost h, string host) : base(h, host) { this.host = host; Rebuild(); }

        protected override void Build()
        {
            var g = Group("Permissions");
            foreach (var t in SitePerm.Types)
            {
                var type = t;
                var p = SitePerms.Find(host, type);
                string state = p == null ? "Ask every time" : p.Allowed ? "Allowed " + SitePerms.Ago(p.Granted) : "Blocked";
                int sel = p == null ? 2 : p.Allowed ? 0 : 1;
                kit.Add(g, kit.Value(null, SitePerm.Title(type), state, null, () =>
                    h.Choose(SitePerm.Title(type) + " for " + host, new[] { "Allow", "Block", "Ask every time" }, sel, i =>
                    {
                        if (i == 2) SitePerms.Remove(host, type); else SitePerms.Set(host, type, i == 0);
                        Rebuild();
                    })));
            }
            g = Group("Reset");
            kit.Add(g, kit.Value("trash", "Reset all for this site", "", null, () => { SitePerms.ClearHost(host); h.Toast("Reset"); Rebuild(); }));
        }
    }

    public class PermissionRemindersPage : PageBase
    {
        public PermissionRemindersPage(IFacetHost h) : base(h, "Permission reminders") { Rebuild(); }

        protected override void Build()
        {
            var list = SitePerms.Reminders().OrderBy(x => x.Granted).ToList();
            Note(list.Count == 0 ? "Nothing to review. Every site with access to your camera, microphone or location was allowed recently."
                : "These sites were allowed to use a sensitive feature at least 30 days ago and still can.");
            if (list.Count == 0) return;
            var g = Group(null);
            foreach (var p in list)
            {
                var x = p;
                kit.Add(g, kit.Value(null, x.Host + ": " + SitePerm.Title(x.Type), "", "Allowed " + SitePerms.Ago(x.Granted) + " · last used " + SitePerms.Ago(x.LastUsed), () =>
                    h.Choose(x.Host + ": " + SitePerm.Title(x.Type), new[] { "Revoke access", "Keep access" }, -1, i =>
                    {
                        if (i == 0) SitePerms.Remove(x.Host, x.Type); else SitePerms.Set(x.Host, x.Type, true);
                        Rebuild();
                    })));
            }
        }
    }

    // ---------------------------------------------------------------- profiles

    public class ProfilesPage : PageBase
    {
        public ProfilesPage(IFacetHost h) : base(h, "Profiles") { Rebuild(); }

        protected override void Build()
        {
            var cur = h.Profile;
            var g = Group("Your profiles");
            foreach (var w in Profiles.All())
            {
                var p = w;
                string sub = p.Label + (p.Id == cur.Id ? " · open now" : "");
                kit.Add(g, kit.Value(p.Kind == Profile.Research ? "copy" : p.Kind == Profile.Temporary ? "private" : "tabs", p.Name, "", sub, () =>
                {
                    var opts = new List<string>();
                    var acts = new List<Action>();
                    if (p.Id != cur.Id) { opts.Add("Switch to " + p.Name); acts.Add(() => h.SwitchProfile(p.Id)); }
                    if (p.Id != "personal")
                    {
                        opts.Add("Rename"); acts.Add(() => h.Input("Rename profile", "Profile name", p.Name, false, n => { Profiles.Rename(p.Id, n); Rebuild(); }));
                        opts.Add("Delete with its tabs and bookmarks"); acts.Add(() => h.DeleteProfile(p.Id));
                    }
                    if (opts.Count == 0) { h.Toast("This is the profile you're using"); return; }
                    h.Choose(p.Name, opts.ToArray(), -1, i => acts[i]());
                }));
            }
            g = Group("Add");
            kit.Add(g, kit.Value("plus", "New profile", "", "Its own tabs, bookmarks, cookies and logins. For example Work.", () => h.CreateProfile(Profile.Normal)));
            kit.Add(g, kit.Value("copy", "New research profile", "", "Keeps a topic's tabs, notes and page highlights together.", () => h.CreateProfile(Profile.Research)));
            kit.Add(g, kit.Value("private", "Temporary session", "", "Clears its tabs, cookies and site data as soon as you leave it.", () => h.CreateProfile(Profile.Temporary)));
            Note("Switching profile saves the tabs you have open and opens the ones from the other profile. Profiles never share cookies or logins.");
        }
    }

    // ---------------------------------------------------------------- home page

    public class HomeSettingsPage : PageBase
    {
        public HomeSettingsPage(IFacetHost h) : base(h, "Home page") { Rebuild(); }

        private void Changed() { h.Home.Save(); h.HomeChanged(); Rebuild(); }

        protected override void Build()
        {
            var c = h.Home;
            var g = Group("Accent colour");
            for (int i = 0; i < Palette.Accents.Length; i++)
            {
                int a = i;
                kit.Add(g, kit.Choice(Palette.Accents[i][0], null, P.Accent == i, () => { P.Accent = a; Saved(); h.ApplyTheme(); }));
            }
            g = Group("Background");
            kit.Add(g, kit.Choice("Paper", "The plain Paper background with its soft grid", c.Wallpaper == HomeConfig.Paper, () => { c.Wallpaper = HomeConfig.Paper; Changed(); }));
            for (int i = 0; i < HomeConfig.Wallpapers.Length; i++)
            {
                int w = i;
                kit.Add(g, kit.Choice(HomeConfig.Wallpapers[i], null, c.Wallpaper == i, () => { c.Wallpaper = w; Changed(); }));
            }
            kit.Add(g, kit.Choice("Your photo", c.Wallpaper == HomeConfig.Photo ? "In use. Click to pick another." : "Choose a picture from this PC", c.Wallpaper == HomeConfig.Photo, h.PickWallpaper));
            if (c.Wallpaper != HomeConfig.Paper)
            {
                g = Group("Wallpaper dimming");
                string[] dims = { "None", "Light", "Medium", "Strong" };
                for (int i = 0; i < 4; i++) { int d = i; kit.Add(g, kit.Choice(dims[i], null, c.Dim == i, () => { c.Dim = d; Changed(); })); }
            }
            g = Group("Motion");
            kit.Add(g, kit.Toggle(null, "Moving aurora", "Slow drifting colour behind the home page", c.Aurora, v => { c.Aurora = v; c.Save(); h.HomeChanged(); }));

            g = Group("Clock");
            kit.Add(g, kit.Toggle(null, "Show the time", null, c.Clock, v => { c.Clock = v; Changed(); }));
            if (c.Clock)
            {
                kit.Add(g, kit.Choice("Follow Windows", "12 or 24 hour", c.HourFormat == 0, () => { c.HourFormat = 0; Changed(); }));
                kit.Add(g, kit.Choice("12-hour", null, c.HourFormat == 1, () => { c.HourFormat = 1; Changed(); }));
                kit.Add(g, kit.Choice("24-hour", null, c.HourFormat == 2, () => { c.HourFormat = 2; Changed(); }));
                kit.Add(g, kit.Toggle(null, "Show the date", null, c.Date, v => { c.Date = v; Changed(); }));
            }

            g = Group("Weather");
            kit.Add(g, kit.Toggle(null, "Show the weather", "Off by default. Looks up the place you choose on Open-Meteo, nothing else.", c.Weather, v => { c.Weather = v; Changed(); }));
            if (c.Weather)
            {
                kit.Add(g, kit.Value(null, "Place", c.City.Length == 0 ? "Not set" : c.City, null, () =>
                    h.Input("Place", "City name", c.City, false, async q =>
                    {
                        if (string.IsNullOrWhiteSpace(q)) return;
                        var found = await Weather.SetPlaceAsync(c, q.Trim());
                        if (found == null) h.Toast("Couldn't find that place"); else { h.Toast(found); Changed(); }
                    })));
                kit.Add(g, kit.Choice("Celsius", null, !c.Fahrenheit, () => { c.Fahrenheit = false; Changed(); }));
                kit.Add(g, kit.Choice("Fahrenheit", null, c.Fahrenheit, () => { c.Fahrenheit = true; Changed(); }));
            }

            g = Group("Page");
            kit.Add(g, kit.Value(null, "Your name", c.Name.Length == 0 ? "Not set" : c.Name, "Shown in a greeting", () =>
                h.Input("Your name", "Optional. Used for a greeting on the home page, and stays on this PC.", c.Name, false, n => { c.Name = n.Trim(); Changed(); })));
            kit.Add(g, kit.Toggle(null, "Protection counters", null, c.Stats, v => { c.Stats = v; c.Save(); h.HomeChanged(); }));
            kit.Add(g, kit.Toggle(null, "Bookmarks", null, c.Bookmarks, v => { c.Bookmarks = v; c.Save(); h.HomeChanged(); }));
            Note("Only the place name is sent when you set it, and its coordinates when the weather is refreshed, at most every half hour.");
        }
    }

    // ---------------------------------------------------------------- AI assistant

    public class AiPage : PageBase
    {
        public AiPage(IFacetHost h) : base(h, "AI assistant") { Rebuild(); }

        protected override void Build()
        {
            var ai = AiConfig.Load();
            var g = Group(null);
            kit.Add(g, kit.Toggle("info", "Use an AI assistant", "Optional and off by default. Adds Summarize and Ask to the menu.", ai.Enabled, v => { ai.Enabled = v; ai.Save(); Rebuild(); }));
            if (ai.Enabled)
            {
                g = Group("Provider");
                kit.Add(g, kit.Choice("Anthropic (Claude)", null, ai.Provider == 0, () => { ai.Provider = 0; ai.BaseUrl = ""; ai.Model = ""; ai.Save(); Rebuild(); }));
                kit.Add(g, kit.Choice("OpenAI-compatible", "Any service that offers /chat/completions", ai.Provider == 1, () => { ai.Provider = 1; ai.BaseUrl = ""; ai.Model = ""; ai.Save(); Rebuild(); }));
                kit.Add(g, kit.Value(null, "Server address", ai.Base, null, () => h.Input("Server address", "https://", ai.Base, false, v => { ai.BaseUrl = v.Trim(); ai.Save(); Rebuild(); })));
                kit.Add(g, kit.Value(null, "Model", ai.ModelName.Length == 0 ? "Not set" : ai.ModelName, null, () => h.Input("Model name", "Model id", ai.ModelName, false, v => { ai.Model = v.Trim(); ai.Save(); Rebuild(); })));
                kit.Add(g, kit.Value("lock", "API key", ai.KeyProtected.Length == 0 ? "Not set" : "Saved, encrypted on this PC", null, () => h.Input("API key", "Paste your key", "", true, v => { ai.Key = v; ai.Save(); Rebuild(); })));
            }
            Note("When you choose Summarize or Ask, the text of the page you're on and your question are sent to the provider you chose, using your own key. Facet sends nothing on its own, and never from private tabs.");
        }
    }

    /// <summary>The assistant's answer, as a page with a copy button.</summary>
    public class AiAnswerPage : PageBase
    {
        private readonly string question;
        private string answer, error;
        private readonly string source;

        public AiAnswerPage(IFacetHost h, string title, string question, string source) : base(h, title)
        {
            this.question = question; this.source = source;
            Rebuild();
        }

        public void Show(string text, string err) { answer = text; error = err; Rebuild(); }

        protected override void Build()
        {
            if (!string.IsNullOrEmpty(question)) Note(question);
            var g = Group(source);
            var t = Ui.Text(error ?? answer ?? "Thinking…", 15, error != null ? h.P.Muted : h.P.Ink, Fonts.Sans);
            t.TextWrapping = TextWrapping.Wrap; t.TextTrimming = TextTrimming.None; t.Margin = new Thickness(18, 16, 18, 16);
            t.LineHeight = 23;
            var copy = new TextBox { Text = t.Text, IsReadOnly = true, BorderThickness = new Thickness(0), Background = Brushes.Transparent, Foreground = t.Foreground,
                FontFamily = Fonts.Sans, FontSize = 15, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(16, 14, 16, 14) };
            g.Children.Add(copy);
            if (answer != null)
            {
                var b = Ui.PillButton(h.P, "Copy", false, () => { try { Clipboard.SetText(answer); h.Toast("Copied"); } catch { } });
                b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 14, 0, 0);
                body.Children.Add(b);
            }
            Note("Answers come from the provider you chose and can be wrong. Check anything important.");
        }
    }

    // ---------------------------------------------------------------- history

    public class HistoryPage : PageBase
    {
        private string filter = "";
        public HistoryPage(IFacetHost h) : base(h, "History") { Rebuild(); }

        protected override void Build()
        {
            var search = kit.Field(filter, "Search history", false);
            var box = Group(null);
            box.Children.Add(search);
            search.TextChanged += (s, e) => { filter = search.Text; ShowList(); };
            list = new StackPanel();
            body.Children.Add(list);
            ShowList();
            if (h.Profile.Kind == Profile.Temporary) Note("Temporary sessions don't keep history.");
        }

        private StackPanel list;

        private void ShowList()
        {
            list.Children.Clear();
            var items = History.All(h.Profile.Id).Where(e => filter.Length == 0 || e.Url.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0
                                                             || e.Title.IndexOf(filter, StringComparison.OrdinalIgnoreCase) >= 0).Take(200).ToList();
            if (items.Count == 0) { list.Children.Add(kit.Note(filter.Length > 0 ? "Nothing matches." : "No history yet in " + h.Profile.Name + ".")); return; }
            foreach (var day in items.GroupBy(e => DateTimeOffset.FromUnixTimeMilliseconds(e.Time).LocalDateTime.Date))
            {
                list.Children.Add(kit.Section(day.Key == DateTime.Today ? "Today" : day.Key == DateTime.Today.AddDays(-1) ? "Yesterday" : day.Key.ToString("dddd, d MMMM")));
                Border card;
                var g = kit.Group(out card);
                foreach (var e in day)
                {
                    var x = e;
                    kit.Add(g, kit.Item(string.IsNullOrWhiteSpace(x.Title) ? Urls.Host(x.Url) : x.Title,
                        DateTimeOffset.FromUnixTimeMilliseconds(x.Time).LocalDateTime.ToString("HH:mm") + " · " + Urls.Host(x.Url),
                        () => h.OpenUrl(x.Url), "close", () => { History.Remove(h.Profile.Id, x.Url); ShowList(); }));
                }
                list.Children.Add(card);
            }
        }
    }

    // ---------------------------------------------------------------- downloads

    public class DownloadsPage : PageBase
    {
        private string category = "All";

        public DownloadsPage(IFacetHost h) : base(h, "Downloads")
        {
            Downloads.Changed += Refresh;
            Unloaded += (s, e) => Downloads.Changed -= Refresh;
            Rebuild();
        }

        private void Refresh() { Dispatcher.BeginInvoke(new Action(Rebuild)); }

        protected override void Build()
        {
            var all = Downloads.All();
            var cats = new[] { "All" }.Concat(all.Select(x => x.Category).Distinct().OrderBy(x => x)).ToArray();
            var chips = new WrapPanel { Margin = new Thickness(6, 14, 0, 4) };
            foreach (var c in cats)
            {
                var cat = c;
                var chip = Ui.PillButton(h.P, cat, cat == category, () => { category = cat; Rebuild(); });
                chip.Margin = new Thickness(0, 0, 8, 8); chip.Padding = new Thickness(16, 7, 16, 7);
                chips.Children.Add(chip);
            }
            body.Children.Add(chips);

            var g = Group(null);
            kit.Add(g, kit.Value("plus", "Add by link", "", "Paste a file's address to download it", () =>
                h.Input("Download a file", "File address (https://...)", "", false, u =>
                {
                    if (h.Current == null || string.IsNullOrWhiteSpace(u)) return;
                    try { CefSharp.WebBrowserExtensions.GetBrowserHost(h.Current.Browser).StartDownload(Urls.Resolve(u, P.SearchEngine)); h.Toast("Downloading…"); } catch { h.Toast("Couldn't start the download"); }
                })));
            kit.Add(g, kit.Value("download", "Open the Downloads folder", "", null, () => { try { Process.Start("explorer.exe", FacetDownloads.Folder); } catch { } }));

            var items = all.Where(x => category == "All" || x.Category == category).ToList();
            g = Group(category == "All" ? "Files" : category);
            if (items.Count == 0) kit.Add(g, kit.Info("Nothing here yet", ""));
            foreach (var it in items)
            {
                var e = it;
                string state = e.State == 0 ? (e.Total > 0 ? (100 * e.Received / Math.Max(1, e.Total)) + "% · " + DeviceStatus.Bytes(e.Received) + " of " + DeviceStatus.Bytes(e.Total) : DeviceStatus.Bytes(e.Received))
                    : e.State == 1 ? DeviceStatus.Bytes(e.Total > 0 ? e.Total : e.Received) + " · " + Urls.Host(e.Url)
                    : e.State == 2 ? "Failed" : "Cancelled";
                bool exists = e.State == 1 && File.Exists(e.Path);
                kit.Add(g, kit.Item(e.Name, state + (e.State == 1 && !exists ? " · file moved or deleted" : ""),
                    () =>
                    {
                        if (e.State == 0) { h.Choose(e.Name, new[] { "Cancel download" }, -1, i => FacetDownloads.Cancel(e)); return; }
                        var opts = new List<string>(); var acts = new List<Action>();
                        if (exists) { opts.Add("Open"); acts.Add(() => { try { Process.Start(new ProcessStartInfo(e.Path) { UseShellExecute = true }); } catch { h.Toast("Couldn't open it"); } }); }
                        if (exists) { opts.Add("Show in folder"); acts.Add(() => { try { Process.Start("explorer.exe", "/select,\"" + e.Path + "\""); } catch { } }); }
                        opts.Add("Copy download link"); acts.Add(() => { try { Clipboard.SetText(e.Url); h.Toast("Link copied"); } catch { } });
                        opts.Add("Remove from list"); acts.Add(() => Downloads.Remove(e));
                        if (exists) { opts.Add("Delete the file"); acts.Add(() => h.Confirm("Delete " + e.Name + "?", "The file is deleted from your Downloads folder.", "Delete", () => { try { File.Delete(e.Path); } catch { } Downloads.Remove(e); })); }
                        h.Choose(e.Name, opts.ToArray(), -1, i => acts[i]());
                    }, e.State == 0 ? "close" : null, e.State == 0 ? (Action)(() => FacetDownloads.Cancel(e)) : null));
            }
            if (all.Any(x => x.State != 0))
            {
                var b = Ui.PillButton(h.P, "Clear finished", false, Downloads.ClearFinished);
                b.HorizontalAlignment = HorizontalAlignment.Left; b.Margin = new Thickness(0, 14, 0, 0);
                body.Children.Add(b);
            }
            Note("Files go to your Downloads folder. Extension and userscript files are offered for installing instead.");
        }
    }

    // ---------------------------------------------------------------- research

    public class ResearchPage : PageBase
    {
        public ResearchPage(IFacetHost h) : base(h, "Research") { Rebuild(); }

        protected override void Build()
        {
            var r = Research.Load(h.Profile.Id);
            var topic = Ui.Text(h.Profile.Name, 26, h.P.Ink, Fonts.Serif);
            topic.Margin = new Thickness(6, 10, 0, 4);
            body.Children.Add(topic);
            if (h.Profile.Kind != Profile.Research)
                Note("Research works best in a research profile, which keeps a topic's tabs, notes and highlights together. Add one under Profiles.");
            else Note("Everything here stays on this PC. Nothing is sent to a cloud service.");

            var g = Group("Notes");
            var notes = kit.Field(r.Notes, "Write down what you're finding out", true);
            notes.LostKeyboardFocus += (s, e) => { var x = Research.Load(h.Profile.Id); x.Notes = notes.Text; x.Save(h.Profile.Id); };
            g.Children.Add(notes);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
            bool canSave = h.Current != null && !h.Current.ShowsNewTab && !h.Current.IsPrivate;
            var save = Ui.PillButton(h.P, "Save this page", canSave, () => { if (canSave) { h.SaveCurrentToResearch(); Rebuild(); } });
            var export = Ui.PillButton(h.P, "Export notes", false, () =>
            {
                var x = Research.Load(h.Profile.Id); x.Notes = notes.Text; x.Save(h.Profile.Id);
                h.ExportText(h.Profile.Name + ".md", x.Markdown(h.Profile.Name));
            });
            export.Margin = new Thickness(10, 0, 0, 0);
            actions.Children.Add(save); actions.Children.Add(export);
            body.Children.Add(actions);

            body.Children.Add(kit.Section("Saved pages and highlights"));
            if (r.Pages.Count == 0)
            {
                Note("Nothing saved yet. Open a page and choose Save to research in the menu. Select text and choose Highlight to keep quotes here.");
                return;
            }
            foreach (var pg in r.Pages)
            {
                var page = pg;
                Border card;
                var sp = kit.Group(out card);
                card.Margin = new Thickness(0, 0, 0, 10);
                kit.Add(sp, kit.Item(string.IsNullOrWhiteSpace(page.Title) ? Urls.Host(page.Url) : page.Title, Urls.Host(page.Url),
                    () => h.OpenUrl(page.Url), "trash", () => { var x = Research.Load(h.Profile.Id); x.Pages.RemoveAll(q => q.Url == page.Url); x.Save(h.Profile.Id); Rebuild(); }));
                foreach (var hl in page.Highlights)
                {
                    var text = hl;
                    var q = new Border { Background = Ui.B(h.P.Accent.Color.ToString(), 0x1F), Margin = new Thickness(16, 0, 16, 10), Padding = new Thickness(12, 8, 8, 8), CornerRadius = new CornerRadius(8) };
                    var row = new Grid();
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    var tb = Ui.Text(text, 14, h.P.Ink, Fonts.SerifText);
                    tb.TextWrapping = TextWrapping.Wrap; tb.TextTrimming = TextTrimming.None;
                    row.Children.Add(tb);
                    var del = Ui.IconButton(h.P, "close", 11, () => { var x = Research.Load(h.Profile.Id); var sp2 = x.Pages.FirstOrDefault(z => z.Url == page.Url); if (sp2 != null) sp2.Highlights.Remove(text); x.Save(h.Profile.Id); Rebuild(); }, 24);
                    del.ToolTip = "Remove this highlight";
                    Grid.SetColumn(del, 1);
                    row.Children.Add(del);
                    q.Child = row;
                    sp.Children.Add(q);
                }
                body.Children.Add(card);
            }
        }
    }

    // ---------------------------------------------------------------- about

    public class AboutPage : PageBase
    {
        public AboutPage(IFacetHost h) : base(h, "About Facet") { Rebuild(); }

        protected override void Build()
        {
            var logo = new LogoView(120, true, h.P.Accent.Color) { HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) };
            body.Children.Add(logo);
            var g = Group(null);
            kit.Add(g, kit.Info("Version", AppInfo.Version));
            kit.Add(g, kit.Info("Engine", DeviceStatus.Engine()));
            kit.Add(g, kit.Info("License", "MIT"));
            g = Group(null);
            kit.Add(g, kit.Value("info", "Show the welcome again", "", "The animated welcome and quick setup", h.ShowOnboarding));
            Note("Facet collects nothing: no accounts, no analytics, no telemetry. Chromium is bundled inside the app, so Facet doesn't depend on Edge or any other browser.");
            Note("Made by Ayush, written with Claude, an AI model from Anthropic.");
        }
    }
}
