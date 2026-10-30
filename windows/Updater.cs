using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Facet
{
    public class Release
    {
        public string Tag = "", Title = "", Notes = "", Published = "", AssetUrl = "", AssetName = "";
        public long Size;
    }

    /// <summary>Checks GitHub Releases, downloads the Windows zip and swaps it in after Facet closes.</summary>
    public static class Updater
    {
        private static HttpClient Make(string token, bool follow)
        {
            var h = new HttpClientHandler { AllowAutoRedirect = follow };
            var c = new HttpClient(h);
            c.Timeout = TimeSpan.FromMinutes(15);
            c.DefaultRequestHeaders.UserAgent.ParseAdd("Facet-Updater");
            if (!string.IsNullOrEmpty(token)) c.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            return c;
        }

        public static async Task<Release> LatestAsync(string repo, string token)
        {
            if (!Regex.IsMatch(repo ?? "", @"^[\w.-]+/[\w.-]+$")) throw new Exception("Repository must look like owner/name");
            using (var c = Make(token, true))
            {
                var req = new HttpRequestMessage(HttpMethod.Get, "https://api.github.com/repos/" + repo + "/releases/latest");
                req.Headers.Accept.ParseAdd("application/vnd.github+json");
                var res = await c.SendAsync(req);
                if (res.StatusCode == HttpStatusCode.NotFound)
                {
                    if (string.IsNullOrEmpty(token)) throw new Exception("No release found. If the repository is private, add an access token.");
                    return null;
                }
                if (res.StatusCode == HttpStatusCode.Unauthorized || res.StatusCode == HttpStatusCode.Forbidden)
                    throw new Exception("GitHub refused the request (" + (int)res.StatusCode + "). Check the access token.");
                if (!res.IsSuccessStatusCode) throw new Exception("GitHub answered with code " + (int)res.StatusCode);
                using (var doc = JsonDocument.Parse(await res.Content.ReadAsStringAsync()))
                {
                    var o = doc.RootElement;
                    var r = new Release();
                    r.Tag = Str(o, "tag_name"); r.Title = Str(o, "name"); r.Notes = Str(o, "body"); r.Published = Str(o, "published_at");
                    if (o.TryGetProperty("assets", out var assets))
                    {
                        foreach (var a in assets.EnumerateArray())
                        {
                            var name = Str(a, "name");
                            if (name.EndsWith("windows-x64.zip", StringComparison.OrdinalIgnoreCase))
                            {
                                r.AssetName = name; r.AssetUrl = Str(a, "url");
                                r.Size = a.TryGetProperty("size", out var sz) ? sz.GetInt64() : 0;
                                break;
                            }
                        }
                    }
                    if (r.AssetUrl.Length == 0) throw new Exception("The latest release has no Windows build attached");
                    return r;
                }
            }
        }

        private static string Str(JsonElement e, string k)
        {
            return e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : "";
        }

        public static bool IsNewer(string tag, string current)
        {
            int[] a = Parts(tag), b = Parts(current);
            for (int i = 0; i < Math.Max(a.Length, b.Length); i++)
            {
                int x = i < a.Length ? a[i] : 0, y = i < b.Length ? b[i] : 0;
                if (x != y) return x > y;
            }
            return false;
        }

        private static int[] Parts(string s)
        {
            var list = new System.Collections.Generic.List<int>();
            foreach (var p in (s ?? "").Trim().TrimStart('v', 'V').Split('.', '-'))
            {
                var m = Regex.Match(p, @"^\d+");
                list.Add(m.Success ? int.Parse(m.Value) : 0);
            }
            return list.ToArray();
        }

        public static async Task<string> DownloadAsync(Release r, string token, Action<long, long> progress, CancellationToken ct)
        {
            string dir = Path.Combine(AppInfo.DataDir, "update");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            Directory.CreateDirectory(dir);
            string zip = Path.Combine(dir, "Facet-update.zip");

            string url = r.AssetUrl;
            string auth = token;
            for (int hop = 0; hop < 6; hop++)
            {
                using (var c = Make(auth, false))
                {
                    var req = new HttpRequestMessage(HttpMethod.Get, url);
                    req.Headers.Accept.ParseAdd("application/octet-stream");
                    var res = await c.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
                    int code = (int)res.StatusCode;
                    if (code >= 300 && code < 400 && res.Headers.Location != null)
                    {
                        url = res.Headers.Location.ToString();
                        auth = ""; // never send the token to the storage host
                        continue;
                    }
                    if (!res.IsSuccessStatusCode) throw new Exception("Download failed (code " + code + ")");
                    long total = res.Content.Headers.ContentLength ?? r.Size;
                    using (var input = await res.Content.ReadAsStreamAsync())
                    using (var output = File.Create(zip))
                    {
                        var buf = new byte[64 * 1024];
                        long done = 0;
                        int n;
                        while ((n = await input.ReadAsync(buf, 0, buf.Length, ct)) > 0)
                        {
                            await output.WriteAsync(buf, 0, n, ct);
                            done += n;
                            progress(done, total);
                        }
                        if (total > 0 && done < total) throw new Exception("Download was cut short");
                    }
                    return zip;
                }
            }
            throw new Exception("Too many redirects");
        }

        public static bool CanWriteHere()
        {
            try
            {
                var f = Path.Combine(AppContext.BaseDirectory, ".write-test");
                File.WriteAllText(f, "x");
                File.Delete(f);
                return true;
            }
            catch { return false; }
        }

        /// <summary>Unpacks the zip, then runs a small script that copies it over this folder once Facet has exited.</summary>
        public static void ApplyAndRestart(string zip)
        {
            string work = Path.Combine(AppInfo.DataDir, "update");
            string fresh = Path.Combine(work, "new");
            if (Directory.Exists(fresh)) Directory.Delete(fresh, true);
            ZipFile.ExtractToDirectory(zip, fresh);
            string dst = AppContext.BaseDirectory.TrimEnd('\\');
            string script = Path.Combine(work, "apply.cmd");
            File.WriteAllText(script,
                "@echo off\r\n" +
                "set n=0\r\n" +
                ":retry\r\n" +
                "timeout /t 2 /nobreak >nul\r\n" +
                "xcopy \"" + fresh + "\\*\" \"" + dst + "\\\" /E /Y /Q >nul\r\n" +
                "if errorlevel 1 (\r\n" +
                "  set /a n+=1\r\n" +
                "  if %n% lss 10 goto retry\r\n" +
                ")\r\n" +
                "start \"\" \"" + dst + "\\Facet.exe\"\r\n");
            Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + script + "\"") { CreateNoWindow = true, UseShellExecute = false });
            System.Windows.Application.Current.Shutdown();
        }
    }
}
