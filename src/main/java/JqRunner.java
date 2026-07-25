import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JqRunner {

    /** Guard against queries like {@code ..} on a huge document exhausting the heap. */
    static final int MAX_RESULTS = 50_000;

    private static final int QUERY_CACHE_LIMIT = 256;

    private final Scope rootScope;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonQuery> queryCache = new ConcurrentHashMap<>();

    public JqRunner() throws Exception {
        rootScope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
    }

    /** Returns pretty-printed JSON result, or throws on invalid query. */
    public String run(String json, String query) throws Exception {
        return run(mapper.readTree(json), query);
    }

    /**
     * Same as {@link #run(String, String)} but takes an already-parsed tree, so callers that
     * query the same document repeatedly don't re-parse megabytes on every keystroke.
     */
    public String run(JsonNode input, String query) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(runToNode(input, query));
    }

    /**
     * Applies the query and returns the result as a tree: a single output as-is, zero or several
     * outputs wrapped in an array. Avoids serialising a large result just to re-parse it.
     */
    public JsonNode runToNode(JsonNode input, String query) throws Exception {
        JsonQuery jq = compile(query);
        List<JsonNode> out = new ArrayList<>();
        // A child scope keeps per-query variable bindings off the shared root scope, which is
        // read from background workers belonging to several editors at once.
        Scope scope = Scope.newChildScope(rootScope);
        jq.apply(scope, input, node -> {
            if (out.size() >= MAX_RESULTS) {
                throw new IllegalStateException("Query produced more than " + MAX_RESULTS + " results");
            }
            out.add(node);
        });
        if (out.size() == 1) return out.get(0);
        ArrayNode arr = mapper.createArrayNode();
        out.forEach(arr::add);
        return arr;
    }

    private JsonQuery compile(String query) throws Exception {
        JsonQuery cached = queryCache.get(query);
        if (cached != null) return cached;
        JsonQuery compiled = JsonQuery.compile(query, Versions.JQ_1_6);
        if (queryCache.size() >= QUERY_CACHE_LIMIT) queryCache.clear();
        queryCache.put(query, compiled);
        return compiled;
    }
}
