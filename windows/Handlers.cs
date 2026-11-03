using System;
using System.Collections.Generic;
using CefSharp;

namespace Facet
{
    /// <summary>A question for the person: a site permission, or a page's alert / confirm / prompt / leave-page dialog.</summary>
    public class Ask
    {
        public string Title = "", Message = "", Ok = "OK", Cancel = "Cancel";
        /// <summary>Null for plain dialogs; the starting text for a prompt() field.</summary>
        public string Field;
        /// <summary>Called once, on the UI thread: true when accepted, plus the field's text.</summary>
        public Action<bool, string> Answer;
    }

    /// <summary>What was right-clicked.</summary>
    public class MenuTarget
    {
        public string Link = "", LinkText = "", Image = "", Selection = "", Page = "", Frame = "";
        /// <summary>The address of a right-clicked video or audio element.</summary>
        public string Video = "", Audio = "";
        public string Misspelled = "";
        public List<string> Suggestions = new List<string>();
        public bool Editable, CanCut, CanCopy, CanPaste, CanUndo, CanRedo, CanSelectAll, MediaLoops;
        /// <summary>Where the click was, in page coordinates (for Inspect and media actions).</summary>
        public int X, Y;
    }

    static class Describe
    {
        // Names come from CefSharp's flag enums. Matching on names (not enum members) keeps this working when
        // Chromium adds or renames permission types.
        private static readonly Dictionary<string, string> Names = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            { "DeviceVideoCapture", "your camera" }, { "CameraStream", "your camera" }, { "CameraPanTiltZoom", "move your camera" },
            { "DeviceAudioCapture", "your microphone" }, { "MicStream", "your microphone" },
            { "DesktopVideoCapture", "see your screen" }, { "DesktopAudioCapture", "hear your computer's audio" },
            { "Geolocation", "your location" }, { "Notifications", "show notifications" }, { "Clipboard", "see what you copy" },
            { "MidiSysex", "control MIDI devices" }, { "MultipleDownloads", "download several files" },
            { "LocalFonts", "see your installed fonts" }, { "IdleDetection", "know when you're idle" },
            { "StorageAccess", "use cookies while embedded" }, { "TopLevelStorageAccess", "use cookies across sites" },
            { "WindowManagement", "manage windows on your screens" }, { "KeyboardLock", "capture your keyboard" },
            { "PointerLock", "lock your mouse pointer" }, { "ProtectedMediaIdentifier", "play protected content" },
            { "RegisterProtocolHandler", "open certain links" }, { "FileSystemAccess", "edit files on this PC" },
            { "VrSession", "use virtual reality" }, { "ArSession", "use augmented reality" },
            { "CapturedSurfaceControl", "control a shared tab" }, { "IdentityProvider", "sign you in with another site" },
            { "DiskQuota", "store more data" },
        };

        public static string What(Enum flags)
        {
            var parts = new List<string>();
            foreach (var name in flags.ToString().Split(new[] { ", " }, StringSplitOptions.RemoveEmptyEntries))
            {
                if (name == "None") continue;
                string t;
                if (!Names.TryGetValue(name, out t)) t = name;
                if (!parts.Contains(t)) parts.Add(t);
            }
            if (parts.Count == 0) return "a browser feature";
            if (parts.Count == 1) return parts[0];
            return string.Join(", ", parts.GetRange(0, parts.Count - 1)) + " and " + parts[parts.Count - 1];
        }

        public static string Site(string url)
        {
            var h = Urls.Host(url);
            return h.Length > 0 ? h : "This page";
        }
    }

    /// <summary>Camera, microphone, location and other permissions are asked per request and never granted silently.</summary>
    public class FacetPermissions : IPermissionHandler
    {
        private readonly Action<Ask> ask;
        public FacetPermissions(Action<Ask> ask) { this.ask = ask; }

        public bool OnRequestMediaAccessPermission(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, string requestingOrigin,
            MediaAccessPermissionType requestedPermissions, IMediaAccessCallback callback)
        {
            ask(new Ask
            {
                Title = "Allow " + Describe.What(requestedPermissions) + "?",
                Message = Describe.Site(requestingOrigin) + " wants to use " + Describe.What(requestedPermissions) + ".",
                Ok = "Allow", Cancel = "Block",
                Answer = (ok, _) => { using (callback) { if (ok) callback.Continue(requestedPermissions); else callback.Cancel(); } }
            });
            return true;
        }

        public bool OnShowPermissionPrompt(IWebBrowser chromiumWebBrowser, IBrowser browser, ulong promptId, string requestingOrigin,
            PermissionRequestType permissionRequestTypes, IPermissionPromptCallback callback)
        {
            var what = Describe.What(permissionRequestTypes);
            ask(new Ask
            {
                Title = "Allow access?",
                Message = Describe.Site(requestingOrigin) + " wants to " + (what.StartsWith("your") ? "use " : "") + what + ".",
                Ok = "Allow", Cancel = "Block",
                Answer = (ok, _) => { using (callback) callback.Continue(ok ? PermissionRequestResult.Accept : PermissionRequestResult.Deny); }
            });
            return true;
        }

        public void OnDismissPermissionPrompt(IWebBrowser chromiumWebBrowser, IBrowser browser, ulong promptId, PermissionRequestResult result) { }
    }

    /// <summary>alert(), confirm(), prompt() and "leave site?" shown as Facet dialogs (offscreen pages have no dialogs of their own).</summary>
    public class FacetDialogs : IJsDialogHandler
    {
        private readonly Action<Ask> ask;
        public FacetDialogs(Action<Ask> ask) { this.ask = ask; }

        public bool OnJSDialog(IWebBrowser chromiumWebBrowser, IBrowser browser, string originUrl, CefJsDialogType dialogType,
            string messageText, string defaultPromptText, IJsDialogCallback callback, ref bool suppressMessage)
        {
            string kind = dialogType.ToString();
            ask(new Ask
            {
                Title = Describe.Site(originUrl) + " says",
                Message = messageText ?? "",
                Ok = "OK",
                Cancel = kind == "Alert" ? null : "Cancel",
                Field = kind == "Prompt" ? (defaultPromptText ?? "") : null,
                Answer = (ok, text) => { using (callback) callback.Continue(ok, text ?? ""); }
            });
            return true;
        }

        public bool OnBeforeUnloadDialog(IWebBrowser chromiumWebBrowser, IBrowser browser, string messageText, bool isReload, IJsDialogCallback callback)
        {
            ask(new Ask
            {
                Title = isReload ? "Reload this page?" : "Leave this page?",
                Message = "Changes you made may not be saved.",
                Ok = isReload ? "Reload" : "Leave", Cancel = "Stay",
                Answer = (ok, _) => { using (callback) callback.Continue(ok); }
            });
            return true;
        }

        public void OnResetDialogState(IWebBrowser chromiumWebBrowser, IBrowser browser) { }
        public void OnDialogClosed(IWebBrowser chromiumWebBrowser, IBrowser browser) { }
    }

    /// <summary>Replaces Chromium's menu with Facet's own (link, image, selection and editing actions).</summary>
    public class FacetContextMenu : IContextMenuHandler
    {
        private readonly Action<MenuTarget> show;
        public FacetContextMenu(Action<MenuTarget> show) { this.show = show; }

        // The model is left as it is: Chromium skips RunContextMenu for an empty menu. RunContextMenu cancels
        // Chromium's menu and shows Facet's instead.
        public void OnBeforeContextMenu(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IContextMenuParams parameters, IMenuModel model) { }

        public bool RunContextMenu(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IContextMenuParams parameters, IMenuModel model, IRunContextMenuCallback callback)
        {
            var flags = parameters.EditStateFlags;
            string media = parameters.MediaType.ToString();
            string mediaState = parameters.MediaStateFlags.ToString();
            var t = new MenuTarget
            {
                Link = parameters.LinkUrl ?? "",
                LinkText = parameters.LinkUrl ?? "",
                Image = parameters.MediaType == ContextMenuMediaType.Image ? (parameters.SourceUrl ?? "") : "",
                Video = media == "Video" ? (parameters.SourceUrl ?? "") : "",
                Audio = media == "Audio" ? (parameters.SourceUrl ?? "") : "",
                Selection = parameters.SelectionText ?? "",
                Page = parameters.PageUrl ?? "",
                Frame = parameters.FrameUrl ?? "",
                Editable = parameters.IsEditable,
                CanCut = (flags & ContextMenuEditState.CanCut) != 0,
                CanCopy = (flags & ContextMenuEditState.CanCopy) != 0,
                CanPaste = (flags & ContextMenuEditState.CanPaste) != 0,
                CanUndo = (flags & ContextMenuEditState.CanUndo) != 0,
                CanRedo = (flags & ContextMenuEditState.CanRedo) != 0,
                CanSelectAll = (flags & ContextMenuEditState.CanSelectAll) != 0,
                MediaLoops = mediaState.Contains("Loop"),
                Misspelled = parameters.MisspelledWord ?? "",
                X = parameters.XCoord, Y = parameters.YCoord,
            };
            try { if (parameters.DictionarySuggestions != null) t.Suggestions.AddRange(parameters.DictionarySuggestions); } catch { }
            using (callback) callback.Cancel();
            show(t);
            return true;
        }

        public bool OnContextMenuCommand(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame, IContextMenuParams parameters, CefMenuCommand commandId, CefEventFlags eventFlags)
        {
            return false;
        }

        public void OnContextMenuDismissed(IWebBrowser chromiumWebBrowser, IBrowser browser, IFrame frame) { }
    }

    /// <summary>Video and other page fullscreen requests.</summary>
    public class FacetDisplay : CefSharp.Handler.DisplayHandler
    {
        private readonly Action<bool> fullscreen;
        public FacetDisplay(Action<bool> fullscreen) { this.fullscreen = fullscreen; }

        protected override void OnFullscreenModeChange(IWebBrowser chromiumWebBrowser, IBrowser browser, bool fullscreen)
        {
            this.fullscreen(fullscreen);
        }
    }

    /// <summary>Match counts for the find bar.</summary>
    public class FacetFind : IFindHandler
    {
        private readonly Action<int, int> result;
        public FacetFind(Action<int, int> result) { this.result = result; }

        public void OnFindResult(IWebBrowser chromiumWebBrowser, IBrowser browser, int identifier, int count, CefSharp.Structs.Rect selectionRect, int activeMatchOrdinal, bool finalUpdate)
        {
            result(activeMatchOrdinal, count);
        }
    }
}
