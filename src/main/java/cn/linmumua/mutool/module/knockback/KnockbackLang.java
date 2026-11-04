package cn.linmumua.mutool.module.knockback;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the knockback module's language file
 * ({@code module/knockback/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (color gradients, glyphs, etc.). Placeholders in the
 * templates ({@code {player}}, {@code {usage}}, {@code {command}}) are plain-text
 * {@code {...}} markers substituted before MiniMessage parsing, mirroring the original
 * {@code MumuTools} {@code TextComponent#replace(...)} behaviour.</p>
 */
public final class KnockbackLang {

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

    // --- /knockback command texts ---

    public Component usage() {
        return component("commands.knockback.usage");
    }

    public Component description() {
        return component("commands.knockback.description");
    }

    public Component noType() {
        return component("commands.knockback.no_type");
    }

    /** Success message with {@code {player}} replaced by the target's name. */
    public Component message(String playerName) {
        String combined = raw("commands.knockback.message").replace("{player}", playerName);
        return MINI.deserialize(combined);
    }

    /** Localized name for the {@code normal} knockback type. */
    public Component typeNormal() {
        return component("commands.knockback.types.normal");
    }

    /** Localized name for the {@code random} knockback type. */
    public Component typeRandom() {
        return component("commands.knockback.types.random");
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

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the knockback usage pattern and {@code {command}}
     * replaced by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.knockback.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
