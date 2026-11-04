package cn.linmumua.mutool.module.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the economy module's language file ({@code module/economy/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file
 * keeps its MiniMessage tags (color gradients, glyphs, etc.). Placeholders such as
 * {@code {player}} / {@code {amount}} / {@code {money_name}} / {@code {usage}} /
 * {@code {command}} are substituted on the raw MiniMessage template before
 * deserialization.</p>
 */
public final class EconomyLang {

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

    /** The raw (un-deserialized) MiniMessage template at {@code key}. */
    public String raw(String key) {
        if (this.config == null) {
            return "";
        }
        String value = this.config.getString(key, "");
        return value == null ? "" : value;
    }

    /** Deserialize the template at {@code key} to a component. */
    public Component component(String key) {
        return MINI.deserialize(raw(key));
    }

    /** Deserialize the template at {@code key} after substituting the given {@code key,value} pairs. */
    public Component render(String key, String... placeholders) {
        String value = raw(key);
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            value = value.replace(placeholders[index], placeholders[index + 1]);
        }
        return MINI.deserialize(value);
    }

    // --- shared texts ---

    public Component onlyPlayer() {
        return component("global.only_player");
    }

    public Component noPermission() {
        return component("global.no_permission");
    }

    public Component playerNotFound() {
        return component("global.player_not_found");
    }

    public Component databaseError() {
        return component("global.database_error");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the usage pattern at {@code usageKey} and
     * {@code {command}} replaced by the label actually used to invoke the command.
     */
    public Component usageError(String label, String usageKey) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw(usageKey))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the description at {@code key}, for {@code PluginCommand#setDescription}. */
    public String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(key));
    }
}
