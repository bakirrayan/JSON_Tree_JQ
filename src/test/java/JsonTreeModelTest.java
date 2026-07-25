import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultTreeModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JsonTreeModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonTreeNode build(String json) throws Exception {
        DefaultTreeModel model = JsonTreeModel.build(MAPPER.readTree(json));
        return assertInstanceOf(JsonTreeNode.class, model.getRoot());
    }

    private static JsonTreeNode child(JsonTreeNode parent, int index) {
        return (JsonTreeNode) parent.getChildAt(index);
    }

    @Test
    void scalarKindsAreMapped() throws Exception {
        JsonTreeNode root = build("""
                {"s":"txt","n":1.5,"b":true,"z":null}
                """);
        assertEquals(4, root.getChildCount());
        assertEquals(JsonTreeNode.Kind.STRING,  child(root, 0).getKind());
        assertEquals(JsonTreeNode.Kind.NUMBER,  child(root, 1).getKind());
        assertEquals(JsonTreeNode.Kind.BOOLEAN, child(root, 2).getKind());
        assertEquals(JsonTreeNode.Kind.NULL,    child(root, 3).getKind());
        assertEquals("txt", child(root, 0).getValue());
        assertEquals("null", child(root, 3).getValue());
    }

    @Test
    void objectsKeepKeysAndOrder() throws Exception {
        JsonTreeNode root = build("""
                {"first":1,"second":2}
                """);
        assertEquals(JsonTreeNode.Kind.OBJECT, root.getKind());
        assertEquals("first",  child(root, 0).getKey());
        assertEquals("second", child(root, 1).getKey());
    }

    @Test
    void arrayChildrenAreKeyedByIndex() throws Exception {
        JsonTreeNode root = build("""
                {"tags":["a","b","c"]}
                """);
        JsonTreeNode tags = child(root, 0);
        assertEquals(JsonTreeNode.Kind.ARRAY, tags.getKind());
        assertEquals("[3]", tags.getValue());
        assertEquals(3, tags.getChildCount());
        assertEquals("[0]", child(tags, 0).getKey());
        assertEquals("[2]", child(tags, 2).getKey());
    }

    @Test
    void nestingIsPreserved() throws Exception {
        JsonTreeNode root = build("""
                {"a":{"b":{"c":"deep"}}}
                """);
        JsonTreeNode c = child(child(child(root, 0), 0), 0);
        assertEquals("c", c.getKey());
        assertEquals("deep", c.getValue());
    }

    @Test
    void emptyContainersHaveNoChildren() throws Exception {
        JsonTreeNode root = build("""
                {"o":{},"a":[]}
                """);
        assertEquals(0, child(root, 0).getChildCount());
        assertEquals(0, child(root, 1).getChildCount());
        assertEquals("{0}", child(root, 0).getValue());
        assertEquals("[0]", child(root, 1).getValue());
    }
}
