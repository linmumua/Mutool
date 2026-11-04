package cn.linmumua.mutool.module.warp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the warp module's language file ({@code module/warp/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file
 * keeps its MiniMessage tags (color gradients, glyphs, etc.). Placeholders such as
 * {@code {warp}} / {@code {world}} / {@code {usage}} / {@code {command}} are substituted
 * on the raw MiniMessage template before deserialization.</p>
 */
public final class WarpLang {

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

    // --- /warp texts ---

    public Component warpNoWarp(String warp) {
        return render("commands.warp.no_warp", "{warp}", warp);
    }

    public Component worldMissing(String world) {
        return render("commands.warp.world_missing", "{world}", world);
    }

    public Component warpMessage(String warp) {
        return render("commands.warp.message", "{warp}", warp);
    }

    // --- /setwarp texts ---

    public Component setWarpMessage(String warp) {
        return render("commands.setwarp.message", "{warp}", warp);
    }

    // --- /delwarp texts ---

    public Component delWarpNoWarp(String warp) {
        return render("commands.delwarp.no_warp", "{warp}", warp);
    }

    public Component delWarpMessage(String warp) {
        return render("commands.delwarp.message", "{warp}", warp);
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
