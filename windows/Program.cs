using System;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows;
using CefSharp;
using CefSharp.Wpf;

namespace Facet
{
    public static class AppInfo
    {
        public static string Version
        {
            get
            {
                var v = Assembly.GetEntryAssembly()?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "1.0";
                int plus = v.IndexOf('+');
                return plus > 0 ? v.Substring(0, plus) : v;
            }
        }

        public static string DataDir
        {
            get
            {
                // FACET_DATA_DIR gives test runs (and CI) their own folder, so they never touch a real profile
                var custom = Environment.GetEnvironmentVariable("FACET_DATA_DIR");
                var d = !string.IsNullOrEmpty(custom) ? custom : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Facet");
                Directory.CreateDirectory(d);
                return d;
            }
        }
    }

    public static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            bool created;
            string startUrl = args.Length > 0 ? ToUrl(args[0]) : null;
            using (var mutex = new Mutex(true, "Facet.Browser.SingleInstance", out created))
            {
                // Facet is already open: hand it the link (from another app, now that Facet can be the default browser)
                if (!created) { Forward(startUrl ?? ""); return 0; }

                var s = new CefSettings();
                s.CachePath = Path.Combine(AppInfo.DataDir, "cef");
                // profiles keep their own storage in folders under this one
                s.RootCachePath = s.CachePath;
                bool smoke = Environment.GetEnvironmentVariable("FACET_SMOKE") == "1";
                s.LogSeverity = smoke ? LogSeverity.Warning : LogSeverity.Disable;
                if (smoke) s.LogFile = Path.Combine(AppInfo.DataDir, "cef.log");
                s.PersistSessionCookies = false;
                // Switch off Chromium services Facet has no use for. MediaRouter (casting) scans the local network, which
                // costs CPU and is what makes Windows Firewall ask about CefSharp.BrowserSubprocess. The spare renderer
                // is an idle process Chromium keeps warm in case a new site opens.
                s.CefCommandLineArgs["disable-features"] = "TranslateUI,MediaRouter,DialMediaRouteProvider,OptimizationHints,SpareRendererForSitePerProcess,AutofillServerCommunication";
                s.CefCommandLineArgs["disable-background-networking"] = "1";
                // eased wheel scrolling on websites, matching the scrolling in Facet's own pages
                s.CefCommandLineArgs["enable-smooth-scrolling"] = "1";
                // extensions' own files (images, fonts, pages they show) are served from facet-extension://ext/<id>/...
                s.RegisterScheme(new CefCustomScheme
                {
                    SchemeName = Extensions.Scheme,
                    SchemeHandlerFactory = new ExtensionFiles(),
                    IsStandard = true, IsSecure = true, IsCorsEnabled = true, IsFetchEnabled = true
                });
                s.CefCommandLineArgs["disable-component-update"] = "1";
                s.CefCommandLineArgs["disable-domain-reliability"] = "1";
                Cef.Initialize(s, performDependencyCheck: false, browserProcessHandler: null);

                Anim.MatchDisplay();
                var app = new Application();
                app.ShutdownMode = ShutdownMode.OnMainWindowClose;
                app.Run(new MainWindow(startUrl));

                Cef.Shutdown();
            }
            return 0;
        }

        public static string PipeName { get { return "Facet.Browser.Open." + Environment.UserName; } }

        /// <summary>Links and files arrive as arguments; files become file:/// addresses.</summary>
        public static string ToUrl(string arg)
        {
            arg = (arg ?? "").Trim().Trim('"');
            if (arg.Length == 0) return null;
            try { if (File.Exists(arg)) return new Uri(Path.GetFullPath(arg)).AbsoluteUri; } catch { }
            return arg;
        }

        private static void Forward(string url)
        {
            try
            {
                using (var pipe = new System.IO.Pipes.NamedPipeClientStream(".", PipeName, System.IO.Pipes.PipeDirection.Out))
                {
                    pipe.Connect(3000);
                    var data = System.Text.Encoding.UTF8.GetBytes(url);
                    pipe.Write(data, 0, data.Length);
                }
            }
            catch { /* the other Facet is closing; nothing to hand over */ }
        }
    }
}
