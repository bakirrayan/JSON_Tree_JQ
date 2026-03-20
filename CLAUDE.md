# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

This is a Burp Suite Extension built on the **Montoya API** that displays JSON responses as an interactive, collapsible tree with a **jq-powered search bar**.

---

## What this extension does

- Activates automatically on HTTP responses with `Content-Type: application/json`
- Renders the JSON body as a collapsible/expandable `JTree` with syntax coloring
- Left-side ruler shows absolute line numbers (stable across expand/collapse)
- jq search bar filters/queries the JSON in real time (debounced, no Run button)
- Auto-complete suggestions drop down as you type
- Right-click context menu: Copy value / Copy key / Copy key: value
- Ctrl+C copies key: value of the selected node
- Status bar shows the jq path of the selected node (e.g. `.user.name`)
- Colors adapt to Burp's dark and light themes at paint time

---

## Architecture

```
src/main/java/
├── Extension.java            # Entry point — registers the tab factory
├── JsonTreeTab.java          # HttpResponseEditorProvider + ExtensionProvidedHttpResponseEditor
│                             # Activates on JSON responses, owns panel lifecycle
├── JsonTreePanel.java        # Main Swing UI (see layout below)
├── JsonTreeModel.java        # Jackson JsonNode → DefaultTreeModel (recursive)
├── JsonTreeNode.java         # DefaultMutableTreeNode: key, value, Kind enum
├── JsonTreeCellRenderer.java # Custom renderer: JPanel + JLabels, theme-aware colors
├── JqRunner.java             # jackson-jq wrapper — pure Java, no binary needed
└── JqSearchBar.java          # Query field + debounce + autocomplete popup
```

### UI layout

```
┌─────────────────────────────────────────────┐
│  [jq filter field (e.g. .user.name)........]│  ← JqSearchBar (NORTH)
│ ┌──┬──────────────────────────────────────┐ │
│ │ 1│ ▶ user {}                            │ │
│ │ 2│   ├─ id: 42                          │ │  ← LineNumberView | JScrollPane + JTree
│ │ 3│   └─ name: "Alice"                   │ │
│ │ 4│ ▶ tags [2]                           │ │
│ └──┴──────────────────────────────────────┘ │
│  [Expand All] [Collapse All] | .user.name   │  ← bottom bar (SOUTH)
└─────────────────────────────────────────────┘
```

### Data flow

```
HTTP Response
    └─► JsonTreeTab.setResponse()
            ├─► SwingWorker: parse body → JsonTreeModel.build(JsonNode) → DefaultTreeModel
            └─► JsonTreePanel.setModel(model, rawJson)
                    ├─► tree.setModel(model)
                    ├─► rebuildNodeIndex()   ← pre-order walk, assigns absolute 1-based indices
                    └─► auto-expand depth 1

User types jq query
    └─► JqSearchBar debounce (400ms) → JqRunner.run(rawJson, query)
            ├─► success → JsonTreePanel.rebuildTree(resultJson)
            └─► error   → inline red error label
```

---

## Key implementation details

### Theme-aware colors (`JsonTreeCellRenderer.isDark()`)
Checked at **paint time** (not class init) so colors adapt live when Burp's theme changes:
```java
static boolean isDark() {
    Color bg = UIManager.getColor("Panel.background");
    if (bg == null) return false;
    int lum = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
    return lum < 128;
}
```

### Selection readability
Selection text color is derived from selection background luminance — never hardcode white:
```java
int selLum = (selBg.getRed() * 299 + selBg.getGreen() * 587 + selBg.getBlue() * 114) / 1000;
Color onSel    = selLum < 128 ? Color.WHITE : new Color(0x1A, 0x1A, 0x1A);
Color onSelDim = selLum < 128 ? new Color(0xCC, 0xCC, 0xCC) : new Color(0x44, 0x44, 0x44);
```

### Absolute line numbers (`nodeAbsIndex`)
Pre-order traversal of the **full** `TreeModel` (not just visible rows) assigns permanent indices.
Uses `IdentityHashMap` — required because `DefaultMutableTreeNode.equals()` compares by value,
not identity, so nodes with the same content would collide in a regular `HashMap`:
```java
private final Map<Object, Integer> nodeAbsIndex = new IdentityHashMap<>();

private void rebuildNodeIndex() {
    nodeAbsIndex.clear();
    TreeModel model = tree.getModel();
    Object root = model.getRoot();
    int[] counter = {0};
    for (int i = 0; i < model.getChildCount(root); i++)
        traverseIndex(model, model.getChild(root, i), counter);
}
```

### Row height — avoid text truncation
`tree.setRowHeight(0)` tells Swing to ask each renderer for its preferred height.
Without this, a fixed row height clips the text when smaller than the rendered font:
```java
tree.setRowHeight(0);
```

### Cross-platform right-click
Check `isPopupTrigger()` in **both** `mousePressed` and `mouseReleased` —
macOS fires popup on press, Windows on release:
```java
tree.addMouseListener(new MouseAdapter() {
    @Override public void mousePressed(MouseEvent e)  { handleMouse(e); }
    @Override public void mouseReleased(MouseEvent e) { handleMouse(e); }
    private void handleMouse(MouseEvent e) {
        if (e.isPopupTrigger()) { ... }
    }
});
```

### jq autocomplete
`JqSearchBar` calls `getSuggestions(partialQuery)` on every keystroke.
Suggestions are derived by running `jq` on the current JSON up to the last dot,
then listing matching field names of the result object.

---

## Build system

Gradle with Kotlin DSL (`build.gradle.kts`), Java 21.

```kotlin
dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("net.thisptr:jackson-jq:1.0.0")
    implementation("net.thisptr:jackson-jq-extra:1.0.0")
}
```

Fat-JAR task bundles `runtimeClasspath` — all deps included automatically.

```bash
./gradlew build    # Compile, test, package
./gradlew jar      # Fat JAR only → build/libs/
./gradlew clean    # Remove build artifacts
```

Load `build/libs/extension-template-project.jar` in Burp via **Extensions > Installed > Add**.

---

## Threading rules

- **Never** block the EDT
- JSON parsing in `SwingWorker.doInBackground()`
- Cancel any in-flight worker when `setResponse()` is called again
- All `tree.*` mutations inside `SwingUtilities.invokeLater()`

---

## File reference

- `@docs/montoya-api-examples.md` — Montoya API patterns
- `@docs/development-best-practices.md` — threading guidelines
- `@docs/bapp-store-requirements.md` — BApp Store acceptance criteria
- `@docs/resources.md` — Montoya API Javadoc and example repos
