using System;
using System.Collections.Generic;
using System.IO;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Windows;
using CefSharp;

namespace Facet
{
    public class InfoLine
    {
        public string Label, Value;
        public InfoLine(string l, string v) { Label = l; Value = v; }
    }

    public static class DeviceStatus
    {
        public static string Bytes(long n)
        {
            double mb = n / (1024.0 * 1024.0);
            return mb >= 1024 ? (mb / 1024.0).ToString("0.0") + " GB" : mb.ToString("0") + " MB";
        }

        public static string Engine()
        {
            try { return "Chromium " + Cef.ChromiumVersion; } catch { return "Chromium"; }
        }

        public static List<InfoLine> Build()
        {
            var l = new List<InfoLine>();
            l.Add(new InfoLine("Version", AppInfo.Version));
            try
            {
                var f = Path.Combine(AppContext.BaseDirectory, "Facet.dll");
                if (File.Exists(f)) l.Add(new InfoLine("Installed", File.GetLastWriteTime(f).ToString("d MMM yyyy")));
            }
            catch { }
            l.Add(new InfoLine("Engine", Engine()));
            l.Add(new InfoLine("Folder", AppContext.BaseDirectory.TrimEnd('\\')));
            return l;
        }

        public static List<InfoLine> Device()
        {
            var l = new List<InfoLine>();
            l.Add(new InfoLine("Windows", RuntimeInformation.OSDescription));
            l.Add(new InfoLine("Processor", Environment.ProcessorCount + " threads, " + RuntimeInformation.OSArchitecture));
            try
            {
                var gc = GC.GetGCMemoryInfo();
                l.Add(new InfoLine("Memory", Bytes(gc.TotalAvailableMemoryBytes)));
            }
            catch { }
            try
            {
                var d = new DriveInfo(Path.GetPathRoot(AppContext.BaseDirectory));
                l.Add(new InfoLine("Storage free", Bytes(d.AvailableFreeSpace)));
            }
            catch { }
            try
            {
                var st = SystemParameters.PowerLineStatus;
                l.Add(new InfoLine("Power", st == PowerLineStatus.Online ? "Plugged in" : st == PowerLineStatus.Offline ? "On battery" : "Unknown"));
            }
            catch { }
            l.Add(new InfoLine("Network", NetworkIsUp() ? "Online" : "Offline"));
            return l;
        }

        public static bool NetworkIsUp()
        {
            try { return NetworkInterface.GetIsNetworkAvailable(); } catch { return true; }
        }

        public static KeyValuePair<bool, string> Readiness()
        {
            var problems = new List<string>();
            if (!NetworkIsUp()) problems.Add("no connection");
            try
            {
                var d = new DriveInfo(Path.GetPathRoot(AppContext.BaseDirectory));
                if (d.AvailableFreeSpace < 500L * 1024 * 1024) problems.Add("low storage");
            }
            catch { }
            if (!Updater.CanWriteHere()) problems.Add("this folder is read-only (move Facet out of Program Files)");
            if (problems.Count == 0) return new KeyValuePair<bool, string>(true, "Ready to update");
            var s = string.Join(", ", problems);
            return new KeyValuePair<bool, string>(false, char.ToUpper(s[0]) + s.Substring(1));
        }
    }
}
