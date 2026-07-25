import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonTreeTab implements HttpResponseEditorProvider {

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private final MontoyaApi api;
    private final JqRunner jqRunner;

    // Weakly held so Burp can garbage-collect editors it has closed; used only for unload cleanup
    private final Set<Editor> editors = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    public JsonTreeTab(MontoyaApi api) {
        this.api = api;
        JqRunner runner;
        try {
            runner = new JqRunner();
        } catch (Exception e) {
            api.logging().logToError("Failed to initialize jq: " + e.getMessage());
            runner = null;
        }
        this.jqRunner = runner;
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext context) {
        Editor editor = new Editor(api, jqRunner);
        editors.add(editor);
        return editor;
    }

    /** Releases every editor's background work and UI resources when the extension unloads. */
    public void unload() {
        synchronized (editors) {
            for (Editor editor : editors) editor.dispose();
            editors.clear();
        }
        api.logging().logToOutput("JSON Tree Viewer unloaded.");
    }

    private static class Editor implements ExtensionProvidedHttpResponseEditor {

        private final MontoyaApi api;
        private final JsonTreePanel panel;
        private final ObjectMapper mapper = new ObjectMapper();
        private HttpResponse currentResponse;
        private SwingWorker<?, ?> currentWorker;

        Editor(MontoyaApi api, JqRunner jqRunner) {
            this.api = api;
            this.panel = new JsonTreePanel(jqRunner);
        }

        @Override
        public HttpResponse getResponse() {
            return currentResponse;
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            setResponse(requestResponse.response());
        }

        private void setResponse(HttpResponse response) {
            this.currentResponse = response;

            // Cancel any in-flight worker
            cancelWorker();

            if (response == null) {
                panel.showEmpty("No response");
                return;
            }

            ByteArray body = response.body();
            if (body == null || body.length() == 0) {
                panel.showEmpty("Empty response body");
                return;
            }

            String bodyStr = new String(body.getBytes(), charsetOf(response));

            SwingWorker<DefaultTreeModel, Void> worker = new SwingWorker<>() {
                JsonNode parsed;

                @Override
                protected DefaultTreeModel doInBackground() throws Exception {
                    parsed = mapper.readTree(bodyStr);
                    // readTree returns a MissingNode (rather than throwing) for blank input
                    if (parsed == null || parsed.isMissingNode()) {
                        throw new IllegalArgumentException("no JSON content");
                    }
                    return JsonTreeModel.build(parsed);
                }

                @Override
                protected void done() {
                    if (isCancelled()) return;
                    try {
                        panel.setModel(get(), parsed);
                    } catch (Exception e) {
                        api.logging().logToError("JSON parse error: " + e.getMessage());
                        panel.showEmpty("Response body is not valid JSON");
                    }
                }
            };
            currentWorker = worker;
            worker.execute();
        }

        /** Honours the charset declared on Content-Type; falls back to UTF-8. */
        private Charset charsetOf(HttpResponse response) {
            String contentType = response.headerValue("Content-Type");
            if (contentType != null) {
                Matcher m = CHARSET.matcher(contentType);
                if (m.find()) {
                    try {
                        return Charset.forName(m.group(1).replace("\"", "").trim());
                    } catch (Exception ignored) {
                        // unknown or malformed charset — fall through to UTF-8
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }

        void cancelWorker() {
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
            }
        }

        void dispose() {
            cancelWorker();
            panel.dispose();
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            HttpResponse response = requestResponse.response();
            if (response == null) return false;
            String contentType = response.headerValue("Content-Type");
            return contentType != null && contentType.toLowerCase().contains("json");
        }

        @Override
        public String caption() {
            return "JSON Tree";
        }

        @Override
        public Component uiComponent() {
            return panel;
        }

        @Override
        public burp.api.montoya.ui.Selection selectedData() {
            return null;
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }
}
