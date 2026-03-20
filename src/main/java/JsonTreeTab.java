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
import java.nio.charset.StandardCharsets;

public class JsonTreeTab implements HttpResponseEditorProvider {

    private final MontoyaApi api;
    private final JqRunner jqRunner;

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
        return new Editor(api, jqRunner);
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
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
            }

            ByteArray body = response.body();
            String bodyStr = new String(body.getBytes(), StandardCharsets.UTF_8);

            SwingWorker<DefaultTreeModel, Void> worker = new SwingWorker<>() {
                String rawJson;

                @Override
                protected DefaultTreeModel doInBackground() throws Exception {
                    rawJson = bodyStr;
                    JsonNode node = mapper.readTree(bodyStr);
                    return JsonTreeModel.build(node);
                }

                @Override
                protected void done() {
                    if (isCancelled()) return;
                    try {
                        panel.setModel(get(), rawJson);
                    } catch (Exception e) {
                        api.logging().logToError("JSON parse error: " + e.getMessage());
                        panel.setModel(new DefaultTreeModel(null), null);
                    }
                }
            };
            currentWorker = worker;
            worker.execute();
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
