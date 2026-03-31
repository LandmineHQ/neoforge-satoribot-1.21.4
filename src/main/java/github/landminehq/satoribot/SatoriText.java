package github.landminehq.satoribot;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SatoriText {
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?s)<\\s*(/?)\\s*([a-zA-Z][\\w-]*)[^>]*?/?>");
    private static final Pattern ATTRIBUTE_PATTERN = Pattern.compile("([:\\w-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x?[0-9A-Fa-f]+);");
    private static final Pattern BLANK_LINE_PATTERN = Pattern.compile("\\n{3,}");
    private static final Pattern INLINE_SPACE_PATTERN = Pattern.compile("[\\t\\x0B\\f ]+");

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
        text = replaceTags(text);
        text = unescapeEntities(text);
        text = text.replace("\r", "");
        text = INLINE_SPACE_PATTERN.matcher(text).replaceAll(" ");
        text = text.replaceAll(" *\\n *", "\n");
        text = BLANK_LINE_PATTERN.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private static String replaceTags(String input) {
        Matcher matcher = TAG_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String tagName = matcher.group(2).toLowerCase(Locale.ROOT);
            String replacement = describeTag(tagName, matcher.group(), closing);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String describeTag(String tagName, String rawTag, boolean closing) {
        if (closing) {
            return switch (tagName) {
                case "p", "br", "message", "quote" -> "\n";
                default -> "";
            };
        }

        return switch (tagName) {
            case "img" -> " [图片] ";
            case "audio" -> " [语音] ";
            case "video" -> " [视频] ";
            case "file" -> " [文件] ";
            case "emoji", "face" -> describeEmojiTag(rawTag);
            case "a" -> describeLinkTag(rawTag);
            case "at" -> describeAtTag(rawTag);
            case "sharp" -> describeSharpTag(rawTag);
            case "quote" -> " [引用] ";
            case "author" -> describeAuthorTag(rawTag);
            case "button" -> describeButtonTag(rawTag);
            case "br", "p", "message" -> "\n";
            case "b", "strong", "i", "em", "u", "ins", "s", "del", "spl", "code", "sup", "sub" -> "";
            default -> describeUnknownTag(tagName, rawTag);
        };
    }

    private static String describeLinkTag(String rawTag) {
        String href = getAttribute(rawTag, "href");
        if (!href.isBlank() && rawTag.endsWith("/>")) {
            return " " + href + " ";
        }
        return "";
    }

    private static String describeAtTag(String rawTag) {
        String type = getAttribute(rawTag, "type");
        if ("all".equalsIgnoreCase(type)) {
            return " @全体成员 ";
        }
        if ("here".equalsIgnoreCase(type)) {
            return " @在线成员 ";
        }

        String name = getAttribute(rawTag, "name");
        if (!name.isBlank()) {
            return " @" + name + " ";
        }

        String role = getAttribute(rawTag, "role");
        if (!role.isBlank()) {
            return " @身份组:" + role + " ";
        }

        String id = getAttribute(rawTag, "id");
        if (!id.isBlank()) {
            return " @" + id + " ";
        }

        return " [提及] ";
    }

    private static String describeEmojiTag(String rawTag) {
        String name = getAttribute(rawTag, "name");
        if (!name.isBlank()) {
            return " [表情:" + name + "] ";
        }

        String id = getAttribute(rawTag, "id");
        if (!id.isBlank()) {
            return " [表情:" + id + "] ";
        }

        return " [表情] ";
    }

    private static String describeUnknownTag(String tagName, String rawTag) {
        if (rawTag.endsWith("/>")) {
            return " [" + tagName + "] ";
        }
        return "";
    }

    private static String describeSharpTag(String rawTag) {
        String name = getAttribute(rawTag, "name");
        if (!name.isBlank()) {
            return " #" + name + " ";
        }

        String id = getAttribute(rawTag, "id");
        if (!id.isBlank()) {
            return " #" + id + " ";
        }

        return " [频道] ";
    }

    private static String describeAuthorTag(String rawTag) {
        String name = getAttribute(rawTag, "name");
        if (!name.isBlank()) {
            return " [作者:" + name + "] ";
        }

        String id = getAttribute(rawTag, "id");
        if (!id.isBlank()) {
            return " [作者:" + id + "] ";
        }

        return " [作者] ";
    }

    private static String describeButtonTag(String rawTag) {
        String text = getAttribute(rawTag, "text");
        if (!text.isBlank()) {
            return " [按钮:" + text + "] ";
        }

        String link = getAttribute(rawTag, "link");
        if (!link.isBlank()) {
            return " [按钮链接:" + link + "] ";
        }

        String type = getAttribute(rawTag, "type");
        if (!type.isBlank()) {
            return " [按钮类型:" + type + "] ";
        }

        return " [按钮] ";
    }

    private static String getAttribute(String rawTag, String key) {
        Matcher matcher = ATTRIBUTE_PATTERN.matcher(rawTag);
        while (matcher.find()) {
            if (key.equalsIgnoreCase(matcher.group(1))) {
                String doubleQuoted = matcher.group(3);
                String singleQuoted = matcher.group(4);
                return doubleQuoted != null ? doubleQuoted : singleQuoted != null ? singleQuoted : "";
            }
        }
        return "";
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
