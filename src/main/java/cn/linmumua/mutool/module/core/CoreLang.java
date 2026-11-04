package cn.linmumua.mutool.module.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the core module's language file ({@code module/core/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (gradient titles, separators, pagination clicks).
 * Placeholders such as {@code {command}} / {@code {help_list}} / {@code {page}} are
 * substituted on the raw MiniMessage template <em>before</em> deserialization, so a
 * replacement value may itself contain MiniMessage markup.</p>
 */
public final class CoreLang {

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

    /** Raw MiniMessage template at {@code key} (empty string when missing). */
    public String raw(String key) {
        if (this.config == null) {
            return "";
        }
        String value = this.config.getString(key, "");
        return value == null ? "" : value;
    }

    private Component component(String key) {
        return MINI.deserialize(raw(key));
    }

    /**
     * Deserialize the template at {@code key} after substituting the given
     * {@code placeholder, value} pairs (in order) on the raw string.
     */
    public Component render(String key, String... placeholders) {
        String value = raw(key);
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            value = value.replace(placeholders[index], placeholders[index + 1]);
        }
        return MINI.deserialize(value);
    }

    // --- shared texts ---

    public Component noPermission() {
        return component("global.no_permission");
    }

    /** Plain-text form of the main command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(component("commands.mutool.description"));
    }
}
