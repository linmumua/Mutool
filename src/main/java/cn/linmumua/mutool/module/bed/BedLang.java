package cn.linmumua.mutool.module.bed;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the bed module's language file ({@code module/bed/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (color gradients, glyphs, etc.).</p>
 */
public final class BedLang {

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

    // --- /bed command texts ---

    public Component usage() {
        return component("commands.bed.usage");
    }

    public Component description() {
        return component("commands.bed.description");
    }

    public Component noBed() {
        return component("commands.bed.no_bed");
    }

    public Component message() {
        return component("commands.bed.message");
    }

    // --- shared texts ---

    public Component onlyPlayer() {
        return component("global.only_player");
    }

    public Component noPermission() {
        return component("global.no_permission");
    }

    public Component blackWorld() {
        return component("global.black_world");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the bed usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.bed.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
