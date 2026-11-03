# Changelog

## 3.0 (Windows major update)

Full notes: [RELEASE_NOTES.md](RELEASE_NOTES.md).

### Added
- From Android 2.0: animated welcome, profiles and temporary sessions, Research with highlights and Markdown export, Reader view, command bar (Ctrl+K), redesigned home page with clock, weather and wallpapers, Paper AMOLED and accent colours, tracker levels and per-page report, remembered site permissions with reminders, History, Downloads page, optional AI assistant, tab groups (right-click a tab; coloured, foldable, saved per profile), vertical tabs, the new logo (also in the installer).
- Paper-themed Windows installer (`Facet-v3.0-Setup.exe`): per-user install, Start menu and desktop shortcuts,
  Apps & features entry with an uninstaller, registration as a browser for Default apps, and updates through setup.
- Extensions: userscripts (`.user.js`, Greasy Fork) and page-changing Chrome / Firefox extensions (Chrome Web Store
  link, `.crx`, `.zip`, `.xpi`, unpacked folder), with an Extensions page.
- From Android: per-request camera / microphone / location permission prompts, page dialogs (alert, confirm,
  prompt, leave page), crash recovery, fullscreen video.
- Chrome-style right-click menu for pages, links, images, video / audio, selected text and text fields, with
  spelling suggestions, Copy image and Inspect.
- Find in page, zoom, print, save page, view source, developer tools, and their keyboard shortcuts.
- Facet cube app icon; Facet opens links handed over by other apps in the running window.

### Changed
- Android: releases are now stable release builds (R8-shrunk, signed with Facet's private release key) named
  `Facet-v<version>.apk`; earlier debug-signed installs must be uninstalled once.
- Tabs replace the Windows title bar; full-size window buttons; Windows 11 rounded corners.
- Animations throughout, paper scrollbars and smooth scrolling, ClearType text.
- Lower memory and CPU use: restored tabs load when opened, hidden tabs stop drawing, unused Chromium services off.

### Fixed
- Crash on sites that set third-party cookies.
- Settings staying on screen while browsing, and blinking when opening its sub-pages.
- Blank address bar after closing a new tab.

## 1.0

- Android: GeckoView browser with tabs, private tabs, tracking protection, HTTPS-only mode, bookmarks, downloads,
  Paper themes and the Software update page.
- Windows: first Chromium-based build with the same privacy features, distributed as a zip.
