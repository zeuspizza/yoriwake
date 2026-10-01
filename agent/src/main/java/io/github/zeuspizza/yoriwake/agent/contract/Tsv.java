package io.github.zeuspizza.yoriwake.agent.contract;

/**
 * The one field escaping every tab-separated file the two sides share uses: {@code index.tsv},
 * {@code coverage.tsv} and {@code decisions.tsv}. Test ids carry host-supplied display names, and
 * a tab or newline in one would shift or split a row.
 *
 * <p>A backslash, tab, carriage return and newline become {@code \\}, {@code \t}, {@code \r} and
 * {@code \n}; nothing else changes, so a field without them is written as it is.
 */
public final class Tsv {

    private Tsv() {}

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /** The inverse of {@link #escape}. An unknown escape and a trailing backslash stay as written. */
    public static String unescape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                out.append(current);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'n': out.append('\n'); break;
                case '\\': out.append('\\'); break;
                default: out.append('\\').append(next); break;
            }
        }
        return out.toString();
    }

    /** One row: every field escaped, joined by tabs, without a line end. */
    public static String join(String... fields) {
        StringBuilder row = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                row.append('\t');
            }
            row.append(escape(fields[index]));
        }
        return row.toString();
    }

    /** One row's fields, unescaped. Empty fields are kept, trailing ones included. */
    public static String[] split(String row) {
        String[] fields = row.split("\t", -1);
        for (int index = 0; index < fields.length; index++) {
            fields[index] = unescape(fields[index]);
        }
        return fields;
    }
}
