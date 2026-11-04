package cn.linmumua.mutool.module.suicide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the suicide module's language file ({@code module/suicide/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource
 * file keeps its MiniMessage tags (color gradients, clickable confirm button, etc.).
 * The confirm prompt's {@code <click:run_command:...>} tag is a native MiniMessage
 * click event — no hand-built {@link Component} is required.</p>
 */
public final class SuicideLang {

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

    // --- /suicide command texts ---

    public Component usage() {
        return component("commands.suicide.usage");
    }

    public Component description() {
        return component("commands.suicide.description");
    }

    /** Prompt shown for a bare {@code /suicide}; contains the clickable {@code /suicide confirm}. */
    public Component confirm() {
        return component("commands.suicide.confirm");
    }

    public Component message() {
        return component("commands.suicide.message");
    }

    // --- shared texts ---

    public Component onlyPlayer() {
        return component("global.only_player");
    }

    public Component noPermission() {
        return component("global.no_permission");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the suicide usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.suicide.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
