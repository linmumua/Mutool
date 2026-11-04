package cn.linmumua.mutool.module.fly;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only accessor for the fly module's language file ({@code module/fly/lang.yml}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_fly}'s
 * {@code LangSetting}. All texts are rendered through Adventure's {@link MiniMessage}
 * so the resource file keeps its MiniMessage tags (colour gradients, glyphs, etc.).
 * The original module reused a global prefix/lang; here the shared strings are inlined
 * so the module stays self-contained — exactly like the {@code hat} / {@code ip} ports.</p>
 */
public final class FlyLang {

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

    private boolean present(String key) {
        return this.config != null && this.config.contains(key);
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

    public Component error() {
        return component("global.error");
    }

    public Component playerOffline() {
        return component("global.player_offline");
    }

    public Component playerNotFound() {
        return component("global.player_not_found");
    }

    // --- /fly command texts ---

    public Component flyDescription() {
        return component("commands.fly.description");
    }

    public String plainFlyDescription() {
        return PlainTextComponentSerializer.plainText().serialize(flyDescription());
    }

    /**
     * Build the {@code /fly} "usage error" message: the {@code global.usage_error}
     * template with {@code {usage}} from {@code commands.fly.usage} and {@code {command}}
     * from the label actually used.
     */
    public Component flyUsageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.fly.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /**
     * Build the {@code /fly} toggle result message: {@code {player}} and {@code {change}}
     * are substituted into the raw MiniMessage template before parsing so the tags around
     * the placeholders render correctly. {@code {change}} is the coloured enable/disable
     * word.
     */
    public Component flyMessage(String player, boolean enable) {
        String combined = raw("commands.fly.message")
                .replace("{player}", player == null ? "-" : player)
                .replace("{change}", enable ? raw("global.enable") : raw("global.disable"));
        return MINI.deserialize(combined);
    }

    /** One fall-countdown title (3 / 2 / 1 / 0), or {@code null} when not configured. */
    public FallMessage fallMessage(long time) {
        String base = "commands.fly.fall_messages." + time;
        if (!present(base + ".title") && !present(base + ".subtitle")) {
            return null;
        }
        return new FallMessage(
                MINI.deserialize(raw(base + ".title")),
                MINI.deserialize(raw(base + ".subtitle")),
                this.config.getInt(base + ".in", 10),
                this.config.getInt(base + ".step", 70),
                this.config.getInt(base + ".out", 20)
        );
    }

    /** A single fall-countdown title definition. */
    public record FallMessage(Component title, Component subtitle, int in, int step, int out) {
    }

    // --- /flytime command texts ---

    public Component flyTimeDescription() {
        return component("commands.flytime.description");
    }

    public String plainFlyTimeDescription() {
        return PlainTextComponentSerializer.plainText().serialize(flyTimeDescription());
    }

    public Component timeFormatError() {
        return component("commands.flytime.time_format_error");
    }

    /**
     * Build the {@code /flytime} result message for the given sub-command
     * ({@code set} / {@code add} / {@code take}). Each sub-command has its own template
     * with {@code {player}} / {@code {change}} / {@code {amount}} placeholders.
     */
    public Component flyTimeMessage(String mode, String player, String change, String amount) {
        String key = "commands.flytime.sub_commands." + mode + ".message";
        String combined = raw(key)
                .replace("{player}", player == null ? "-" : player)
                .replace("{change}", change == null ? "" : change)
                .replace("{amount}", amount == null ? "" : amount);
        return MINI.deserialize(combined);
    }

    /**
     * Build the {@code /flytime} help panel: the {@code help.message} template with the
     * four sub-command lines substituted into {@code {help_list}} and {@code {command}}
     * replaced by the label used to invoke the command.
     */
    public Component flyTimeHelp(String label) {
        String combined = raw("commands.flytime.sub_commands.help.message")
                .replace("{help_list}", this.helpList(label))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    private String helpList(String label) {
        List<String> lines = new ArrayList<>();
        lines.add(this.helpLine("help", label));
        lines.add(this.helpLine("set", label));
        lines.add(this.helpLine("add", label));
        lines.add(this.helpLine("take", label));
        return String.join("\n", lines);
    }

    private String helpLine(String mode, String label) {
        String base = "commands.flytime.sub_commands." + mode;
        String usage = raw(base + ".usage").replace("{command}", label);
        String description = raw(base + ".description");
        return "<dark_gray>▪ <white>" + usage + " <gray>- " + description;
    }
}
