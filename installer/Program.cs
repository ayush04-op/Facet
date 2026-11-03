using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows;

namespace Facet.Setup
{
    /// <summary>
    /// Facet-Setup.exe            installs (or updates) with the paper setup window
    ///   /silent                  no window (/silent /dir="..." for scripted installs)
    ///   /update /dir="..."       used by Facet's own updater: waits for Facet to close, updates, reopens it
    /// uninstall.exe              (the same program without the payload) removes Facet; /silent for no window
    /// </summary>
    public static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            bool silent = false, update = false, uninstall = false, fromTemp = false, deleteData = false;
            string dir = null;
            foreach (var a in args)
            {
                var s = a.Trim();
                if (s.Equals("/silent", StringComparison.OrdinalIgnoreCase) || s.Equals("/S", StringComparison.Ordinal)) silent = true;
                else if (s.Equals("/update", StringComparison.OrdinalIgnoreCase)) update = true;
                else if (s.Equals("/uninstall", StringComparison.OrdinalIgnoreCase)) uninstall = true;
                else if (s.Equals("/fromtemp", StringComparison.OrdinalIgnoreCase)) fromTemp = true;
                else if (s.Equals("/deletedata", StringComparison.OrdinalIgnoreCase)) deleteData = true;
                else if (s.StartsWith("/dir=", StringComparison.OrdinalIgnoreCase)) dir = s.Substring(5).Trim('"');
            }
            string self = Assembly.GetExecutingAssembly().Location;
            if (!Setup.HasPayload) uninstall = true;

            if (uninstall)
            {
                if (dir == null) dir = Path.GetDirectoryName(self);
                // An uninstaller can't delete the folder it runs from: run a copy from %TEMP% instead.
                if (!fromTemp)
                {
                    string tmp = Path.Combine(Path.GetTempPath(), "facet-uninstall-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".exe");
                    File.Copy(self, tmp, true);
                    var psi = new ProcessStartInfo(tmp, "/uninstall /fromtemp \"/dir=" + dir + "\"" + (silent ? " /silent" : "") + (deleteData ? " /deletedata" : ""))
                    { UseShellExecute = false };
                    var child = Process.Start(psi);
                    if (silent && child != null) { child.WaitForExit(); return child.ExitCode; }
                    return 0;
                }
                try { return Run(Mode.Uninstall, dir, silent, deleteData); }
                finally { DeleteLater(self); }
            }

            if (dir == null) dir = Setup.InstalledDir() ?? Setup.DefaultDir;
            return Run(update ? Mode.Update : Mode.Install, Setup.SafeDir(dir), silent, false);
        }

        private static int Run(Mode mode, string dir, bool silent, bool deleteData)
        {
            if (silent)
            {
                try
                {
                    if (mode == Mode.Uninstall) Setup.Uninstall(dir, deleteData, (f, s) => { });
                    else
                    {
                        if (mode == Mode.Update)
                        {
                            var sw = Stopwatch.StartNew();
                            while (Setup.RunningFacets(dir).Count > 0 && sw.ElapsedMilliseconds < 20000) System.Threading.Thread.Sleep(250);
                        }
                        Setup.Install(dir, File.Exists(Setup.DesktopLink()), (f, s) => { });
                        if (mode == Mode.Update) Process.Start(new ProcessStartInfo(Path.Combine(dir, "Facet.exe")) { UseShellExecute = true });
                    }
                    return 0;
                }
                catch (Exception e)
                {
                    try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "facet-setup-error.txt"), e.ToString()); } catch { }
                    return 1;
                }
            }
            var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
            app.Run(new InstallerWindow(mode, dir));
            return 0;
        }

        /// <summary>Removes the temporary uninstaller copy once it has exited.</summary>
        private static void DeleteLater(string file)
        {
            try
            {
                Process.Start(new ProcessStartInfo("cmd.exe", "/c ping 127.0.0.1 -n 3 >nul & del /f /q \"" + file + "\"")
                { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            }
            catch { }
        }
    }
}
