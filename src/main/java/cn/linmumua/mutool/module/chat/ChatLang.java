package cn.linmumua.mutool.module.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for {@code module/chat/lang.yml}.
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file keeps
 * its MiniMessage tags (colour gradients, glyphs, etc.). The {@code *WithMessage} helpers splice
 * a pre-rendered message {@link Component} into a template that contains a {@code {message}}
 * token, so player-typed content (which may itself be MiniMessage) is never re-parsed.</p>
 */
final class ChatLang {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Configuration config;

    /** Load (or reload from disk) the given language file. */
    void load(File file) {
        this.config = Configuration.Companion.loadFromFile(file, Type.YAML, false);
    }

    /** Reload the already-loaded language file. No-op when never loaded. */
    void reload() {
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

    Component onlyPlayer() {
        return component("global.only_player");
    }

    Component noPermission() {
        return component("global.no_permission");
    }

    Component playerOffline(String name) {
        return MINI.deserialize(raw("global.player_offline").replace("{player}", name));
    }

    Component playerNotFound(String name) {
        return MINI.deserialize(raw("global.player_not_found").replace("{player}", name));
    }

    /** {@code global.usage_error} with {@code {usage}} (from {@code usageKey}) and {@code {command}}. */
    Component usageError(String label, String usageKey) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw(usageKey))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of an arbitrary language key (for {@code PluginCommand#setDescription}). */
    String plainDescription(String key) {
        return PlainTextComponentSerializer.plainText().serialize(component(key));
    }

    // --- chat ---

    Component delay(int seconds) {
        return MINI.deserialize(raw("chat.delay").replace("{delay}", String.valueOf(seconds)));
    }

    Component spam() {
        return component("chat.spam");
    }

    // --- at ---

    String atAllText() {
        return raw("chat.at.all");
    }

    Component atFormat(String target) {
        return MINI.deserialize(raw("chat.at.format").replace("{target}", target));
    }

    Component atTitleTitle(String by) {
        return MINI.deserialize(raw("chat.at.title.title").replace("{by}", by));
    }

    Component atTitleSubtitle(String by) {
        return MINI.deserialize(raw("chat.at.title.subtitle").replace("{by}", by));
    }

    int atTitleIn() {
        return this.config == null ? 20 : this.config.getInt("chat.at.title.in", 20);
    }

    int atTitleStep() {
        return this.config == null ? 60 : this.config.getInt("chat.at.title.step", 60);
    }

    int atTitleOut() {
        return this.config == null ? 20 : this.config.getInt("chat.at.title.out", 20);
    }

    // --- msg / reply commands ---

    Component msgSend(String player, String target, Component message) {
        return withMessage("commands.msg.send", message,
                "{player}", player, "{target}", target);
    }

    Component msgReceive(String player, String target, Component message) {
        return withMessage("commands.msg.receive", message,
                "{player}", player, "{target}", target);
    }

    Component replyNoTarget() {
        return component("commands.reply.no_target");
    }

    // --- helper ---

    /** Replace the given key/value string pairs, then splice {@code {message}} in as a component. */
    private Component withMessage(String key, Component message, String... pairs) {
        String template = raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            template = template.replace(pairs[i], pairs[i + 1]);
        }
        return spliceMessage(template, message);
    }

    /** Split a MiniMessage template around the {@code {message}} token and insert the component. */
    static Component spliceMessage(String template, Component message) {
        int index = template.indexOf("{message}");
        if (index < 0) {
            return MINI.deserialize(template);
        }
        Component before = MINI.deserialize(template.substring(0, index));
        Component after = MINI.deserialize(template.substring(index + "{message}".length()));
        return Component.text().append(before).append(message).append(after).build();
    }
}
