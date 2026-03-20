import javax.swing.tree.DefaultMutableTreeNode;

public class JsonTreeNode extends DefaultMutableTreeNode {

    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private final String key;
    private final String value;
    private final Kind kind;

    public JsonTreeNode(String key, String value, Kind kind) {
        this.key = key;
        this.value = value;
        this.kind = kind;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public Kind getKind() { return kind; }
}
