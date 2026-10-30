# Facet

A fast, private, uncluttered web browser for Android. Written from scratch in Kotlin
on top of Android's built-in Chromium (WebView). No accounts, no analytics, no telemetry.

Cromite was used as a reference for which privacy defaults matter. No code was copied.

## v1.0

- **Tabs** with a card-style tab switcher, plus **private tabs** (no history, no cache)
- **Address bar** at the bottom: type an address or a search, one field for both
- **Ad and tracker blocking** from a built-in host list (`app/src/main/assets/blocklist.txt`)
- **Third-party cookie blocking**, **HTTPS upgrades** with automatic fallback
- Camera, microphone and location are **denied by default**; pop-ups are blocked
- Bookmarks, downloads, share, desktop site, restore tabs
- **Paper** theme (light) and **Paper Dark**, the same palette as Prism Client
- Prism Serif for headings, Prism Sans for text, across the whole app UI

Search engines: DuckDuckGo (default), Brave Search, Startpage, Ecosia, Google.

## Honest limits

Facet uses the system WebView instead of its own browser engine. That keeps it small
and quick to build, but it means:

- No Chrome extensions and no sync.
- **Private tabs share the cookie jar with normal tabs.** They save no history, skip the
  cache, and wipe the cache when the last private tab closes, but a site you are logged
  into in a normal tab will still see you in a private one.
- Blocking works at the request level using a host list, not cosmetic filtering.
- Safe Browsing is on by default and checks hash prefixes with Google. Turn it off in Settings.

## Building

Debug APKs are built by GitHub Actions (`.github/workflows/build.yml`).

- Every push builds an APK, downloadable from the run's **Artifacts**.
- Pushing a tag like `v1.0` or `v2.0` builds the APK and attaches it to a **GitHub Release**.
  The tag sets the version: `v2.0` becomes versionName `2.0` and versionCode `2`.

To build locally you need JDK 17, the Android SDK (platform 34) and Gradle 8.9:

```
gradle assembleDebug
```

### Signing

Without secrets, CI signs with a throwaway debug key, so each build has a different
signature and cannot be installed over the previous one. For updates that install over
each other, add these repository secrets:

| Secret | Value |
| --- | --- |
| `FACET_KEYSTORE_B64` | your keystore file, base64-encoded |
| `FACET_KEYSTORE_PASSWORD` | keystore password |
| `FACET_KEY_ALIAS` | key alias |
| `FACET_KEY_PASSWORD` | key password |

## Fonts

`app/src/main/assets/fonts` holds the Prism Sans and Prism Serif font files, obtained from
MoonBear. They are **not** part of the MIT license and come with no redistribution
permission in the download. **Remove that folder before making this repository public.**
Facet falls back to the system serif and sans-serif fonts when the files are missing, so the
build still works without them.

## License

MIT for the source code. See `LICENSE`.
