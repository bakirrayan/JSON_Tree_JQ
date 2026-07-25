import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("JSON Tree + jq");

        JsonTreeTab tab = new JsonTreeTab(api);
        api.userInterface().registerHttpResponseEditorProvider(tab);
        api.extension().registerUnloadingHandler(tab::unload);

        api.logging().logToOutput("JSON Tree Viewer loaded.");
    }
}
