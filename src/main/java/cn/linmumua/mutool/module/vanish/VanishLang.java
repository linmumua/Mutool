package cn.linmumua.mutool.module.vanish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the vanish module's language file ({@code module/vanish/lang.yml}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_vanish}'s
 * {@code LangSetting}. All texts are rendered through Adventure's {@link MiniMessage} so the
 * resource file keeps its MiniMessage tags (colour gradients, glyphs, etc.). The original
 * module reused a global prefix/lang; here the shared strings are inlined so the module stays
 * self-contained — exactly like the {@code hat} / {@code pvp} ports.</p>
 */
public final class VanishLang {

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

    // --- bossbar text ---

    /** The {@code 隐身中} BossBar title shown to a vanished player. */
    public Component bossBarName() {
        return component("bossbar.vanish");
    }

    // --- /vanish command texts ---

    public Component usage() {
        return component("commands.vanish.usage");
    }

    public Component description() {
        return component("commands.vanish.description");
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }

    /**
     * Build the toggle-result message: {@code commands.vanish.message} with {@code {player}}
     * replaced by the target name and {@code {change}} by the themed on/off text (mirrors the
     * original global {@code enable}/{@code disable} wording).
     */
    public Component message(String player, boolean enable) {
        String change = enable ? raw("global.enable") : raw("global.disable");
        String combined = raw("commands.vanish.message")
                .replace("{player}", player == null ? "-" : player)
                .replace("{change}", change);
        return MINI.deserialize(combined);
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

    public Component databaseUnavailable() {
        return component("global.database_unavailable");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the vanish usage pattern and {@code {command}} replaced by
     * the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.vanish.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }
}
