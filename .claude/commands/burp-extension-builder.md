# Burp Suite Extension Builder

You are helping build a Burp Suite extension using the **Montoya API** and **Java 21 + Gradle (Kotlin DSL)**.

Use the patterns, pitfalls, and boilerplate below to scaffold or extend any Burp extension quickly and correctly.

---

## Project structure

```
src/main/java/
├── Extension.java          # BurpExtension entry point
├── <Feature>Tab.java       # HttpResponseEditorProvider (or RequestEditorProvider)
├── <Feature>Panel.java     # Main Swing UI panel
└── ...                     # Supporting classes
build.gradle.kts
settings.gradle.kts
```

## build.gradle.kts template

```kotlin
plugins { id("java") }

repositories { mavenCentral() }

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.2")

    // Pick what you need:
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")   // JSON parsing
    implementation("net.thisptr:jackson-jq:1.0.0")                        // Pure-Java jq
    implementation("net.thisptr:jackson-jq-extra:1.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

// Fat JAR — bundles all runtimeClasspath deps
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().filter { it.isDirectory })
    from(configurations.runtimeClasspath.get().filterNot { it.isDirectory }.map { zipTree(it) })
}
```

Build: `./gradlew jar` → `build/libs/<name>.jar`
Load in Burp: **Extensions > Installed > Add**

---

## Extension.java — entry point

```java
import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("My Extension");

        // Register a response editor tab:
        api.userInterface().registerHttpResponseEditorProvider(new MyTab(api));

        // Register a request editor tab:
        // api.userInterface().registerHttpRequestEditorProvider(new MyRequestTab(api));

        // Register a context menu:
        // api.userInterface().registerContextMenuItemsProvider(new MyMenu(api));

        // Unload handler — release resources:
        api.extension().registerUnloadingHandler(() -> {
            api.logging().logToOutput("Extension unloaded.");
        });

        api.logging().logToOutput("Extension loaded.");
    }
}
```

---

## Response editor tab boilerplate

```java
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.extension.*;
import javax.swing.*;
import java.awt.*;

public class MyTab implements HttpResponseEditorProvider {

    private final MontoyaApi api;

    public MyTab(MontoyaApi api) { this.api = api; }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext ctx) {
        return new Editor(api);
    }

    private static class Editor implements ExtensionProvidedHttpResponseEditor {
        private final MontoyaApi api;
        private final MyPanel panel;
        private HttpResponse currentResponse;
        private SwingWorker<?, ?> currentWorker;

        Editor(MontoyaApi api) {
            this.api = api;
            this.panel = new MyPanel();
        }

        @Override
        public void setRequestResponse(HttpRequestResponse rr) {
            currentResponse = rr.response();
            // Cancel in-flight work
            if (currentWorker != null && !currentWorker.isDone()) currentWorker.cancel(true);

            String body = new String(rr.response().body().getBytes(), java.nio.charset.StandardCharsets.UTF_8);

            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override protected Void doInBackground() throws Exception {
                    // heavy work here (parse, transform)
                    return null;
                }
                @Override protected void done() {
                    if (isCancelled()) return;
                    try {
                        get(); // rethrow exceptions
                        SwingUtilities.invokeLater(() -> panel.update(/* result */));
                    } catch (Exception e) {
                        api.logging().logToError("Error: " + e.getMessage());
                    }
                }
            };
            currentWorker = worker;
            worker.execute();
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse rr) {
            HttpResponse r = rr.response();
            if (r == null) return false;
            String ct = r.headerValue("Content-Type");
            return ct != null && ct.toLowerCase().contains("json"); // adjust as needed
        }

        @Override public String caption()              { return "My Tab"; }
        @Override public Component uiComponent()       { return panel; }
        @Override public HttpResponse getResponse()    { return currentResponse; }
        @Override public burp.api.montoya.ui.Selection selectedData() { return null; }
        @Override public boolean isModified()          { return false; }
    }
}
```

---

## Swing UI patterns

### Theme-aware colors (adapts to Burp dark/light mode at paint time)
```java
static boolean isDark() {
    Color bg = UIManager.getColor("Panel.background");
    if (bg == null) return false;
    int lum = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
    return lum < 128;
}
```
Call `isDark()` inside `paintComponent()` or `configure()` — **not** at class init — so it reacts live.

### Selection text contrast (readable on any selection background)
```java
Color selBg = UIManager.getColor("Tree.selectionBackground");
if (selBg == null) selBg = new Color(0x26, 0x59, 0xA8);
int selLum = (selBg.getRed() * 299 + selBg.getGreen() * 587 + selBg.getBlue() * 114) / 1000;
Color onSel    = selLum < 128 ? Color.WHITE : new Color(0x1A, 0x1A, 0x1A);
Color onSelDim = selLum < 128 ? new Color(0xCC, 0xCC, 0xCC) : new Color(0x44, 0x44, 0x44);
```

### JTree: avoid text truncation
```java
tree.setRowHeight(0); // auto-size each row from renderer's preferred height
```
A fixed row height smaller than the rendered font clips text. Always use 0.

### JTree: absolute line numbers that survive expand/collapse
Use `IdentityHashMap` (not `HashMap`) because `DefaultMutableTreeNode.equals()` compares by value:
```java
private final Map<Object, Integer> nodeAbsIndex = new IdentityHashMap<>();

private void rebuildNodeIndex() {
    nodeAbsIndex.clear();
    TreeModel m = tree.getModel();
    if (m == null) return;
    Object root = m.getRoot();
    if (root == null) return;
    int[] c = {0};
    for (int i = 0; i < m.getChildCount(root); i++)
        traverse(m, m.getChild(root, i), c);
}
private void traverse(TreeModel m, Object node, int[] c) {
    nodeAbsIndex.put(node, ++c[0]);
    for (int i = 0; i < m.getChildCount(node); i++)
        traverse(m, m.getChild(node, i), c);
}
```
Call `rebuildNodeIndex()` right after `tree.setModel(model)`.
In `paintComponent()` of the line-number view: look up `nodeAbsIndex.get(path.getLastPathComponent())`.

### Cross-platform right-click context menu
```java
tree.addMouseListener(new MouseAdapter() {
    @Override public void mousePressed(MouseEvent e)  { check(e); }
    @Override public void mouseReleased(MouseEvent e) { check(e); }
    void check(MouseEvent e) {
        if (!e.isPopupTrigger()) return;          // macOS: press, Windows: release
        int row = tree.getRowForLocation(e.getX(), e.getY());
        if (row >= 0) { tree.setSelectionRow(row); showMenu(e); }
    }
});
```

### Debounced live query field (no Run button needed)
```java
private static final int DEBOUNCE_MS = 400;
Timer debounce = new Timer(DEBOUNCE_MS, e -> runQuery());
debounce.setRepeats(false);

field.getDocument().addDocumentListener(new DocumentListener() {
    public void insertUpdate(DocumentEvent e) { debounce.restart(); }
    public void removeUpdate(DocumentEvent e) { debounce.restart(); }
    public void changedUpdate(DocumentEvent e) { debounce.restart(); }
});
field.addActionListener(e -> { debounce.stop(); runQuery(); }); // Enter: run immediately
```

### Scroll pane left-side ruler
```java
JScrollPane scroll = new JScrollPane(tree);
scroll.setRowHeaderView(new MyLineNumberView());
```
`MyLineNumberView` is a `JComponent` whose `paintComponent()` walks visible rows and draws numbers.
Register a `TreeExpansionListener` on the tree to call `revalidate(); repaint();` on expand/collapse.

---

## Common Montoya API calls

```java
// Logging
api.logging().logToOutput("msg");
api.logging().logToError("err");

// HTTP body as String
String body = new String(response.body().getBytes(), StandardCharsets.UTF_8);

// Check a header
String ct = response.headerValue("Content-Type");

// Highlight a request in proxy history
// (set via requestResponse.annotations().setHighlightColor(...))
```

---

## Threading rules

1. **Never** block the EDT — wrap all I/O and heavy computation in `SwingWorker.doInBackground()`
2. Always update Swing components on the EDT: `SwingUtilities.invokeLater(() -> ...)`
3. Cancel the previous `SwingWorker` before starting a new one
4. Register an unload handler (`api.extension().registerUnloadingHandler(...)`) to shut down any `ExecutorService`

---

## jq (jackson-jq) — pure-Java, no binary required

```java
import net.thisptr.jackson.jq.*;

public class JqRunner {
    private final Scope scope;
    private final ObjectMapper mapper = new ObjectMapper();

    public JqRunner() throws Exception {
        scope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope);
    }

    public String run(String json, String query) throws Exception {
        JsonQuery jq = JsonQuery.compile(query, Versions.JQ_1_6);
        JsonNode input = mapper.readTree(json);
        List<JsonNode> out = new ArrayList<>();
        jq.apply(scope, input, out::add);
        if (out.size() == 1)
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out.get(0));
        ArrayNode arr = mapper.createArrayNode();
        out.forEach(arr::add);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
    }
}
```

---

## Useful jq queries for testing

```
.                          # Identity (full JSON)
.user.name                 # Nested field
.items[0]                  # First array element
.items[] | .id             # Pluck field from each element
.[] | select(.active==true)# Filter by condition
keys                       # Object keys
length                     # Count keys or array items
.. | numbers               # All numbers recursively
```

---

## Checklist before loading in Burp

- [ ] Fat JAR built: `./gradlew jar`
- [ ] `Extension.java` implements `BurpExtension` (no `@BurpExtension` annotation needed for Montoya)
- [ ] `isEnabledFor()` returns `false` for responses you don't handle (avoid unnecessary activations)
- [ ] All Swing mutations on EDT
- [ ] No `System.out.println` — use `api.logging().logToOutput()`
- [ ] Unload handler registered if you use background threads
