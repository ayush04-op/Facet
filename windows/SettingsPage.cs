using System;
using System.Windows;
using System.Windows.Controls;

namespace Facet
{
    public class SettingsPage : Grid
    {
        private readonly Palette p;
        private readonly Prefs prefs;
        private readonly Kit kit;
        private readonly StackPanel body;
        private readonly Action onBack, onToggled, openEngine, openTheme, clearData, openUpdates;

        public SettingsPage(Palette p, Prefs prefs, Action onBack, Action onToggled, Action openEngine, Action openTheme, Action clearData, Action openUpdates)
        {
            this.p = p; this.prefs = prefs; this.onBack = onBack; this.onToggled = onToggled;
            this.openEngine = openEngine; this.openTheme = openTheme; this.clearData = clearData; this.openUpdates = openUpdates;
            kit = new Kit(p);
            Children.Add(kit.Page("Settings", onBack, out body));
            Rebuild();
        }

        public void Rebuild()
        {
            body.Children.Clear();
            Border g;
            StackPanel gp;

            body.Children.Add(kit.Section("Software"));
            gp = kit.Group(out g);
            string avail = prefs.AvailableUpdate;
            kit.Add(gp, kit.Value("download", "Software update", avail.Length > 0 ? avail + " available" : "Version " + AppInfo.Version,
                "Updates, this build and device status", openUpdates));
            body.Children.Add(g);

            body.Children.Add(kit.Section("Privacy and security"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Toggle("shield", "Block ads and trackers", "Known advertising, analytics and fingerprinting hosts are blocked before they load.", prefs.BlockTrackers, v => { prefs.BlockTrackers = v; Changed(); }));
            kit.Add(gp, kit.Toggle("private", "Block third-party cookies", "Cookies from other sites are neither sent nor saved.", prefs.BlockThirdPartyCookies, v => { prefs.BlockThirdPartyCookies = v; Changed(); }));
            kit.Add(gp, kit.Toggle("lock", "HTTPS-only mode", "Pages are opened over HTTPS instead of HTTP.", prefs.HttpsOnly, v => { prefs.HttpsOnly = v; Changed(); }));
            kit.Add(gp, kit.Info("Passkeys", "Experimental (depends on the engine)"));
            body.Children.Add(g);

            body.Children.Add(kit.Section("Browsing"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Value("search", "Search engine", Urls.EngineById(prefs.SearchEngine).Name, null, openEngine));
            kit.Add(gp, kit.Toggle("tabs", "Restore tabs on launch", "Private tabs are never saved.", prefs.RestoreTabs, v => { prefs.RestoreTabs = v; Changed(); }));
            body.Children.Add(g);

            body.Children.Add(kit.Section("Appearance"));
            gp = kit.Group(out g);
            string theme = prefs.Theme == 1 ? "Paper (light)" : prefs.Theme == 2 ? "Paper Dark" : "Match Windows";
            kit.Add(gp, kit.Value("device", "Theme", theme, null, openTheme));
            body.Children.Add(g);

            body.Children.Add(kit.Section("Data"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Value("trash", "Clear browsing data", "Cookies, cache, site data", null, clearData));
            body.Children.Add(g);

            body.Children.Add(kit.Section("Activity"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Info("Trackers blocked", (prefs.TrackersBlocked + Stats.Blocked).ToString("N0")));
            kit.Add(gp, kit.Info("Secure page loads", (prefs.SecureLoads + Stats.Secure).ToString("N0")));
            body.Children.Add(g);

            body.Children.Add(kit.Section("About"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Info("Version", AppInfo.Version));
            kit.Add(gp, kit.Info("Engine", DeviceStatus.Engine()));
            kit.Add(gp, kit.Info("License", "MIT"));
            body.Children.Add(g);
            body.Children.Add(kit.Note("Facet collects nothing: no accounts, no analytics, no telemetry. Chromium is bundled inside the app, so Facet doesn't depend on Edge or any other browser."));
            body.Children.Add(kit.Note("Made by Ayush, written with Claude, an AI model from Anthropic."));
            Kit.FadeIn(body);
        }

        private void Changed() { prefs.Save(); onToggled(); }
    }
}
