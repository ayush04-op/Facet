using System;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows;
using CefSharp;

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
                var d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Facet");
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
            using (var mutex = new Mutex(true, "Facet.Browser.SingleInstance", out created))
            {
                if (!created) return 0;

                var s = new CefSettings();
                s.CachePath = Path.Combine(AppInfo.DataDir, "cef");
                s.LogSeverity = LogSeverity.Disable;
                s.PersistSessionCookies = false;
                s.CefCommandLineArgs["disable-features"] = "TranslateUI";
                Cef.Initialize(s, performDependencyCheck: false, browserProcessHandler: null);

                var app = new Application();
                app.ShutdownMode = ShutdownMode.OnMainWindowClose;
                string startUrl = args.Length > 0 ? args[0] : null;
                app.Run(new MainWindow(startUrl));

                Cef.Shutdown();
            }
            return 0;
        }
    }
}
