package cn.linmumua.mutool.module.invsee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the invsee module's language file ({@code module/invsee/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file keeps
 * its MiniMessage tags (colour gradients, glyphs, etc.). Placeholders such as {@code {player}},
 * {@code {type}}, {@code {usage}} and {@code {command}} are substituted on the raw MiniMessage
 * template <em>before</em> deserialization.</p>
 */
public final class InvseeLang {

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

    /** Deserialize the template at {@code key} after substituting the given {@code key, value} pairs. */
    private Component render(String key, String... placeholders) {
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

    public Component playerOffline() {
        return component("global.player_offline");
    }

    // --- /invsee texts ---

    public Component description() {
        return component("commands.invsee.description");
    }

    public Component noType(String type) {
        return render("commands.invsee.no_type", "{type}", type);
    }

    /**
     * The "已打开 …" confirmation. {@code typeKey} is the argument ({@code armor} / {@code inventory}
     * / {@code enderchest}); its localized display name is looked up from
     * {@code commands.invsee.types.<typeKey>} and substituted into the {@code {type}} placeholder.
     */
    public Component opened(String player, String typeKey) {
        String typeText = raw("commands.invsee.types." + typeKey);
        String template = raw("commands.invsee.message")
                .replace("{player}", player)
                .replace("{type}", typeText);
        return MINI.deserialize(template);
    }

    /** Title for the read-only inventory snapshot menu. */
    public Component inventoryTitle(String player) {
        return render("commands.invsee.titles.inventory", "{player}", player);
    }

    /** Title for the read-only ender chest snapshot menu. */
    public Component enderchestTitle(String player) {
        return render("commands.invsee.titles.enderchest", "{player}", player);
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with {@code {usage}}
     * replaced by the invsee usage pattern and {@code {command}} replaced by the label actually used
     * to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.invsee.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
