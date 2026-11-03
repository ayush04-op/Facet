using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Threading;
using Microsoft.Win32;

namespace Facet.Setup
{
    /// <summary>
    /// The work behind the installer: unpacking Facet, shortcuts, the Apps &amp; features entry and the registration that
    /// lets Windows offer Facet as the default browser. Everything is per-user (HKCU, %LOCALAPPDATA%), so no
    /// administrator rights are needed and Facet can update itself in place.
    /// </summary>
    public static class Setup
    {
        public const string AppName = "Facet";
        public const string Publisher = "Ayush";
        public const string Marker = "facet-install.json";
        public const string UninstallerName = "uninstall.exe";
        private const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Facet";
        private const string ClientKey = @"Software\Clients\StartMenuInternet\Facet";

        public static string Version
        {
            get
            {
                var v = Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "1.0";
                int plus = v.IndexOf('+');
                return plus > 0 ? v.Substring(0, plus) : v;
            }
        }

        public static string DefaultDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Facet"); }
        }

        public static string DataDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Facet"); }
        }

        public static bool HasPayload
        {
            get { using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip")) return s != null; }
        }

        /// <summary>The folder of an existing install, from the Apps &amp; features entry.</summary>
        public static string InstalledDir()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(UninstallKey))
            {
                var d = k?.GetValue("InstallLocation") as string;
                return !string.IsNullOrEmpty(d) && File.Exists(Path.Combine(d, "Facet.exe")) ? d : null;
            }
        }

        /// <summary>A folder that isn't Facet's and already has files in it gets a Facet subfolder, so nothing else is touched.</summary>
        public static string SafeDir(string dir)
        {
            dir = Path.GetFullPath(dir.Trim().TrimEnd('\\'));
            bool foreign = Directory.Exists(dir) && !File.Exists(Path.Combine(dir, Marker)) && !File.Exists(Path.Combine(dir, "Facet.exe"))
                           && Directory.GetFileSystemEntries(dir).Length > 0;
            return foreign ? Path.Combine(dir, "Facet") : dir;
        }

        // ------------------------------------------------------------------ running Facet

        public static List<Process> RunningFacets(string dir)
        {
            var list = new List<Process>();
            foreach (var p in Process.GetProcessesByName("Facet"))
            {
                try
                {
                    string exe = p.MainModule.FileName;
                    if (dir == null || exe.StartsWith(dir.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) list.Add(p);
                }
                catch { }
            }
            return list;
        }

        /// <summary>Asks Facet to close (it saves open tabs), then makes sure it and its engine processes are gone.</summary>
        public static void CloseFacet(string dir, int waitMs)
        {
            foreach (var p in RunningFacets(dir)) { try { p.CloseMainWindow(); } catch { } }
            var sw = Stopwatch.StartNew();
            while (RunningFacets(dir).Count > 0 && sw.ElapsedMilliseconds < waitMs) Thread.Sleep(200);
            foreach (var p in RunningFacets(dir)) { try { p.Kill(); p.WaitForExit(5000); } catch { } }
            foreach (var p in Process.GetProcessesByName("CefSharp.BrowserSubprocess"))
            {
                try { if (p.MainModule.FileName.StartsWith(dir.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) { p.Kill(); p.WaitForExit(5000); } } catch { }
            }
        }

        // ------------------------------------------------------------------ install

        /// <summary>progress(fraction 0..1, status text)</summary>
        public static void Install(string dir, bool desktopShortcut, Action<double, string> progress)
        {
            progress(0, "Getting ready");
            Directory.CreateDirectory(dir);
            if (!CanWrite(dir)) throw new Exception("Facet can't be installed in " + dir + " because that folder is read-only. Choose a folder in your user profile.");
            CloseFacet(dir, 8000);

            // an earlier version's files go first (only in a folder that really is Facet's)
            if (File.Exists(Path.Combine(dir, Marker)) || File.Exists(Path.Combine(dir, "Facet.exe")))
            {
                progress(0.02, "Removing the previous version");
                foreach (var f in Directory.GetFiles(dir)) TryDelete(f);
                foreach (var d in Directory.GetDirectories(dir)) { try { Directory.Delete(d, true); } catch { } }
            }

            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
            using (var zip = new ZipArchive(s, ZipArchiveMode.Read))
            {
                long total = 0, done = 0;
                foreach (var e in zip.Entries) total += e.Length;
                string root = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
                var buf = new byte[256 * 1024];
                foreach (var e in zip.Entries)
                {
                    string target = Path.GetFullPath(Path.Combine(dir, e.FullName));
                    if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue; // never write outside the folder
                    if (e.FullName.EndsWith("/") || e.FullName.EndsWith("\\")) { Directory.CreateDirectory(target); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(target));
                    using (var input = e.Open())
                    using (var output = File.Create(target))
                    {
                        int n;
                        while ((n = input.Read(buf, 0, buf.Length)) > 0)
                        {
                            output.Write(buf, 0, n);
                            done += n;
                            progress(0.05 + 0.85 * done / Math.Max(1, total), StatusFor(e.FullName));
                        }
                    }
                }
            }

            progress(0.92, "Adding Facet to the Start menu");
            string exe = Path.Combine(dir, "Facet.exe");
            Shortcut(StartMenuLink(), exe, dir, "A fast, private, uncluttered browser");
            string desk = DesktopLink();
            if (desktopShortcut) Shortcut(desk, exe, dir, "A fast, private, uncluttered browser");
            else TryDelete(desk);

            progress(0.96, "Registering Facet with Windows");
            Register(dir, exe);
            File.WriteAllText(Path.Combine(dir, Marker),
                "{\"version\":\"" + Version + "\",\"installed\":\"" + DateTime.Now.ToString("yyyy-MM-ddTHH:mm:ss") + "\"}");
            progress(1, "Done");
        }

        private static string StatusFor(string entry)
        {
            string n = entry.ToLowerInvariant();
            if (n.Contains("libcef") || n.EndsWith(".pak") || n.Contains("chrome") || n.Contains("locales") || n.Contains("v8") || n.Contains("icudtl"))
                return "Unpacking the Chromium engine";
            if (n.StartsWith("facet")) return "Unpacking Facet";
            return "Unpacking Facet's runtime";
        }

        private static void Register(string dir, string exe)
        {
            string quoted = "\"" + exe + "\"";
            string icon = exe + ",0";
            long sizeKb = 0;
            foreach (var f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories)) { try { sizeKb += new FileInfo(f).Length / 1024; } catch { } }

            using (var k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                string uninst = "\"" + Path.Combine(dir, UninstallerName) + "\"";
                k.SetValue("DisplayName", AppName);
                k.SetValue("DisplayVersion", Version);
                k.SetValue("Publisher", Publisher);
                k.SetValue("DisplayIcon", icon);
                k.SetValue("InstallLocation", dir);
                k.SetValue("UninstallString", uninst);
                k.SetValue("QuietUninstallString", uninst + " /silent");
                k.SetValue("URLInfoAbout", "https://github.com/ayush04-op/Facet");
                k.SetValue("HelpLink", "https://github.com/ayush04-op/Facet/issues");
                k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
                k.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, sizeKb), RegistryValueKind.DWord);
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }

            // "Win+R facet"
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\App Paths\Facet.exe"))
            {
                k.SetValue("", exe);
                k.SetValue("Path", dir);
            }

            // Lets Windows list Facet under Settings > Apps > Default apps, the same way Chrome and Firefox register.
            using (var k = Registry.CurrentUser.CreateSubKey(ClientKey))
            {
                k.SetValue("", AppName);
                using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", icon);
                using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", quoted);
                using (var cap = k.CreateSubKey("Capabilities"))
                {
                    cap.SetValue("ApplicationName", AppName);
                    cap.SetValue("ApplicationDescription", "A fast, private, uncluttered browser");
                    cap.SetValue("ApplicationIcon", icon);
                    using (var sm = cap.CreateSubKey("StartMenu")) sm.SetValue("StartMenuInternet", AppName);
                    using (var u = cap.CreateSubKey("URLAssociations")) { u.SetValue("http", "FacetURL"); u.SetValue("https", "FacetURL"); }
                    using (var fa = cap.CreateSubKey("FileAssociations"))
                        foreach (var ext in new[] { ".htm", ".html", ".shtml", ".xht", ".xhtml", ".svg", ".pdf" }) fa.SetValue(ext, "FacetHTML");
                }
            }
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\RegisteredApplications"))
                k.SetValue(AppName, ClientKey + @"\Capabilities");

            foreach (var prog in new[] { "FacetURL", "FacetHTML" })
            {
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\" + prog))
                {
                    k.SetValue("", prog == "FacetURL" ? "Facet URL" : "Facet HTML Document");
                    k.SetValue("FriendlyTypeName", prog == "FacetURL" ? "Facet URL" : "Facet HTML Document");
                    if (prog == "FacetURL") k.SetValue("URL Protocol", "");
                    using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", icon);
                    using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", quoted + " \"%1\"");
                    using (var a = k.CreateSubKey("Application"))
                    {
                        a.SetValue("ApplicationName", AppName);
                        a.SetValue("ApplicationIcon", icon);
                        a.SetValue("ApplicationCompany", Publisher);
                    }
                }
            }
            NotifyAssociations();
        }

        // ------------------------------------------------------------------ uninstall

        public static void Uninstall(string dir, bool deleteData, Action<double, string> progress)
        {
            progress(0.05, "Closing Facet");
            CloseFacet(dir, 8000);

            progress(0.2, "Removing shortcuts");
            TryDelete(StartMenuLink());
            TryDelete(DesktopLink());

            progress(0.3, "Unregistering Facet");
            foreach (var key in new[] { UninstallKey, ClientKey, @"Software\Classes\FacetURL", @"Software\Classes\FacetHTML", @"Software\Microsoft\Windows\CurrentVersion\App Paths\Facet.exe" })
                try { Registry.CurrentUser.DeleteSubKeyTree(key, false); } catch { }
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\RegisteredApplications", true))
                try { k?.DeleteValue(AppName, false); } catch { }
            NotifyAssociations();

            progress(0.45, "Removing Facet's files");
            if (File.Exists(Path.Combine(dir, Marker)) || File.Exists(Path.Combine(dir, "Facet.exe")))
            {
                var files = Directory.GetFiles(dir, "*", SearchOption.AllDirectories);
                for (int i = 0; i < files.Length; i++)
                {
                    TryDelete(files[i]);
                    if (i % 20 == 0) progress(0.45 + 0.4 * i / Math.Max(1, files.Length), "Removing Facet's files");
                }
                try { Directory.Delete(dir, true); } catch { }
            }

            if (deleteData)
            {
                progress(0.9, "Deleting browsing data");
                try { Directory.Delete(DataDir, true); } catch { }
            }
            progress(1, "Done");
        }

        // ------------------------------------------------------------------ helpers

        public static string StartMenuLink()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Facet.lnk");
        }

        public static string DesktopLink()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Facet.lnk");
        }

        private static bool CanWrite(string dir)
        {
            try
            {
                var f = Path.Combine(dir, ".facet-write-test");
                File.WriteAllText(f, "x");
                File.Delete(f);
                return true;
            }
            catch { return false; }
        }

        private static void TryDelete(string f)
        {
            try { if (File.Exists(f)) { File.SetAttributes(f, FileAttributes.Normal); File.Delete(f); } } catch { }
        }

        [DllImport("shell32.dll")]
        private static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);

        private static void NotifyAssociations() { SHChangeNotify(0x08000000 /* SHCNE_ASSOCCHANGED */, 0, IntPtr.Zero, IntPtr.Zero); }

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        private class CShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        private interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder file, int max, IntPtr fd, int flags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder name, int max);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder dir, int max);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int max);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
            void GetHotkey(out short hotkey);
            void SetHotkey(short hotkey);
            void GetShowCmd(out int cmd);
            void SetShowCmd(int cmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int max, out int index);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, int reserved);
            void Resolve(IntPtr hwnd, int flags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
        }

        private static void Shortcut(string lnk, string target, string workDir, string description)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(lnk));
            var link = (IShellLinkW)new CShellLink();
            link.SetPath(target);
            link.SetWorkingDirectory(workDir);
            link.SetDescription(description);
            link.SetIconLocation(target, 0);
            ((IPersistFile)link).Save(lnk, true);
            Marshal.ReleaseComObject(link);
        }
    }
}
