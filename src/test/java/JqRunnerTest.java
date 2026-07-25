import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JqRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOC = """
            {"user":{"name":"Alice","id":42},"tags":["a","b"],"content-type":"text/html"}
            """;

    private static JqRunner runner;

    @BeforeAll
    static void setUp() throws Exception {
        runner = new JqRunner();
    }

    private static JsonNode query(String jq) throws Exception {
        return runner.runToNode(MAPPER.readTree(DOC), jq);
    }

    @Test
    void identityReturnsWholeDocument() throws Exception {
        assertEquals(MAPPER.readTree(DOC), query("."));
    }

    @Test
    void fieldAccessReturnsSingleValue() throws Exception {
        assertEquals("Alice", query(".user.name").asText());
        assertEquals(42, query(".user.id").asInt());
    }

    /** The bracket form produced by JsonPaths.jq for awkward keys must actually run. */
    @Test
    void bracketedKeyPathFromJsonPathsIsValid() throws Exception {
        assertEquals("text/html", query(".[\"content-type\"]").asText());
    }

    @Test
    void multipleOutputsAreWrappedInAnArray() throws Exception {
        JsonNode result = query(".tags[]");
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).asText());
    }

    @Test
    void noOutputYieldsEmptyArray() throws Exception {
        JsonNode result = query("empty");
        assertTrue(result.isArray());
        assertEquals(0, result.size());
    }

    @Test
    void invalidQueryThrows() {
        assertThrows(Exception.class, () -> query(".user|["));
    }

    @Test
    void runningTheSameQueryTwiceUsesTheCacheAndStillWorks() throws Exception {
        assertEquals(query(".user.name"), query(".user.name"));
    }

    @Test
    void runReturnsPrettyPrintedJson() throws Exception {
        String out = runner.run(DOC, ".user");
        assertTrue(out.contains("\n"), "expected pretty-printed output but got: " + out);
        assertEquals("Alice", MAPPER.readTree(out).get("name").asText());
    }

    @Test
    void oversizedResultIsRejected() throws Exception {
        // range/0 emits one output per element — well past MAX_RESULTS
        assertThrows(Exception.class,
                () -> runner.runToNode(MAPPER.readTree("null"), "range(" + (JqRunner.MAX_RESULTS + 10) + ")"));
    }
}
