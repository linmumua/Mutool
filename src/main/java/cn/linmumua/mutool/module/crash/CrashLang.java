package cn.linmumua.mutool.module.crash;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the crash module's language file ({@code module/crash/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file
 * keeps its MiniMessage tags (colors, glyphs, ...). Mirrors {@link
 * cn.linmumua.mutool.module.hat.HatLang} but adds the {@code {player}} / {@code {type}}
 * substitution used by {@code /crash}.</p>
 */
public final class CrashLang {

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

    // --- /crash command texts ---

    public Component usage() {
        return component("commands.crash.usage");
    }

    public Component description() {
        return component("commands.crash.description");
    }

    public Component noType() {
        return component("commands.crash.no_type");
    }

    /**
     * Build the success message: the {@code commands.crash.message} template with
     * {@code {player}} replaced by the target name and {@code {type}} by the raw
     * (MiniMessage) type label from {@link #typeRaw(String)}.
     */
    public Component message(String player, String typeRaw) {
        String combined = raw("commands.crash.message")
                .replace("{player}", player == null ? "" : player)
                .replace("{type}", typeRaw == null ? "" : typeRaw);
        return MINI.deserialize(combined);
    }

    /**
     * Raw (MiniMessage) label for a crash type, used both for the success message's
     * {@code {type}} replacement and standalone rendering.
     *
     * @param type one of {@code explosion}, {@code invalid_teleport}, {@code invalid_particle}
     * @return the raw lang value, or the type itself when unknown
     */
    public String typeRaw(String type) {
        return switch (type) {
            case "explosion" -> raw("commands.crash.types.explosion");
            case "invalid_teleport" -> raw("commands.crash.types.invalid_teleport");
            case "invalid_particle" -> raw("commands.crash.types.invalid_particle");
            default -> type == null ? "" : type;
        };
    }

    // --- shared texts ---

    public Component playerOffline() {
        return component("global.player_offline");
    }

    public Component noPermission() {
        return component("global.no_permission");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the crash usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.crash.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
