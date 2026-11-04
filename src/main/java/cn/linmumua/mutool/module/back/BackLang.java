package cn.linmumua.mutool.module.back;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the back module's language file ({@code module/back/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file
 * keeps its MiniMessage tags (color gradients, glyphs, etc.). Placeholders such as
 * {@code {usage}} / {@code {command}} are substituted on the raw MiniMessage template
 * before deserialization.</p>
 */
public final class BackLang {

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

    // --- /back command texts ---

    public Component usage() {
        return component("commands.back.usage");
    }

    public Component description() {
        return component("commands.back.description");
    }

    public Component noLocation() {
        return component("commands.back.no_location");
    }

    public Component worldMissing(String world) {
        return MINI.deserialize(raw("commands.back.world_missing").replace("{world}", world));
    }

    public Component respawnMessage() {
        return component("commands.back.respawn_message");
    }

    public Component openMenuMessage() {
        return component("commands.back.open_menu_message");
    }

    public Component message() {
        return component("commands.back.message");
    }

    /**
     * Human-readable name for a stored record type ({@code teleport} / {@code death}).
     * Used as the {@code {type}} placeholder inside the menu lore. Falls back to the
     * raw type id when unknown.
     */
    public String typeName(String type) {
        String name = raw("commands.back.types." + type);
        return name.isBlank() ? type : name;
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the back usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.back.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
