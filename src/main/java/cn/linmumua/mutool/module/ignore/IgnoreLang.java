package cn.linmumua.mutool.module.ignore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the ignore module's language file ({@code module/ignore/lang.yml}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_ignore}'s
 * {@code LangSetting}. All texts are rendered through Adventure's {@link MiniMessage}
 * so the resource file keeps its MiniMessage tags (color gradients, glyphs, etc.).</p>
 *
 * <p>Unlike the legacy implementation this class has no dependency on a global lang
 * file: every message the module needs (including the shared {@code only_player} /
 * {@code no_permission} / {@code player_not_found}) is inlined under {@code global:}
 * so the module stays self-contained.</p>
 */
public final class IgnoreLang {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Configuration config;

    /** Load (or reload from disk) the given language file. */
    public void load(File file) {
        this.config = Configuration.Companion.loadFromFile(file, Type.YAML, false);
    }

    /** Reload the already-loaded language file. No-op when never loaded. */
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
    }

    private String raw(String key) {
        if (this.config == null) {
            return "";
        }
        String value = this.config.getString(key, "");
        return value == null ? "" : value;
    }

    private Component component(String key) {
        return MINI.deserialize(raw(key));
    }

    /** Read a template, replace the given {@code key/value} pairs, then parse as MiniMessage. */
    private Component fill(String key, String... pairs) {
        String text = raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return MINI.deserialize(text);
    }

    // --- shared texts ---

    public Component onlyPlayer() {
        return component("global.only_player");
    }

    public Component noPermission() {
        return component("global.no_permission");
    }

    public Component playerNotFound(String player) {
        return fill("global.player_not_found", "{player}", player);
    }

    public Component databaseUnavailable() {
        return component("global.database_unavailable");
    }

    // --- /ignore command texts ---

    /** Plain description for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(component("commands.ignore.description"));
    }

    public Component listMessage(String list) {
        return fill("commands.ignore.sub_commands.list.message", "{list}", list);
    }

    public Component addMessage(String player) {
        return fill("commands.ignore.sub_commands.add.message", "{player}", player);
    }

    public Component addHaveIgnore(String player) {
        return fill("commands.ignore.sub_commands.add.have_ignore", "{player}", player);
    }

    public Component addBlacklist(String player) {
        return fill("commands.ignore.sub_commands.add.blacklist", "{player}", player);
    }

    public Component addIgnoreSelf() {
        return component("commands.ignore.sub_commands.add.ignore_self");
    }

    public Component removeMessage(String player) {
        return fill("commands.ignore.sub_commands.remove.message", "{player}", player);
    }

    public Component removeNoIgnore(String player) {
        return fill("commands.ignore.sub_commands.remove.no_ignore", "{player}", player);
    }

    /**
     * Build the help panel: the four sub-command lines (help/list/add/remove), each laid
     * out through {@code command_info_format}, then substituted into the multi-line
     * {@code help.message} template at {@code {help_list}}.
     */
    public Component helpMessage(String label) {
        String format = raw("command_info_format");
        StringBuilder helpList = new StringBuilder();
        appendHelpLine(helpList, format, "help", label);
        appendHelpLine(helpList, format, "list", label);
        appendHelpLine(helpList, format, "add", label);
        appendHelpLine(helpList, format, "remove", label);

        return fill("commands.ignore.sub_commands.help.message",
                "{help_list}", helpList.toString(),
                "{command}", label);
    }

    private void appendHelpLine(StringBuilder sb, String format, String sub, String label) {
        String usage = raw("commands.ignore.sub_commands." + sub + ".usage").replace("{command}", label);
        String description = raw("commands.ignore.sub_commands." + sub + ".description");
        String line = format.replace("{usage}", usage).replace("{description}", description);
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }
}
