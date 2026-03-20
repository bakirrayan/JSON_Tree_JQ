import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("JSonTree");
        api.userInterface().registerHttpResponseEditorProvider(new JsonTreeTab(api));
        api.logging().logToOutput("JSON Tree Viewer loaded.");
    }
}
