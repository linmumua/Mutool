package cn.linmumua.mutool.module.chat;

import org.bukkit.entity.Player;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, parsed view of {@code module/chat/config.yml}.
 *
 * <p>Built on every {@link ChatModule#reload()} so the listener / commands always read a
 * consistent snapshot instead of hitting the raw {@link Configuration} on the hot path.
 * Format groups keep their weight and MiniMessage template; {@link #select(Player)} picks the
 * highest-weight group the player is entitled to.</p>
 */
final class ChatConfig {

    private static final String DEFAULT_FORMAT = "<white>{player}<white>: {message}";

    private final boolean delayEnabled;
    private final int delaySeconds;
    private final boolean spamEnabled;

    private final boolean atEnabled;
    private final List<String> atAllMessage;

    private final boolean msgEnabled;
    private final List<String> msgCommands;
    private final List<String> replyCommands;

    private final boolean formatEnabled;
    private final Map<String, Group> groups;

    private ChatConfig(boolean delayEnabled, int delaySeconds, boolean spamEnabled,
                       boolean atEnabled, List<String> atAllMessage,
                       boolean msgEnabled, List<String> msgCommands, List<String> replyCommands,
                       boolean formatEnabled, Map<String, Group> groups) {
        this.delayEnabled = delayEnabled;
        this.delaySeconds = delaySeconds;
        this.spamEnabled = spamEnabled;
        this.atEnabled = atEnabled;
        this.atAllMessage = atAllMessage;
        this.msgEnabled = msgEnabled;
        this.msgCommands = msgCommands;
        this.replyCommands = replyCommands;
        this.formatEnabled = formatEnabled;
        this.groups = groups;
    }

    static ChatConfig load(Configuration config) {
        List<String> atAll = sanitizeList(config.getStringList("at.all_message"), "all");
        List<String> msgCommands = sanitizeList(config.getStringList("msg.commands"), "msg");
        List<String> replyCommands = sanitizeList(config.getStringList("msg.reply_commands"), "reply");

        Map<String, Group> groups = new LinkedHashMap<>();
        ConfigurationSection formatSection = config.getConfigurationSection("format");
        if (formatSection != null) {
            for (String key : formatSection.getKeys(false)) {
                if ("enable".equals(key) || "prefix".equals(key)) {
                    continue;
                }
                ConfigurationSection group = formatSection.getConfigurationSection(key);
                if (group == null) {
                    continue;
                }
                String format = group.getString("format", DEFAULT_FORMAT);
                groups.put(key, new Group(group.getInt("weight", 0), format == null ? DEFAULT_FORMAT : format));
            }
        }

        return new ChatConfig(
                config.getBoolean("delay.enable", true),
                Math.max(config.getInt("delay.delay", 0), 0),
                config.getBoolean("spam.enable", true),
                config.getBoolean("at.enable", true),
                atAll,
                config.getBoolean("msg.enable", true),
                msgCommands,
                replyCommands,
                config.getBoolean("format.enable", true),
                groups
        );
    }

    private static List<String> sanitizeList(List<String> values, String fallback) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        if (out.isEmpty()) {
            out.add(fallback);
        }
        return out;
    }

    /** The highest-weight format group the player is entitled to, or the built-in default. */
    Group select(Player player) {
        if (!this.formatEnabled) {
            return new Group(0, DEFAULT_FORMAT);
        }
        Group best = null;
        for (Map.Entry<String, Group> entry : this.groups.entrySet()) {
            boolean eligible = "default".equals(entry.getKey())
                    || player.hasPermission("mutool.group.chatformat." + entry.getKey());
            if (!eligible) {
                continue;
            }
            if (best == null || entry.getValue().weight > best.weight) {
                best = entry.getValue();
            }
        }
        return best == null ? new Group(0, DEFAULT_FORMAT) : best;
    }

    boolean delayEnabled() {
        return this.delayEnabled;
    }

    int delaySeconds() {
        return this.delaySeconds;
    }

    boolean spamEnabled() {
        return this.spamEnabled;
    }

    boolean atEnabled() {
        return this.atEnabled;
    }

    List<String> atAllMessage() {
        return this.atAllMessage;
    }

    boolean msgEnabled() {
        return this.msgEnabled;
    }

    List<String> msgCommands() {
        return this.msgCommands;
    }

    List<String> replyCommands() {
        return this.replyCommands;
    }

    /** A single chat-format group: its weight and MiniMessage template. */
    static final class Group {
        private final int weight;
        private final String format;

        Group(int weight, String format) {
            this.weight = weight;
            this.format = format;
        }

        int weight() {
            return this.weight;
        }

        String format() {
            return this.format;
        }
    }
}
