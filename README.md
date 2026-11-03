# Facet

<p align="center"><img src="docs/logo.png" width="128" alt="Facet logo"></p>

A fast, private, uncluttered browser for **Android** and **Windows**. The interface is written from scratch, with
the same Paper look on both, and each version bundles its own engine: Facet does not use the system WebView and does
not depend on any other browser being installed.

- **Android**: Kotlin, with **GeckoView**, Mozilla's browser engine. GeckoView is used as an ordinary dependency from
  Mozilla's Maven server; nothing from Mozilla's source tree is copied or forked. GeckoView is licensed under the MPL 2.0.
- **Windows**: C# and WPF, with **Chromium** through CefSharp.

## Download (v3.0)

Get these from the [latest release](../../releases/latest):

| File | What it is |
| --- | --- |
| **`Facet-v3.0-Setup.exe`** | **Windows installer.** Installs for your account (no administrator rights) and lets Facet be your default browser. |
| `Facet-v3.0-windows-x64.zip` | The same Windows browser as a folder, for portable use and Facet's own updater |
| `Facet-v3.0-debug.apk` | Android |

Windows 10 or 11, 64-bit. The installer isn't code-signed yet, so SmartScreen may warn the first time: choose
**More info → Run anyway**. What's new is in [RELEASE_NOTES.md](RELEASE_NOTES.md) and [CHANGELOG.md](CHANGELOG.md).

## Features

On both Android and Windows:

- **Private tabs** that are truly isolated from normal tabs
- **Tracking protection**: Standard, Strict or Custom (ads, analytics, social trackers, fingerprinters,
  cryptominers), third-party cookie isolation, **HTTPS-only mode**, and *what was blocked on this page*
- **Site permissions**: camera, microphone and location are asked per request and never granted silently; review
  and revoke them per site, with reminders for access held a long time
- **Profiles**: Personal, Work or your own, each with its own tabs, bookmarks, history, cookies and logins, plus
  **temporary sessions** that erase everything when you leave them
- **Research profiles**: save pages, highlight text, keep notes and export it all as Markdown
- **Tab groups** and a **vertical tab list**
- **Reader view** with adjustable text size, width and theme
- **Command bar** (`Ctrl+K`): search tabs, bookmarks, history, settings and actions from one box
- **Extensions**: page-changing Firefox and Chrome extensions and userscripts
- **Downloads** with categories, progress and add-by-link; history; bookmarks
- **Paper home page**: clock and date, optional weather, protection counters and your bookmarks, over the Paper
  grid, a generated wallpaper or your own photo
- **Paper, Paper Dark and Paper AMOLED** themes, six accent colours, Prism Serif headings and Prism Sans text
- **Optional AI assistant**: off by default, uses your own key, never runs in private tabs
- Crash-safe sessions, crashed pages reload themselves, an animated welcome for first-time users, and a built-in
  **Software update** page

Android also has the address bar at the bottom, edge-to-edge pages with an optional immersive mode, and Wi-Fi-only
downloads. Windows adds a Paper installer and uninstaller, registration as a default browser, tabs in the title bar,
and a Chrome-style right-click menu with spelling suggestions and Inspect.

## Building

GitHub Actions builds Android and Windows on every push (`.github/workflows/build.yml`); download the builds from the
run's **Artifacts**. Pushing a tag such as `v3.0` publishes a GitHub Release with the APK, the Windows installer and
the Windows zip together.

### Android

`gradle.properties` has `geckoVersion=LATEST`, which makes CI look up the newest GeckoView. Replace it with a real
version number to pin the engine.

Local build: JDK 17, Android SDK 35 and Gradle 8.9, then `gradle assembleDebug -PgeckoVersion=<version>`.

Debug builds are signed with the shared key in `app/debug.keystore`, so each build can update the previous one. For
stable signing add these repository secrets: `FACET_KEYSTORE_B64`, `FACET_KEYSTORE_PASSWORD`, `FACET_KEY_ALIAS`,
`FACET_KEY_PASSWORD`.

### Windows

`pwsh windows/build.ps1 -Version 3.0` (needs the .NET 8 SDK) builds the browser, its uninstaller, the update zip and
the installer into `dist/`. CI runs the same script, smoke-tests the browser on real sites, and installs and
uninstalls it.

## Fonts

The Prism Sans and Prism Serif fonts are bundled in `app/src/main/assets/fonts` and used across both apps. The font
files are not covered by the MIT license.

## Credits

Facet is made by Ayush and was written together with [Claude](https://claude.ai), an AI model from Anthropic.
The browser engines are Mozilla's GeckoView (Android) and Chromium via CefSharp (Windows).

## License

MIT for the source code. See `LICENSE`.
