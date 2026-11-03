using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using CefSharp;

namespace Facet
{
    /// <summary>Small JSON files in %LOCALAPPDATA%\Facet. Everything Facet keeps stays on this PC.</summary>
    static class Json
    {
        public static T Load<T>(string name) where T : new()
        {
            try
            {
                var f = Path.Combine(AppInfo.DataDir, name);
                if (File.Exists(f)) return JsonSerializer.Deserialize<T>(File.ReadAllText(f)) ?? new T();
            }
            catch { /* corrupt file: start fresh */ }
            return new T();
        }

        public static void Save(string name, object value)
        {
            try { File.WriteAllText(Path.Combine(AppInfo.DataDir, name), JsonSerializer.Serialize(value)); } catch { }
        }

        public static void Delete(string name)
        {
            try { File.Delete(Path.Combine(AppInfo.DataDir, name)); } catch { }
        }
    }

    /// <summary>Small secrets (the AI key), encrypted with Windows' per-user data protection.</summary>
    static class Secret
    {
        public static string Protect(string plain)
        {
            if (string.IsNullOrWhiteSpace(plain)) return "";
            return Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(plain.Trim()), null, DataProtectionScope.CurrentUser));
        }

        public static string Unprotect(string stored)
        {
            if (string.IsNullOrEmpty(stored)) return "";
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(stored), null, DataProtectionScope.CurrentUser)); }
            catch { return ""; }
        }
    }

    // ---------------------------------------------------------------- profiles

    public class Profile
    {
        public const int Normal = 0, Research = 1, Temporary = 2;
        public string Id { get; set; } = "";
        public string Name { get; set; } = "";
        public int Kind { get; set; }
        public string Label { get { return Kind == Research ? "Research" : Kind == Temporary ? "Temporary" : "Profile"; } }
    }

    /// <summary>What a profile keeps apart from the others (besides its cookies and site data).</summary>
    public class ProfileData
    {
        public List<Bookmark> Bookmarks { get; set; } = new List<Bookmark>();
        public List<string> SessionUrls { get; set; } = new List<string>();
        public int SessionSelected { get; set; }
    }

    /// <summary>
    /// Profiles keep tabs, bookmarks, cookies, logins and site data apart, as on Android. The Personal profile uses
    /// the original storage, so nothing changes for people who never add one. Temporary sessions keep everything
    /// in memory and are erased when you leave them.
    /// </summary>
    public static class Profiles
    {
        private class Store { public List<Profile> Items { get; set; } = new List<Profile>(); }

        public static List<Profile> All()
        {
            var list = Json.Load<Store>("profiles.json").Items;
            if (!list.Any(p => p.Id == "personal")) list.Insert(0, new Profile { Id = "personal", Name = "Personal", Kind = Profile.Normal });
            return list;
        }

        private static void Save(List<Profile> list) { Json.Save("profiles.json", new Store { Items = list }); }

        public static Profile Current(Prefs prefs)
        {
            var all = All();
            return all.FirstOrDefault(p => p.Id == prefs.ProfileId) ?? all[0];
        }

        public static Profile Add(string name, int kind)
        {
            var list = All();
            var p = new Profile { Id = "p" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString("x"), Name = string.IsNullOrWhiteSpace(name) ? "Profile" : name.Trim(), Kind = kind };
            list.Add(p);
            Save(list);
            return p;
        }

        public static void Rename(string id, string name)
        {
            var list = All();
            var p = list.FirstOrDefault(x => x.Id == id);
            if (p != null) { p.Name = string.IsNullOrWhiteSpace(name) ? "Profile" : name.Trim(); Save(list); }
        }

        /// <summary>Removes the profile and everything stored for it: tabs, bookmarks, notes, history, cookies.</summary>
        public static void Remove(string id)
        {
            if (id == "personal") return;
            var list = All();
            list.RemoveAll(x => x.Id == id);
            Save(list);
            foreach (var f in new[] { "profile-" + id + ".json", "history-" + id + ".json", "research-" + id + ".json" }) Json.Delete(f);
            try { Directory.Delete(CachePath(id), true); } catch { /* the engine may still hold it until restart */ }
        }

        public static ProfileData Data(Prefs prefs, Profile p)
        {
            if (p.Id == "personal") return new ProfileData { Bookmarks = prefs.Bookmarks, SessionUrls = prefs.SessionUrls, SessionSelected = prefs.SessionSelected };
            if (p.Kind == Profile.Temporary) return new ProfileData();
            return Json.Load<ProfileData>("profile-" + p.Id + ".json");
        }

        public static void SaveData(Prefs prefs, Profile p, ProfileData d)
        {
            if (p.Id == "personal")
            {
                prefs.Bookmarks = d.Bookmarks; prefs.SessionUrls = d.SessionUrls; prefs.SessionSelected = d.SessionSelected;
                prefs.Save();
                return;
            }
            if (p.Kind == Profile.Temporary) return;
            Json.Save("profile-" + p.Id + ".json", d);
        }

        public static string CachePath(string id) { return Path.Combine(AppInfo.DataDir, "cef", "profiles", id); }

        private static readonly Dictionary<string, IRequestContext> contexts = new Dictionary<string, IRequestContext>();

        /// <summary>The engine storage for a profile: null means the main storage (Personal).</summary>
        public static IRequestContext Context(Profile p)
        {
            if (p.Id == "personal") return null;
            IRequestContext c;
            if (contexts.TryGetValue(p.Id, out c)) return c;
            // An empty cache path keeps a temporary session entirely in memory.
            c = new RequestContext(new RequestContextSettings { CachePath = p.Kind == Profile.Temporary ? "" : CachePath(p.Id), PersistSessionCookies = false });
            contexts[p.Id] = c;
            return c;
        }

        /// <summary>Forgets a temporary session's in-memory storage, so the next visit starts empty.</summary>
        public static void Forget(string id) { contexts.Remove(id); }
    }

    // ---------------------------------------------------------------- site permissions

    public class SitePerm
    {
        public const string Camera = "camera", Microphone = "microphone", Location = "location", Notifications = "notifications";
        public static readonly string[] Types = { Camera, Microphone, Location, Notifications };

        public string Host { get; set; } = "";
        public string Type { get; set; } = "";
        public bool Allowed { get; set; }
        public long Granted { get; set; }
        public long LastUsed { get; set; }

        public static string Title(string type)
        {
            return type == Camera ? "Camera" : type == Microphone ? "Microphone" : type == Location ? "Location" : "Notifications";
        }
    }

    /// <summary>Remembered answers to permission questions (only when the person ticks "Remember"), per site.</summary>
    public static class SitePerms
    {
        private class Store { public List<SitePerm> Items { get; set; } = new List<SitePerm>(); }
        private static readonly object gate = new object();

        public static List<SitePerm> All() { lock (gate) return Json.Load<Store>("site-permissions.json").Items; }
        private static void Save(List<SitePerm> l) { Json.Save("site-permissions.json", new Store { Items = l }); }
        private static long Now { get { return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); } }

        public static SitePerm Find(string host, string type) { return All().FirstOrDefault(p => p.Host == host && p.Type == type); }

        public static void Set(string host, string type, bool allowed)
        {
            lock (gate)
            {
                var l = All();
                l.RemoveAll(p => p.Host == host && p.Type == type);
                l.Add(new SitePerm { Host = host, Type = type, Allowed = allowed, Granted = Now, LastUsed = Now });
                Save(l);
            }
        }

        public static void Touch(string host, string type)
        {
            lock (gate)
            {
                var l = All();
                var p = l.FirstOrDefault(x => x.Host == host && x.Type == type);
                if (p != null) { p.LastUsed = Now; Save(l); }
            }
        }

        public static void Remove(string host, string type) { lock (gate) { var l = All(); l.RemoveAll(p => p.Host == host && p.Type == type); Save(l); } }
        public static void ClearHost(string host) { lock (gate) { var l = All(); l.RemoveAll(p => p.Host == host); Save(l); } }
        public static void ClearAll() { lock (gate) Save(new List<SitePerm>()); }
        public static List<string> Hosts() { return All().Select(p => p.Host).Distinct().OrderBy(h => h).ToList(); }

        /// <summary>Sites allowed to use the camera, microphone or location a month or more ago.</summary>
        public static List<SitePerm> Reminders()
        {
            long limit = Now - 30L * 24 * 3600 * 1000;
            return All().Where(p => p.Allowed && p.Type != SitePerm.Notifications && p.Granted < limit).ToList();
        }

        public static string Ago(long t)
        {
            if (t == 0) return "never";
            long days = (Now - t) / (24L * 3600 * 1000);
            if (days <= 0) return "today";
            if (days == 1) return "yesterday";
            if (days < 60) return days + " days ago";
            return DateTimeOffset.FromUnixTimeMilliseconds(t).LocalDateTime.ToString("d MMM yyyy");
        }
    }

    // ---------------------------------------------------------------- history

    public class HistoryEntry
    {
        public string Url { get; set; } = "";
        public string Title { get; set; } = "";
        public long Time { get; set; }
    }

    /// <summary>Pages visited in normal tabs of a saved profile (never private tabs or temporary sessions). Newest first, at most 2000.</summary>
    public static class History
    {
        private class Store { public List<HistoryEntry> Items { get; set; } = new List<HistoryEntry>(); }
        private static string loadedFor;
        private static List<HistoryEntry> cache;

        private static string File(string profileId) { return profileId == "personal" ? "history.json" : "history-" + profileId + ".json"; }

        public static List<HistoryEntry> All(string profileId)
        {
            if (loadedFor != profileId || cache == null) { cache = Json.Load<Store>(File(profileId)).Items; loadedFor = profileId; }
            return cache;
        }

        public static void Add(string profileId, string url, string title)
        {
            if (string.IsNullOrEmpty(url) || !(url.StartsWith("http://") || url.StartsWith("https://"))) return;
            var l = All(profileId);
            var old = l.FirstOrDefault(e => e.Url == url);
            if (old != null) l.Remove(old);
            l.Insert(0, new HistoryEntry { Url = url, Title = string.IsNullOrWhiteSpace(title) ? (old != null ? old.Title : "") : title, Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
            if (l.Count > 2000) l.RemoveRange(2000, l.Count - 2000);
            Json.Save(File(profileId), new Store { Items = l });
        }

        public static void SetTitle(string profileId, string url, string title)
        {
            var e = All(profileId).FirstOrDefault(x => x.Url == url);
            if (e == null || string.IsNullOrWhiteSpace(title) || e.Title == title) return;
            e.Title = title;
            Json.Save(File(profileId), new Store { Items = All(profileId) });
        }

        public static void Remove(string profileId, string url)
        {
            All(profileId).RemoveAll(e => e.Url == url);
            Json.Save(File(profileId), new Store { Items = All(profileId) });
        }

        public static void Clear(string profileId)
        {
            All(profileId).Clear();
            Json.Save(File(profileId), new Store { Items = All(profileId) });
        }
    }

    // ---------------------------------------------------------------- research

    public class SavedPage
    {
        public string Url { get; set; } = "";
        public string Title { get; set; } = "";
        public long Time { get; set; }
        public List<string> Highlights { get; set; } = new List<string>();
    }

    /// <summary>A research profile's notes and saved pages with their highlights. On this PC only.</summary>
    public class Research
    {
        public string Notes { get; set; } = "";
        public List<SavedPage> Pages { get; set; } = new List<SavedPage>();

        public static Research Load(string profileId) { return Json.Load<Research>("research-" + profileId + ".json"); }
        public void Save(string profileId) { Json.Save("research-" + profileId + ".json", this); }

        public void SavePage(string url, string title)
        {
            if (Pages.Any(p => p.Url == url)) return;
            Pages.Insert(0, new SavedPage { Url = url, Title = title, Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });
        }

        public void AddHighlight(string url, string title, string text)
        {
            var p = Pages.FirstOrDefault(x => x.Url == url);
            if (p == null) { p = new SavedPage { Url = url, Title = title, Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() }; Pages.Insert(0, p); }
            if (!p.Highlights.Contains(text)) p.Highlights.Add(text);
        }

        public List<string> HighlightsFor(string url)
        {
            var p = Pages.FirstOrDefault(x => x.Url == url);
            return p != null ? p.Highlights : new List<string>();
        }

        public string Markdown(string topic)
        {
            var sb = new StringBuilder();
            sb.Append("# ").Append(topic).Append("\n\n");
            if (!string.IsNullOrWhiteSpace(Notes)) sb.Append("## Notes\n\n").Append(Notes.Trim()).Append("\n\n");
            if (Pages.Count > 0) sb.Append("## Sources\n\n");
            foreach (var p in Pages)
            {
                sb.Append("### [").Append(string.IsNullOrWhiteSpace(p.Title) ? p.Url : p.Title).Append("](").Append(p.Url).Append(")\n\n");
                foreach (var h in p.Highlights) sb.Append("> ").Append(h.Replace("\n", " ")).Append("\n\n");
            }
            return sb.ToString();
        }
    }

    // ---------------------------------------------------------------- downloads

    public class DownloadEntry
    {
        public int Id { get; set; }
        public string Url { get; set; } = "";
        public string Name { get; set; } = "";
        public string Path { get; set; } = "";
        public long Total { get; set; }
        public long Received { get; set; }
        /// <summary>0 downloading, 1 done, 2 failed, 3 cancelled</summary>
        public int State { get; set; }
        public long Time { get; set; }

        public string Category
        {
            get
            {
                string e = System.IO.Path.GetExtension(Name).ToLowerInvariant();
                if (new[] { ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp", ".heic", ".avif", ".ico" }.Contains(e)) return "Images";
                if (new[] { ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v" }.Contains(e)) return "Video";
                if (new[] { ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".aac" }.Contains(e)) return "Audio";
                if (new[] { ".zip", ".7z", ".rar", ".tar", ".gz", ".xz", ".bz2" }.Contains(e)) return "Archives";
                if (new[] { ".exe", ".msi", ".msix", ".appx", ".apk", ".bat", ".cmd", ".ps1" }.Contains(e)) return "Programs";
                if (new[] { ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv", ".odt", ".rtf", ".epub" }.Contains(e)) return "Documents";
                return "Other";
            }
        }
    }

    /// <summary>The list on the Downloads page. The files themselves are in the Downloads folder.</summary>
    public static class Downloads
    {
        private class Store { public List<DownloadEntry> Items { get; set; } = new List<DownloadEntry>(); }
        private static List<DownloadEntry> items;
        public static event Action Changed;

        public static List<DownloadEntry> All() { if (items == null) items = Json.Load<Store>("downloads.json").Items; return items; }

        public static void Update(DownloadEntry e, bool save)
        {
            var l = All();
            if (!l.Contains(e)) l.Insert(0, e);
            if (l.Count > 300) l.RemoveRange(300, l.Count - 300);
            if (save) Json.Save("downloads.json", new Store { Items = l });
            if (Changed != null) Changed();
        }

        public static void Remove(DownloadEntry e) { All().Remove(e); Json.Save("downloads.json", new Store { Items = All() }); if (Changed != null) Changed(); }
        public static void ClearFinished() { All().RemoveAll(x => x.State != 0); Json.Save("downloads.json", new Store { Items = All() }); if (Changed != null) Changed(); }
    }

    // ---------------------------------------------------------------- home page

    /// <summary>Everything the person can change about the home page, as on Android.</summary>
    public class HomeConfig
    {
        public const int Paper = 8, Photo = 9;
        public static readonly string[] Wallpapers = { "Paper glow", "Dusk", "Ocean", "Forest", "Graphite" };

        /// <summary>Paper = the plain Paper grid (default), 0..4 generated wallpapers, Photo = your own picture</summary>
        public int Wallpaper { get; set; } = Paper;
        /// <summary>0 none, 1 light, 2 medium, 3 strong</summary>
        public int Dim { get; set; } = 1;
        public bool Aurora { get; set; } = true;
        public bool Clock { get; set; } = true;
        /// <summary>0 follow Windows, 1 12-hour, 2 24-hour</summary>
        public int HourFormat { get; set; }
        public bool Date { get; set; } = true;
        public bool Weather { get; set; }
        public string City { get; set; } = "";
        public string Lat { get; set; } = "";
        public string Lon { get; set; } = "";
        public bool Fahrenheit { get; set; }
        public bool Stats { get; set; } = true;
        public bool Bookmarks { get; set; } = true;
        public string Name { get; set; } = "";
        public long PhotoStamp { get; set; }

        public static HomeConfig Load() { return Json.Load<HomeConfig>("home.json"); }
        public void Save() { Json.Save("home.json", this); }

        public static string PhotoPath { get { return Path.Combine(AppInfo.DataDir, "wallpaper.jpg"); } }
        public byte DimAlpha { get { return (byte)(Dim == 1 ? 0x26 : Dim == 2 ? 0x4D : Dim == 3 ? 0x80 : 0); } }
    }

    public class WeatherInfo
    {
        public int Temp { get; set; }
        public string Words { get; set; } = "";
        public string Place { get; set; } = "";
        public bool Fahrenheit { get; set; }
        public long Time { get; set; }
    }

    /// <summary>Optional weather from Open-Meteo: off by default, only the chosen place is ever looked up.</summary>
    public static class Weather
    {
        public static WeatherInfo Cached() { var w = Json.Load<WeatherInfo>("weather.json"); return w.Time == 0 ? null : w; }

        public static async Task<WeatherInfo> RefreshAsync(HomeConfig cfg, bool force = false)
        {
            if (!cfg.Weather || cfg.Lat.Length == 0) return null;
            var have = Cached();
            if (!force && have != null && have.Fahrenheit == cfg.Fahrenheit && have.Place == cfg.City
                && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - have.Time < 30 * 60 * 1000) return have;
            try
            {
                string unit = cfg.Fahrenheit ? "fahrenheit" : "celsius";
                string body;
                using (var c = new HttpClient { Timeout = TimeSpan.FromSeconds(15) })
                    body = await c.GetStringAsync("https://api.open-meteo.com/v1/forecast?latitude=" + cfg.Lat + "&longitude=" + cfg.Lon + "&current=temperature_2m,weather_code&temperature_unit=" + unit);
                using (var doc = JsonDocument.Parse(body))
                {
                    var cur = doc.RootElement.GetProperty("current");
                    var w = new WeatherInfo
                    {
                        Temp = (int)Math.Round(cur.GetProperty("temperature_2m").GetDouble()),
                        Words = Words(cur.GetProperty("weather_code").GetInt32()),
                        Place = cfg.City, Fahrenheit = cfg.Fahrenheit, Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                    };
                    Json.Save("weather.json", w);
                    return w;
                }
            }
            catch { return have; }
        }

        /// <summary>Looks a place up by name; returns its name, or null when nothing was found.</summary>
        public static async Task<string> SetPlaceAsync(HomeConfig cfg, string query)
        {
            try
            {
                string body;
                using (var c = new HttpClient { Timeout = TimeSpan.FromSeconds(15) })
                    body = await c.GetStringAsync("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + Uri.EscapeDataString(query));
                using (var doc = JsonDocument.Parse(body))
                {
                    if (!doc.RootElement.TryGetProperty("results", out var r) || r.GetArrayLength() == 0) return null;
                    var x = r[0];
                    cfg.City = x.TryGetProperty("name", out var n) ? n.GetString() : query;
                    cfg.Lat = x.GetProperty("latitude").GetDouble().ToString(System.Globalization.CultureInfo.InvariantCulture);
                    cfg.Lon = x.GetProperty("longitude").GetDouble().ToString(System.Globalization.CultureInfo.InvariantCulture);
                    cfg.Save();
                    Json.Delete("weather.json");
                    return cfg.City;
                }
            }
            catch { return null; }
        }

        private static string Words(int code)
        {
            if (code == 0) return "Clear";
            if (code == 1 || code == 2) return "Mostly clear";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
            if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
            if (code >= 95 && code <= 99) return "Thunderstorm";
            return "Weather";
        }
    }

    // ---------------------------------------------------------------- AI assistant

    /// <summary>Optional assistant. Off by default; nothing is sent anywhere until the person turns it on and asks.</summary>
    public class AiConfig
    {
        public bool Enabled { get; set; }
        /// <summary>0 = Anthropic, 1 = OpenAI-compatible</summary>
        public int Provider { get; set; }
        public string BaseUrl { get; set; } = "";
        public string Model { get; set; } = "";
        public string KeyProtected { get; set; } = "";

        public static AiConfig Load() { return Json.Load<AiConfig>("ai.json"); }
        public void Save() { Json.Save("ai.json", this); }

        public string Base { get { return BaseUrl.Length > 0 ? BaseUrl.TrimEnd('/') : Provider == 0 ? "https://api.anthropic.com" : "https://api.openai.com/v1"; } }
        public string ModelName { get { return Model.Length > 0 ? Model : Provider == 0 ? "claude-sonnet-5-5" : ""; } }
        public string Key { get { return Secret.Unprotect(KeyProtected); } set { KeyProtected = Secret.Protect(value); } }
        public bool Ready { get { return Enabled && KeyProtected.Length > 0 && ModelName.Length > 0; } }

        public async Task<string> AskAsync(string system, string user)
        {
            using (var c = new HttpClient { Timeout = TimeSpan.FromSeconds(90) })
            {
                HttpRequestMessage req;
                string body;
                if (Provider == 0)
                {
                    req = new HttpRequestMessage(HttpMethod.Post, Base + "/v1/messages");
                    req.Headers.Add("x-api-key", Key);
                    req.Headers.Add("anthropic-version", "2023-06-01");
                    body = JsonSerializer.Serialize(new { model = ModelName, max_tokens = 1500, system, messages = new[] { new { role = "user", content = user } } });
                }
                else
                {
                    req = new HttpRequestMessage(HttpMethod.Post, Base + "/chat/completions");
                    req.Headers.Add("Authorization", "Bearer " + Key);
                    body = JsonSerializer.Serialize(new { model = ModelName, messages = new[] { new { role = "system", content = system }, new { role = "user", content = user } } });
                }
                req.Content = new StringContent(body, Encoding.UTF8, "application/json");
                var res = await c.SendAsync(req);
                string text = await res.Content.ReadAsStringAsync();
                using (var doc = JsonDocument.Parse(text.Length > 0 ? text : "{}"))
                {
                    var o = doc.RootElement;
                    if (!res.IsSuccessStatusCode)
                    {
                        string msg = o.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.Object && err.TryGetProperty("message", out var m) ? m.GetString() : null;
                        throw new Exception(string.IsNullOrEmpty(msg) ? "The provider answered with code " + (int)res.StatusCode : msg);
                    }
                    if (Provider == 0)
                    {
                        var sb = new StringBuilder();
                        foreach (var part in o.GetProperty("content").EnumerateArray())
                            if (part.TryGetProperty("text", out var t)) sb.Append(t.GetString());
                        return sb.ToString().Trim();
                    }
                    return o.GetProperty("choices")[0].GetProperty("message").GetProperty("content").GetString().Trim();
                }
            }
        }
    }
}
