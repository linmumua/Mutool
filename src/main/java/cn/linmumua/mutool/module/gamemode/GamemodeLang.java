package cn.linmumua.mutool.module.gamemode;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the gamemode module's language file ({@code module/gamemode/lang.yml}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_gamemode}'s
 * {@code LangSetting}. All texts are rendered through Adventure's {@link MiniMessage}
 * so the resource file keeps its MiniMessage tags (colour gradients, glyphs, etc.).
 * The original module reused a global prefix/lang; here the few shared strings
 * (no-permission / usage-error / player-offline) are inlined into the {@code global}
 * section so the module stays self-contained — exactly like the {@code hat} /
 * {@code fly} ports.</p>
 */
public final class GamemodeLang {

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

    public Component noPermission() {
        return component("global.no_permission");
    }

    public Component playerOffline() {
        return component("global.player_offline");
    }

    // --- gamemode display names ---

    /**
     * Human-facing (MiniMessage) name of the given {@link GameMode}, e.g.
     * {@code SURVIVAL -> 生存模式}. Falls back to the enum name when not configured.
     */
    public Component gameModeName(GameMode gameMode) {
        if (gameMode == null) {
            return Component.empty();
        }
        String key = "gamemode." + gameMode.name();
        String value = raw(key);
        if (value == null || value.isEmpty()) {
            value = gameMode.name();
        }
        return MINI.deserialize(value);
    }

    // --- /gamemode command texts ---

    public Component usage() {
        return component("commands.gamemode.usage");
    }

    public Component description() {
        return component("commands.gamemode.description");
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }

    /** "That gamemode does not exist" message for the given raw input. */
    public Component noGameMode(String input) {
        String combined = raw("commands.gamemode.no_game_mode")
                .replace("{gamemode}", input == null ? "" : input);
        return MINI.deserialize(combined);
    }

    /**
     * Build the "switched gamemode" result message: {@code {player}} and {@code {gamemode}}
     * are substituted into the raw MiniMessage template before parsing so the tags around
     * the placeholders render correctly.
     */
    public Component message(String player, Component gameModeName) {
        String gmRaw = PlainTextComponentSerializer.plainText().serialize(gameModeName);
        // Render the (possibly coloured) gamemode name as its own component, then let the
        // surrounding template provide its styling by substituting the plain text.
        String combined = raw("commands.gamemode.message")
                .replace("{player}", player == null ? "-" : player)
                .replace("{gamemode}", gmRaw);
        return MINI.deserialize(combined);
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the gamemode usage pattern and {@code {command}}
     * replaced by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.gamemode.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }
}
