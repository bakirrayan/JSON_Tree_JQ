import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonPathsTest {

    /** Mirrors what TreePath.getPath() hands over: a synthetic root, then the node chain. */
    private static Object[] path(String... keys) {
        Object[] components = new Object[keys.length + 1];
        components[0] = new JsonTreeNode("root", "{}", JsonTreeNode.Kind.OBJECT);
        for (int i = 0; i < keys.length; i++) {
            components[i + 1] = new JsonTreeNode(keys[i], "", JsonTreeNode.Kind.STRING);
        }
        return components;
    }

    @Test
    void jqUsesBareDotForSimpleKeys() {
        assertEquals(".user.name", JsonPaths.jq(path("user", "name")));
        assertEquals("._private.a1", JsonPaths.jq(path("_private", "a1")));
    }

    @Test
    void jqBracketsKeysThatAreNotValidIdentifiers() {
        assertEquals(".[\"content-type\"]", JsonPaths.jq(path("content-type")));
        assertEquals(".[\"two words\"]", JsonPaths.jq(path("two words")));
        assertEquals(".[\"1st\"]", JsonPaths.jq(path("1st")));
        assertEquals(".user[\"x.y\"]", JsonPaths.jq(path("user", "x.y")));
    }

    @Test
    void jqEscapesQuotesAndBackslashesInBracketedKeys() {
        assertEquals(".[\"a\\\"b\"]", JsonPaths.jq(path("a\"b")));
        assertEquals(".[\"a\\\\b\"]", JsonPaths.jq(path("a\\b")));
    }

    @Test
    void jqKeepsArrayIndicesAndFallsBackToIdentity() {
        assertEquals(".tags[0].name", JsonPaths.jq(path("tags", "[0]", "name")));
        assertEquals(".", JsonPaths.jq(path()));
    }

    @Test
    void pythonBuildsSubscriptChain() {
        assertEquals("response.json()['user']['name']", JsonPaths.python(path("user", "name")));
        assertEquals("response.json()['tags'][0]", JsonPaths.python(path("tags", "[0]")));
        assertEquals("response.json()", JsonPaths.python(path()));
    }

    @Test
    void pythonEscapesQuotesAndBackslashes() {
        assertEquals("response.json()['it\\'s']", JsonPaths.python(path("it's")));
        assertEquals("response.json()['a\\\\b']", JsonPaths.python(path("a\\b")));
    }

    @Test
    void displayValueEscapesControlCharacters() {
        assertEquals("a\\nb", JsonPaths.displayValue("a\nb", 100));
        assertEquals("a\\tb\\r", JsonPaths.displayValue("a\tb\r", 100));
        assertEquals("a b", JsonPaths.displayValue("a" + (char) 1 + "b", 100));
        assertEquals("", JsonPaths.displayValue(null, 100));
    }

    @Test
    void displayValueTruncatesLongText() {
        String result = JsonPaths.displayValue("x".repeat(1000), 10);
        assertEquals("xxxxxxxxxx…", result);
        assertEquals("short", JsonPaths.displayValue("short", 10));
    }
}
