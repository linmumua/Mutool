package cn.linmumua.mutool.module.fastchangeweather;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the fastchangeweather module's language file
 * ({@code module/fastchangeweather/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (color gradients, glyphs, etc.).</p>
 */
public final class FastChangeWeatherLang {

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

    // --- /fastchangeweather command texts ---

    public Component usage() {
        return component("commands.fastchangeweather.usage");
    }

    public Component description() {
        return component("commands.fastchangeweather.description");
    }

    /**
     * Build the "weather changed" message from
     * {@code commands.fastchangeweather.message}, replacing {@code {storm}} and
     * {@code {thunder}} with the shared enable / disable wording according to the
     * booleans that were applied. Mirrors the original module's behaviour.
     */
    public Component message(boolean storm, boolean thunder) {
        String combined = raw("commands.fastchangeweather.message")
                .replace("{storm}", storm ? raw("global.enable") : raw("global.disable"))
                .replace("{thunder}", thunder ? raw("global.enable") : raw("global.disable"));
        return MINI.deserialize(combined);
    }

    // --- shared texts ---

    public Component noPermission() {
        return component("global.no_permission");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the module usage pattern and {@code {command}}
     * replaced by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.fastchangeweather.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
