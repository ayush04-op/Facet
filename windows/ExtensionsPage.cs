using System;
using System.Windows.Controls;

namespace Facet
{
    /// <summary>Installed extensions and userscripts (switch on/off, remove) and the ways to add more.</summary>
    public class ExtensionsPage : Grid
    {
        private readonly Kit kit;
        private readonly StackPanel body;
        private readonly Action addFile, addFolder, addStore, findScripts;
        private readonly Action<ExtensionInfo> remove;
        private readonly Action<ExtensionInfo, bool> toggle;

        public ExtensionsPage(Palette p, Action onBack, Action addFile, Action addFolder, Action addStore, Action findScripts,
            Action<ExtensionInfo> remove, Action<ExtensionInfo, bool> toggle)
        {
            this.addFile = addFile; this.addFolder = addFolder; this.addStore = addStore; this.findScripts = findScripts;
            this.remove = remove; this.toggle = toggle;
            kit = new Kit(p);
            Children.Add(kit.Page("Extensions", onBack, out body));
            Rebuild();
        }

        public void Rebuild()
        {
            body.Children.Clear();
            Border g;
            StackPanel gp;

            body.Children.Add(kit.Section("Installed"));
            if (Extensions.All.Count == 0)
                body.Children.Add(kit.Note("Nothing installed yet. Add a userscript from Greasy Fork, or an extension from a file or the Chrome Web Store."));
            else
            {
                gp = kit.Group(out g);
                foreach (var e in Extensions.All.ToArray())
                {
                    var ext = e;
                    string sub = (ext.Kind == "Userscript" ? "Userscript" : "Extension") + (ext.Version.Length > 0 ? " " + ext.Version : "")
                                 + (ext.Description.Length > 0 ? " · " + ext.Description : "")
                                 + (ext.Warning.Length > 0 ? "\n" + ext.Warning : "");
                    kit.Add(gp, kit.ToggleAction(ext.Kind == "Userscript" ? "copy" : "puzzle", ext.Name, sub, ext.Enabled,
                        on => toggle(ext, on), "trash", () => remove(ext)));
                }
                body.Children.Add(g);
            }

            body.Children.Add(kit.Section("Add"));
            gp = kit.Group(out g);
            kit.Add(gp, kit.Value("download", "Add from a file", "", ".crx, .zip or .xpi extensions, or .user.js userscripts", addFile));
            kit.Add(gp, kit.Value("link", "Add from Chrome Web Store", "", "Paste the extension's store link", addStore));
            kit.Add(gp, kit.Value("globe", "Find userscripts", "Greasy Fork", "Open a script's Install link and Facet offers to add it", findScripts));
            kit.Add(gp, kit.Value("tabs", "Add an unpacked folder", "", "For developers: a folder with manifest.json", addFolder));
            body.Children.Add(g);

            body.Children.Add(kit.Note("Facet runs the part of extensions that changes pages: content scripts and styles from Chrome and Firefox "
                + "extensions, and userscripts. Background pages, toolbar buttons and options pages don't run yet. Extensions are off in "
                + "private tabs, and changes apply to pages you open or reload."));
        }
    }
}
