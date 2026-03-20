import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.tree.DefaultTreeModel;

public class JsonTreeModel {

    public static DefaultTreeModel build(JsonNode root) {
        JsonTreeNode rootNode = convert("root", root);
        return new DefaultTreeModel(rootNode);
    }

    private static JsonTreeNode convert(String key, JsonNode node) {
        if (node.isObject()) {
            JsonTreeNode n = new JsonTreeNode(key, "{" + node.size() + "}", JsonTreeNode.Kind.OBJECT);
            node.fields().forEachRemaining(e -> n.add(convert(e.getKey(), e.getValue())));
            return n;
        }
        if (node.isArray()) {
            JsonTreeNode n = new JsonTreeNode(key, "[" + node.size() + "]", JsonTreeNode.Kind.ARRAY);
            for (int i = 0; i < node.size(); i++) {
                n.add(convert("[" + i + "]", node.get(i)));
            }
            return n;
        }
        if (node.isTextual()) {
            return new JsonTreeNode(key, node.asText(), JsonTreeNode.Kind.STRING);
        }
        if (node.isNumber()) {
            return new JsonTreeNode(key, node.asText(), JsonTreeNode.Kind.NUMBER);
        }
        if (node.isBoolean()) {
            return new JsonTreeNode(key, node.asText(), JsonTreeNode.Kind.BOOLEAN);
        }
        // null
        return new JsonTreeNode(key, "null", JsonTreeNode.Kind.NULL);
    }
}
