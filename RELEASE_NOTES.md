# Facet 3.0

A major update for Windows. Facet now installs like any other browser, gets the features from the Android 2.0
releases (profiles, Research, Reader view, the command bar, the new home page, site permissions, the AI assistant
and the welcome), extensions, and a full right-click menu.

## Download

| File | What it is |
| --- | --- |
| **`Facet-v3.0-Setup.exe`** | **The Windows installer. Download this one.** |
| `Facet-v3.0-windows-x64.zip` | The same browser as a folder, for portable use and Facet's own updater |
| `Facet-v3.0-debug.apk` | Android |

Windows 10 or 11, 64-bit. Setup installs for your account only, so it needs no administrator rights. Windows may
show a SmartScreen notice the first time because the installer isn't code-signed yet: choose **More info → Run anyway**.

---

## Windows

### Install like other browsers
- **New Paper installer**, animated, with clear Prism type and a progress bar that says what it's doing.
- Start menu and desktop shortcuts, an entry in **Settings → Apps** with a proper uninstaller, and `Win + R` → `facet`.
- **Facet can be your default browser**: it registers with Windows for web links and `.html`, `.svg` and `.pdf` files.
- Links opened from other apps arrive as new tabs in the Facet window that's already open.
- **Updates through the installer**: Software update closes Facet, updates it and opens it again with your tabs.
- Uninstalling can also delete your browsing data, if you ask it to.

### From Android 2.0
- **Animated welcome** for first-time users, then quick choices: profile name, your name, search engine, theme,
  accent and background.
- **Profiles**: Personal, Work, or whatever you need, each with its own tabs, bookmarks, history, cookies and logins.
  **Temporary sessions** erase everything when you leave them.
- **Research profiles**: save pages, highlight text on them, keep notes, and export it all as Markdown.
- **Reader view** (`F9`) with text size, width and Paper, Dark or Sepia themes.
- **Command bar** (`Ctrl+K`): search tabs, bookmarks, history, settings and actions from one box.
- **Redesigned home page**: clock and date, optional weather (Open-Meteo, off by default), a greeting, protection
  counters, quick tiles for Research, Downloads, Private tab and Command bar, and your bookmarks, over the Paper grid,
  a generated wallpaper or your own photo, with a slow aurora.
- **Paper AMOLED** theme and **accent colours** (Terracotta, Ocean, Forest, Violet, Rose, Amber).
- **Tracker controls**: Standard, Strict or Custom, and *What was blocked on this page*. Click the lock in the address
  bar for the site's connection, protection and permissions.
- **Site permissions**: Facet asks every time, and you can tick *Remember* for a site. Review, change or revoke
  answers per site or per permission, with reminders for access held over a month.
- **History** (`Ctrl+H`) and a **Downloads** page (`Ctrl+J`) with categories, progress, cancel and add-by-link.
- **Optional AI assistant**: off by default, uses your own key (Anthropic or any OpenAI-compatible service), never
  runs in private tabs. Adds *Summarize page* and *Ask about this page*.
- **JavaScript** switch, crash-safe sessions (tabs are saved as you browse), crashed pages reload themselves,
  page dialogs and permission prompts, fullscreen video.
- **The new Facet logo** on the app, taskbar, Start menu and the welcome.

### Extensions
- **Userscripts**: open a script's *Install* link on Greasy Fork (or any `.user.js` link) and Facet offers to add it.
- **Chrome and Firefox extensions** that change pages: from the **Chrome Web Store** (paste the link), Mozilla's
  add-ons (open an add-on's file link), a `.crx`, `.zip` or `.xpi` file, or an unpacked folder. Their content
  scripts and styles run, with `chrome.storage`, `chrome.i18n` and `chrome.runtime.getURL`.
- Honest limits: the engine Facet embeds can't run extension background pages, toolbar popups or options pages yet,
  so extensions built only on those can't be added (Facet tells you when you try). Extensions are off in private tabs.

### Right-click menu, like Chrome and Brave
Pages, links, images, video and audio, selected text and text fields: open in new or private tab, save, copy
(including **Copy image**), Google Lens, loop and picture in picture, **spelling suggestions**, paste as plain text,
view source and **Inspect**. Shortcuts are shown next to each item.

### Look, speed and fixes
- Tabs in the title bar like Chrome, Windows 11 corners, animations everywhere, Paper scrollbars and smooth scrolling,
  crisper ClearType text.
- Restored tabs load only when opened and background tabs stop drawing: about 20% less memory and less than half
  the start-up CPU with four tabs.
- Fixed: a crash on sites that set third-party cookies; Settings staying on screen while browsing and blinking when
  opening its pages; a blank address bar after closing a new tab.

### Not on Windows yet
Tab groups and the vertical tab list, and Safe Browsing (the engine Facet embeds doesn't include it).

---

## Android

No changes in this release. The Android app is built as 3.0 from the same code as 2.0.5.

---

Facet is made by Ayush and was written together with Claude, an AI model from Anthropic. It collects nothing:
no accounts, no analytics, no telemetry.
