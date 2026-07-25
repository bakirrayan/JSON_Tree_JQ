/**
 * Path and value formatting helpers, kept free of Swing so they can be unit tested headlessly.
 *
 * <p>Both path builders take the raw {@code TreePath.getPath()} array and skip element 0,
 * which is the hidden synthetic root created by {@link JsonTreeModel}.
 */
public final class JsonPaths {

    /** Keys matching this can be written as {@code .name}; anything else needs bracket syntax. */
    private static final java.util.regex.Pattern BARE_KEY =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private JsonPaths() {}

    /** Builds a jq path such as {@code .user.tags[0]} or {@code .["content-type"]}. */
    public static String jq(Object[] components) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < components.length; i++) {
            if (!(components[i] instanceof JsonTreeNode node)) continue;
            String key = node.getKey();
            if (key == null) continue;
            if (isArrayIndex(key)) {
                sb.append(key);
            } else if (BARE_KEY.matcher(key).matches()) {
                sb.append('.').append(key);
            } else {
                // Keys with dashes, spaces or quotes are invalid after a bare dot. A leading
                // bracket needs the dot (.["a-b"]); mid-path it must not have one (.x["a-b"]).
                if (sb.length() == 0) sb.append('.');
                sb.append("[\"").append(escape(key, '"')).append("\"]");
            }
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    /** Builds a Python subscript path such as {@code response.json()['user']['tags'][0]}. */
    public static String python(Object[] components) {
        StringBuilder sb = new StringBuilder("response.json()");
        for (int i = 1; i < components.length; i++) {
            if (!(components[i] instanceof JsonTreeNode node)) continue;
            String key = node.getKey();
            if (key == null) continue;
            if (isArrayIndex(key)) {
                sb.append(key);
            } else {
                sb.append("['").append(escape(key, '\'')).append("']");
            }
        }
        return sb.toString();
    }

    /**
     * Renders a value on a single line: control characters become escapes and anything longer
     * than {@code maxChars} is truncated. A JLabel silently drops everything after a newline and
     * a multi-megabyte string would blow up the tree's preferred width.
     */
    public static String displayValue(String raw, int maxChars) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxChars) + 8);
        int i = 0;
        while (i < raw.length() && sb.length() < maxChars) {
            char c = raw.charAt(i++);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c < 0x20 ? ' ' : c);
            }
        }
        if (i < raw.length()) sb.append('…');
        return sb.toString();
    }

    /** Array children are keyed "[0]", "[12]", … by {@link JsonTreeModel}. */
    private static boolean isArrayIndex(String key) {
        return key.length() > 2 && key.charAt(0) == '[' && key.charAt(key.length() - 1) == ']';
    }

    private static String escape(String key, char quote) {
        return key.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
    }
}
