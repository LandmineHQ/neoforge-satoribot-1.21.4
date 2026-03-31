package github.landminehq.satoribot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SatoriText {
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");

    private SatoriText() {
    }

    static String escapePlainText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String toPlainText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String text = COMMENT_PATTERN.matcher(content).replaceAll("");
        text = TAG_PATTERN.matcher(text).replaceAll("");
        text = unescapeEntities(text);
        return text.replace("\r", "").trim();
    }

    private static String unescapeEntities(String input) {
        String text = input
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");

        Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String encoded = matcher.group(1);
            int codePoint = encoded.startsWith("x") || encoded.startsWith("X")
                    ? Integer.parseInt(encoded.substring(1), 16)
                    : Integer.parseInt(encoded, 10);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf(Character.toChars(codePoint))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
