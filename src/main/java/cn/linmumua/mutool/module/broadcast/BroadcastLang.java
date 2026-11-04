package cn.linmumua.mutool.module.broadcast;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the broadcast module's language file ({@code module/broadcast/lang.yml}).
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_broadcast}'s
 * {@code LangSetting}. All texts are rendered through Adventure's {@link MiniMessage}
 * so the resource file keeps its MiniMessage tags. The few shared strings (no-permission /
 * usage-error) are inlined into the {@code global} section so the module stays
 * self-contained — exactly like the {@code hat} / {@code gamemode} ports.</p>
 */
public final class BroadcastLang {

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

    // --- /broadcast command texts ---

    public Component usage() {
        return component("commands.broadcast.usage");
    }

    public Component description() {
        return component("commands.broadcast.description");
    }

    /** Success prompt shown to the sender after a broadcast is dispatched. */
    public Component message() {
        return component("commands.broadcast.message");
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }

    /**
     * Render the broadcast body: the {@code commands.broadcast.format} template
     * ({@code "{message}"}) with the raw, user-supplied content substituted in, then parsed
     * as MiniMessage.
     *
     * <p>The raw content is inserted verbatim — so any MiniMessage tags the (permitted)
     * announcer typed are honoured — while the fallback keeps a stray {@code <} from aborting
     * the whole broadcast: when the composed string is not valid MiniMessage it is rendered
     * as plain text instead.</p>
     */
    public Component render(String rawMessage) {
        String template = raw("commands.broadcast.format");
        if (template == null || template.isEmpty()) {
            template = "{message}";
        }
        String combined = template.replace("{message}", rawMessage == null ? "" : rawMessage);
        try {
            return MINI.deserialize(combined);
        } catch (Throwable ignored) {
            return Component.text(combined);
        }
    }

    // --- shared texts ---

    public Component noPermission() {
        return component("global.no_permission");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the broadcast usage pattern and {@code {command}} replaced
     * by the label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.broadcast.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }
}
