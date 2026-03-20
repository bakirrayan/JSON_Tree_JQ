import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

import java.util.ArrayList;
import java.util.List;

public class JqRunner {

    private final Scope scope;
    private final ObjectMapper mapper = new ObjectMapper();

    public JqRunner() throws Exception {
        scope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope);
    }

    /** Returns pretty-printed JSON result, or throws on invalid query. */
    public String run(String json, String query) throws Exception {
        JsonQuery jq = JsonQuery.compile(query, Versions.JQ_1_6);
        JsonNode input = mapper.readTree(json);
        List<JsonNode> out = new ArrayList<>();
        jq.apply(scope, input, out::add);
        if (out.size() == 1) {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out.get(0));
        }
        ArrayNode arr = mapper.createArrayNode();
        out.forEach(arr::add);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
    }
}
