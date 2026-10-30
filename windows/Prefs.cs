using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Facet
{
    public class Bookmark
    {
        public string Title { get; set; } = "";
        public string Url { get; set; } = "";
    }

    /// <summary>Settings and small bits of state, kept as JSON in %LOCALAPPDATA%\Facet.</summary>
    public class Prefs
    {
        public bool BlockTrackers { get; set; } = true;
        public bool BlockThirdPartyCookies { get; set; } = true;
        public bool HttpsOnly { get; set; } = true;
        public bool RestoreTabs { get; set; } = true;
        /// <summary>0 = follow Windows, 1 = light, 2 = dark</summary>
        public int Theme { get; set; } = 0;
        public string SearchEngine { get; set; } = "ddg";
        public long TrackersBlocked { get; set; }
        public long SecureLoads { get; set; }
        public List<string> SessionUrls { get; set; } = new List<string>();
        public int SessionSelected { get; set; }
        public List<Bookmark> Bookmarks { get; set; } = new List<Bookmark>();

        public string UpdateRepo { get; set; } = "ayush04-op/Facet";
        /// <summary>Encrypted with Windows DPAPI for the current user.</summary>
        public string UpdateTokenProtected { get; set; } = "";
        public bool AutoCheckUpdates { get; set; } = true;
        public long LastUpdateCheck { get; set; }
        public string AvailableUpdate { get; set; } = "";

        [JsonIgnore]
        public string UpdateToken
        {
            get
            {
                if (string.IsNullOrEmpty(UpdateTokenProtected)) return "";
                try
                {
                    var raw = ProtectedData.Unprotect(Convert.FromBase64String(UpdateTokenProtected), null, DataProtectionScope.CurrentUser);
                    return Encoding.UTF8.GetString(raw);
                }
                catch { return ""; }
            }
            set
            {
                if (string.IsNullOrWhiteSpace(value)) { UpdateTokenProtected = ""; return; }
                var raw = ProtectedData.Protect(Encoding.UTF8.GetBytes(value.Trim()), null, DataProtectionScope.CurrentUser);
                UpdateTokenProtected = Convert.ToBase64String(raw);
            }
        }

        private static string FilePath { get { return Path.Combine(AppInfo.DataDir, "prefs.json"); } }

        public static Prefs Load()
        {
            try
            {
                if (File.Exists(FilePath))
                    return JsonSerializer.Deserialize<Prefs>(File.ReadAllText(FilePath)) ?? new Prefs();
            }
            catch { /* corrupt file: start fresh */ }
            return new Prefs();
        }

        public void Save()
        {
            try { File.WriteAllText(FilePath, JsonSerializer.Serialize(this)); }
            catch { /* best effort */ }
        }
    }

    public class Engine
    {
        public string Id, Name, Template;
        public Engine(string id, string name, string template) { Id = id; Name = name; Template = template; }
    }

    public static class Urls
    {
        public static readonly Engine[] Engines =
        {
            new Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q={0}"),
            new Engine("brave", "Brave Search", "https://search.brave.com/search?q={0}"),
            new Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q={0}"),
            new Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q={0}"),
            new Engine("google", "Google", "https://www.google.com/search?q={0}"),
        };

        public static Engine EngineById(string id)
        {
            foreach (var e in Engines) if (e.Id == id) return e;
            return Engines[0];
        }

        public static string Resolve(string input, string engineId)
        {
            var t = (input ?? "").Trim();
            if (t.Length == 0) return "about:blank";
            if (t.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || t.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                || t.StartsWith("file://", StringComparison.OrdinalIgnoreCase) || t.StartsWith("about:", StringComparison.OrdinalIgnoreCase))
                return t;
            bool noSpace = t.IndexOf(' ') < 0;
            if (noSpace && (t.Contains(".") || t.StartsWith("localhost", StringComparison.OrdinalIgnoreCase)) )
                return "https://" + t;
            return string.Format(EngineById(engineId).Template, Uri.EscapeDataString(t));
        }

        public static string Host(string url)
        {
            try { return new Uri(url).Host; } catch { return ""; }
        }

        public static bool IsLocal(string host)
        {
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.EndsWith(".local") || host.EndsWith(".localhost");
        }
    }
}
