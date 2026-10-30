using System;
using System.Collections.Generic;
using System.Threading;
using CefSharp;
using CefSharp.Handler;

namespace Facet
{
    /// <summary>Shared counters shown on the new tab page and in Settings.</summary>
    public static class Stats
    {
        private static long blocked;
        private static long secure;
        public static long Blocked { get { return Interlocked.Read(ref blocked); } }
        public static long Secure { get { return Interlocked.Read(ref secure); } }
        public static void AddBlocked() { Interlocked.Increment(ref blocked); }
        public static void AddSecure() { Interlocked.Increment(ref secure); }
    }

    public static class TrackerList
    {
        // Well-known advertising, analytics, social-tracking and fingerprinting hosts.
        private static readonly string[] Domains =
        {
            "doubleclick.net","googlesyndication.com","googleadservices.com","google-analytics.com","googletagmanager.com",
            "googletagservices.com","adservice.google.com","2mdn.net","adsrvr.org","adnxs.com","adform.net","advertising.com",
            "criteo.com","criteo.net","taboola.com","outbrain.com","rubiconproject.com","pubmatic.com","openx.net","casalemedia.com",
            "indexww.com","smartadserver.com","moatads.com","scorecardresearch.com","quantserve.com","quantcast.com","chartbeat.com",
            "hotjar.com","hotjar.io","fullstory.com","mouseflow.com","crazyegg.com","luckyorange.com","inspectlet.com","clarity.ms",
            "mixpanel.com","segment.io","segment.com","amplitude.com","heap.io","heapanalytics.com","optimizely.com","branch.io",
            "adjust.com","appsflyer.com","kochava.com","singular.net","bugsnag.com","newrelic.com","nr-data.net","sentry-cdn.com",
            "facebook.net","connect.facebook.net","graph.facebook.com","pixel.facebook.com","analytics.twitter.com","ads-twitter.com",
            "ads.linkedin.com","px.ads.linkedin.com","snap.licdn.com","analytics.tiktok.com","ads.tiktok.com","business.tiktok.com",
            "bat.bing.com","ads.yahoo.com","analytics.yahoo.com","yimg.com/ads","amazon-adsystem.com","adsymptotic.com","bidswitch.net",
            "bluekai.com","krxd.net","demdex.net","everesttech.net","omtrdc.net","2o7.net","agkn.com","rlcdn.com","liadm.com",
            "tapad.com","turn.com","mathtag.com","contextweb.com","lijit.com","sharethrough.com","teads.tv","media.net","zedo.com",
            "yieldmo.com","33across.com","sovrn.com","gumgum.com","triplelift.com","adroll.com","perfectaudience.com","exelator.com",
            "eyeota.net","id5-sync.com","pippio.com","serving-sys.com","sizmek.com","flashtalking.com","innovid.com","spotxchange.com",
            "tiqcdn.com","ensighten.com","tags.tiqcdn.com","cdn.mxpnl.com","stats.wp.com","pixel.wp.com","addthis.com","sharethis.com",
            "disqusads.com","revcontent.com","mgid.com","zemanta.com","nativo.com","plista.com","adblade.com","popads.net","propellerads.com",
            "coinhive.com","coin-hive.com","crypto-loot.com","jsecoin.com","minero.cc","fingerprintjs.com","fpjs.io","iovation.com",
            "threatmetrix.com","perimeterx.net","datadome.co"
        };

        private static readonly HashSet<string> Set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        static TrackerList() { foreach (var d in Domains) Set.Add(d); }

        public static bool IsTracker(string host)
        {
            if (string.IsNullOrEmpty(host)) return false;
            var h = host;
            while (true)
            {
                if (Set.Contains(h)) return true;
                int dot = h.IndexOf('.');
                if (dot < 0) return false;
                h = h.Substring(dot + 1);
            }
        }

        /// <summary>Rough "same site" test: compares the last two labels (three for co.uk style names).</summary>
        public static string Site(string host)
        {
            if (string.IsNullOrEmpty(host)) return "";
            var p = host.Split('.');
            if (p.Length <= 2) return host;
            string last = p[p.Length - 1], second = p[p.Length - 2];
            bool compound = last.Length == 2 && (second == "co" || second == "com" || second == "org" || second == "net" || second == "gov" || second == "ac");
            int n = compound ? 3 : 2;
            return string.Join(".", p, p.Length - n, n);
        }
    }

    public class FacetRequestHandler : RequestHandler
    {
        private readonly Prefs prefs;
        public FacetRequestHandler(Prefs prefs) { this.prefs = prefs; }

        protected override IResourceRequestHandler GetResourceRequestHandler(
            IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IRequest request,
            bool isNavigation, bool isDownload, string requestInitiator, ref bool disableDefaultHandling)
        {
            return new FacetResourceHandler(prefs, chromiumWebBrowser);
        }
    }

    public class FacetResourceHandler : ResourceRequestHandler
    {
        private readonly Prefs prefs;
        private readonly IWebBrowser web;
        public FacetResourceHandler(Prefs prefs, IWebBrowser web) { this.prefs = prefs; this.web = web; }

        private string TopHost()
        {
            try { return Urls.Host(web.Address ?? ""); } catch { return ""; }
        }

        protected override CefReturnValue OnBeforeResourceLoad(
            IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IRequest request, IRequestCallback callback)
        {
            string url = request.Url;
            string host = Urls.Host(url);

            if (prefs.HttpsOnly && request.ResourceType == ResourceType.MainFrame
                && url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !Urls.IsLocal(host))
            {
                request.Url = "https://" + url.Substring("http://".Length);
                return CefReturnValue.Continue;
            }
            if (request.ResourceType == ResourceType.MainFrame && url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                Stats.AddSecure();

            if (prefs.BlockTrackers && request.ResourceType != ResourceType.MainFrame && TrackerList.IsTracker(host))
            {
                string top = TopHost();
                if (TrackerList.Site(top) != TrackerList.Site(host))
                {
                    Stats.AddBlocked();
                    return CefReturnValue.Cancel;
                }
            }
            return CefReturnValue.Continue;
        }

        protected override ICookieAccessFilter GetCookieAccessFilter(
            IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IRequest request)
        {
            return prefs.BlockThirdPartyCookies ? new ThirdPartyCookieFilter(web) : null;
        }
    }

    public class ThirdPartyCookieFilter : ICookieAccessFilter
    {
        private readonly IWebBrowser web;
        public ThirdPartyCookieFilter(IWebBrowser web) { this.web = web; }

        private bool Allowed(IRequest request)
        {
            string top = Urls.Host(web.Address ?? "");
            if (string.IsNullOrEmpty(top)) return true;
            return TrackerList.Site(Urls.Host(request.Url)) == TrackerList.Site(top);
        }

        public bool CanSendCookie(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IRequest request, Cookie cookie)
        {
            return Allowed(request);
        }

        public bool CanSaveCookie(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IRequest request, IResponse response, Cookie cookie)
        {
            return Allowed(request);
        }

        public void Dispose() { }
    }
}
