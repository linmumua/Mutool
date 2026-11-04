package cn.linmumua.mutool.module.list;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the list module's language file ({@code module/list/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (color gradients, separator glyphs, etc.). This
 * mirrors the original {@code MumuTools} {@code module_list} texts.</p>
 */
public final class ListLang {

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

    // --- /list command texts ---

    public Component usage() {
        return component("commands.list.usage");
    }

    public Component description() {
        return component("commands.list.description");
    }

    /**
     * Render the status panel with all placeholders substituted:
     * {@code {tps}}, {@code {memory}}, {@code {max_memory}}, {@code {player_count}},
     * {@code {max_player_count}} and {@code {player_list}}.
     */
    public Component message(String tps, long usedMemory, long maxMemory,
                             int playerCount, int maxPlayerCount, String playerList) {
        String combined = raw("commands.list.message")
                .replace("{tps}", tps)
                .replace("{memory}", String.valueOf(usedMemory))
                .replace("{max_memory}", String.valueOf(maxMemory))
                .replace("{player_count}", String.valueOf(playerCount))
                .replace("{max_player_count}", String.valueOf(maxPlayerCount))
                .replace("{player_list}", playerList);
        return MINI.deserialize(combined);
    }

    // --- shared texts ---

    public Component noPermission() {
        return component("global.no_permission");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the list usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.list.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
