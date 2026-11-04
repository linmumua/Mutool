package cn.linmumua.mutool.action;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Text-to-{@link Component} helper shared by the action engine.
 *
 * <p>Messages are authored in <em>MiniMessage</em> — that is the primary format, so a
 * value such as {@code "<gradient:#ffcc00:#ff6600>欢迎</gradient>"} works out of the box.
 * For backwards compatibility with the legacy MumuTools configs (which used
 * {@code &}-codes like {@code &e欢迎加入服务器}), any {@code &}/{@code §} colour code is
 * first translated into the equivalent MiniMessage tag, then the whole string is
 * deserialized with {@link MiniMessage}. Mixing the two in one string therefore works.</p>
 *
 * <p>This class is stateless and never throws on malformed input: {@link MiniMessage}
 * degrades unknown tags to literal text instead of failing.</p>
 */
final class ActionText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ActionText() {
    }

    /** Render a (possibly legacy-coloured) string into an Adventure {@link Component}. */
    static Component render(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(legacyToMiniMessage(text));
    }

    /**
     * Translate legacy {@code &}/{@code §} colour &amp; format codes into MiniMessage tags.
     * Native MiniMessage {@code <tags>} are left untouched (they contain no {@code &}).
     */
    static String legacyToMiniMessage(String input) {
        StringBuilder out = new StringBuilder(input.length() + 16);
        int length = input.length();
        for (int i = 0; i < length; i++) {
            char c = input.charAt(i);
            boolean isMarker = (c == '&' || c == '\u00a7');
            if (!isMarker || i + 1 >= length) {
                out.append(c);
                continue;
            }
            char code = input.charAt(i + 1);
            // &#RRGGBB  (legacy hex)
            if (code == '#' && i + 7 < length && isHex(input, i + 2, i + 8)) {
                out.append("<#").append(input, i + 2, i + 8).append('>');
                i += 7;
                continue;
            }
            String tag = legacyTag(code);
            if (tag != null) {
                out.append(tag);
                i += 1; // consume the code char; the marker is already dropped
            } else {
                out.append(c); // not a colour code, keep the '&'/'§' literally
            }
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Map a single legacy code char to a MiniMessage tag, or {@code null} if not a colour code. */
    private static String legacyTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }
}
