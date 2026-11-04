package cn.linmumua.mutool.module.home;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the home module's language file ({@code module/home/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file
 * keeps its MiniMessage tags (color gradients, glyphs, etc.). Placeholders such as
 * {@code {home}} / {@code {world}} / {@code {amount}} / {@code {usage}} / {@code {command}}
 * are substituted on the raw MiniMessage template before deserialization.</p>
 */
public final class HomeLang {

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

    /** Deserialize the template at {@code key} after substituting the given pairs. */
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

    public Component databaseError() {
        return component("global.database_error");
    }

    public Component blackWorld() {
        return component("global.black_world");
    }

    // --- /home texts ---

    public Component homeNoHome(String home) {
        return render("commands.home.no_home", "{home}", home);
    }

    public Component homeOpenMenu() {
        return component("commands.home.open_menu_message");
    }

    public Component homeInvalidName() {
        return component("commands.home.invalid_name");
    }

    public Component homeWorldMissing(String world) {
        return render("commands.home.world_missing", "{world}", world);
    }

    public Component homeMessage(String home) {
        return render("commands.home.message", "{home}", home);
    }

    // --- /sethome texts ---

    public Component setHomeHaveHome(String home) {
        return render("commands.sethome.have_home", "{home}", home);
    }

    public Component setIsMax(int amount) {
        return render("commands.sethome.is_max", "{amount}", String.valueOf(amount));
    }

    public Component setHomeMessage(String home) {
        return render("commands.sethome.message", "{home}", home);
    }

    // --- /delhome texts ---

    public Component delHomeNoHome(String home) {
        return render("commands.delhome.no_home", "{home}", home);
    }

    public Component delHomeMessage(String home) {
        return render("commands.delhome.message", "{home}", home);
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

    /** Plain-text form of the template at {@code key}, for {@code PluginCommand#setDescription}. */
    public String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(key));
    }
}
