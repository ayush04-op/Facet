# Facet

A fast, private, uncluttered browser for Android. The interface is written from scratch in
Kotlin, and the engine is **GeckoView**, Mozilla's browser engine, bundled inside the app.
Facet does not use the system WebView and does not depend on any other browser app.

GeckoView is used as an ordinary dependency from Mozilla's Maven server. Nothing from
Mozilla's source tree is copied or forked. GeckoView is licensed under the MPL 2.0.

## v1.0

- Tabs with a card-style switcher, and **private tabs that are truly isolated** from normal tabs
- Address bar at the bottom: one field for addresses and searches
- **Strict tracking protection** (ads, analytics, social trackers, fingerprinters, cryptominers)
- Third-party cookie isolation and **HTTPS-only mode**
- Camera, microphone and location denied by default, pop-ups blocked
- Bookmarks, downloads, share, desktop site, restore tabs
- Paper and Paper Dark themes, Prism Serif headings and Prism Sans text throughout
- Animated interface
- Grouped settings and a built-in **Software update** page that shows this build and your device status

Coming next: built-in extension support (WebExtensions) and an encrypted password vault.

## Building

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`). Download it from
the run's **Artifacts**. Pushing a tag such as `v1.0` or `v2.0` publishes a GitHub Release.

`gradle.properties` has `geckoVersion=LATEST`, which makes CI look up the newest GeckoView.
Replace it with a real version number to pin the engine.

Local build: JDK 17, Android SDK 35 and Gradle 8.9, then
`gradle assembleDebug -PgeckoVersion=<version>`.

### Signing

Debug builds are signed with the shared key in `app/debug.keystore`, so each build can update the
previous one. For stable signing add these repository secrets: `FACET_KEYSTORE_B64`,
`FACET_KEYSTORE_PASSWORD`, `FACET_KEY_ALIAS`, `FACET_KEY_PASSWORD`.

## Fonts

The Prism Sans and Prism Serif fonts are bundled in `app/src/main/assets/fonts` and used across
the whole app. The font files are not covered by the MIT license.

## Credits

Facet is made by Ayush and was written together with [Claude](https://claude.ai), an AI model from Anthropic.
The browser engine is Mozilla's GeckoView.

## License

MIT for the source code. See `LICENSE`.
