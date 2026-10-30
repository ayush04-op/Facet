using System;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Facet
{
    /// <summary>Software update page: update state, this build, device status and update source.</summary>
    public class UpdatesPage : Grid
    {
        private enum S { Checking, UpToDate, Available, Downloading, Ready, Error }

        private readonly Palette p;
        private readonly Prefs prefs;
        private readonly Kit kit;
        private readonly Action<string, string, string, bool, Action<string>> edit;
        private readonly StackPanel body;
        private S state = S.Checking;
        private Release release;
        private string error = "";
        private string zip;
        private CancellationTokenSource cts;
        private ProgressBar bar;
        private TextBlock progressText;

        public UpdatesPage(Palette p, Prefs prefs, Action onBack, Action<string, string, string, bool, Action<string>> edit)
        {
            this.p = p; this.prefs = prefs; this.edit = edit;
            kit = new Kit(p);
            var page = kit.Page("Software update", () => { if (cts != null) cts.Cancel(); onBack(); }, out body);
            Children.Add(page);
            Render();
            Check();
        }

        private async void Check()
        {
            state = S.Checking; Render();
            try
            {
                var r = await Updater.LatestAsync(prefs.UpdateRepo, prefs.UpdateToken);
                prefs.LastUpdateCheck = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                release = r;
                if (r != null && Updater.IsNewer(r.Tag, AppInfo.Version)) { prefs.AvailableUpdate = r.Tag; state = S.Available; }
                else { prefs.AvailableUpdate = ""; state = S.UpToDate; }
                prefs.Save();
            }
            catch (Exception e) { error = e.Message; state = S.Error; }
            Render();
        }

        private async void Download()
        {
            if (release == null) return;
            state = S.Downloading; Render();
            cts = new CancellationTokenSource();
            try
            {
                zip = await Updater.DownloadAsync(release, prefs.UpdateToken, (done, total) =>
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        if (state != S.Downloading || bar == null) return;
                        bar.Value = total > 0 ? 100.0 * done / total : 0;
                        progressText.Text = DeviceStatus.Bytes(done) + (total > 0 ? " of " + DeviceStatus.Bytes(total) : "");
                    })), cts.Token);
                state = S.Ready;
            }
            catch (OperationCanceledException) { state = S.Available; }
            catch (Exception e) { error = e.Message; state = S.Error; }
            Render();
        }

        private void Install()
        {
            try { Updater.ApplyAndRestart(zip); }
            catch (Exception e) { error = e.Message; state = S.Error; Render(); }
        }

        private static string Ago(long ms)
        {
            if (ms == 0) return "never";
            long s = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - ms) / 1000;
            if (s < 60) return "just now";
            if (s < 3600) return (s / 60) + " min ago";
            if (s < 86400) return (s / 3600) + " h ago";
            return (s / 86400) + " d ago";
        }

        private void Render()
        {
            body.Children.Clear();
            bar = null; progressText = null;
            string version = AppInfo.Version;
            string title, sub;
            switch (state)
            {
                case S.Checking: title = "Checking for updates"; sub = "Contacting GitHub Releases for " + prefs.UpdateRepo; break;
                case S.UpToDate: title = "Facet is up to date"; sub = "Version " + version + " · Last checked " + Ago(prefs.LastUpdateCheck); break;
                case S.Available:
                    title = (release != null ? release.Tag : "A new version") + " is ready";
                    sub = (release != null && release.Size > 0 ? DeviceStatus.Bytes(release.Size) + " · " : "") + "You have version " + version; break;
                case S.Downloading: title = "Downloading " + (release != null ? release.Tag : "update"); sub = "Keep Facet open until this finishes"; break;
                case S.Ready: title = "Ready to install"; sub = "Facet will close, update itself and open again."; break;
                default: title = "Couldn't update"; sub = error; break;
            }

            var card = Ui.Box(p.Surface, 18, p.Line);
            card.Padding = new Thickness(22);
            card.Margin = new Thickness(0, 12, 0, 0);
            var col = new StackPanel();
            col.Children.Add(Ui.Tag("Status", p.Accent));
            var t = Ui.Text(title, 28, p.Ink, Fonts.Serif); t.Margin = new Thickness(0, 10, 0, 0); t.TextTrimming = TextTrimming.None; t.TextWrapping = TextWrapping.Wrap;
            col.Children.Add(t);
            var s = Ui.Text(sub, 14, p.Muted, Fonts.Sans); s.Margin = new Thickness(0, 8, 0, 0); s.TextWrapping = TextWrapping.Wrap; s.TextTrimming = TextTrimming.None;
            col.Children.Add(s);
            if (state == S.Downloading)
            {
                bar = new ProgressBar { Height = 6, Minimum = 0, Maximum = 100, Foreground = p.Accent, Background = p.Line, BorderThickness = new Thickness(0), Margin = new Thickness(0, 18, 0, 0) };
                col.Children.Add(bar);
                progressText = Ui.Text("Starting", 12.5, p.Muted, Fonts.Mono); progressText.Margin = new Thickness(0, 8, 0, 0);
                col.Children.Add(progressText);
            }
            string label = null; Action act = null; bool primary = true;
            switch (state)
            {
                case S.UpToDate: label = "Check again"; act = Check; primary = false; break;
                case S.Error: label = "Try again"; act = Check; primary = false; break;
                case S.Available: label = "Download and install"; act = Download; break;
                case S.Downloading: label = "Cancel"; act = () => { if (cts != null) cts.Cancel(); }; primary = false; break;
                case S.Ready: label = "Install and restart"; act = Install; break;
            }
            if (label != null)
            {
                var btn = Ui.PillButton(p, label, primary, act);
                btn.Margin = new Thickness(0, 20, 0, 0); btn.HorizontalAlignment = HorizontalAlignment.Left;
                col.Children.Add(btn);
            }
            card.Child = col;
            body.Children.Add(card);

            if (release != null && state != S.UpToDate && !string.IsNullOrWhiteSpace(release.Notes))
            {
                body.Children.Add(kit.Section("What's new"));
                Border g;
                var gp = kit.Group(out g);
                var n = Ui.Text(release.Notes.Trim().Length > 1200 ? release.Notes.Trim().Substring(0, 1200) : release.Notes.Trim(), 13.5, p.Ink, Fonts.Sans);
                n.TextWrapping = TextWrapping.Wrap; n.TextTrimming = TextTrimming.None; n.Margin = new Thickness(16, 14, 16, 14);
                gp.Children.Add(n);
                body.Children.Add(g);
            }

            body.Children.Add(kit.Section("This build"));
            Border b1; var build = kit.Group(out b1);
            foreach (var l in DeviceStatus.Build()) kit.Add(build, kit.Info(l.Label, l.Value));
            body.Children.Add(b1);

            body.Children.Add(kit.Section("Device status"));
            Border b2; var dev = kit.Group(out b2);
            kit.Add(dev, kit.Info("Update readiness", DeviceStatus.Readiness().Value));
            foreach (var l in DeviceStatus.Device()) kit.Add(dev, kit.Info(l.Label, l.Value));
            body.Children.Add(b2);

            body.Children.Add(kit.Section("Update source"));
            Border b3; var src = kit.Group(out b3);
            kit.Add(src, kit.Value("link", "Repository", prefs.UpdateRepo, null, () =>
                edit("Update repository", "owner/name", prefs.UpdateRepo, false, v => { prefs.UpdateRepo = v.Trim(); prefs.Save(); Check(); })));
            kit.Add(src, kit.Value("lock", "Access token", string.IsNullOrEmpty(prefs.UpdateToken) ? "Not set" : "Set", null, () =>
                edit("GitHub access token", "Only needed for private repositories", "", true, v => { prefs.UpdateToken = v; prefs.Save(); Check(); })));
            kit.Add(src, kit.Toggle("refresh", "Check automatically", "Looks for a new release when Facet starts, at most twice a day.", prefs.AutoCheckUpdates, v => { prefs.AutoCheckUpdates = v; prefs.Save(); }));
            body.Children.Add(b3);
            body.Children.Add(kit.Note("Last checked " + Ago(prefs.LastUpdateCheck) + ". The token is encrypted for your Windows account and only sent to api.github.com."));
            Kit.FadeIn(card);
        }
    }
}
