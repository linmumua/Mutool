package cn.linmumua.mutool.module.tpa;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the tpa module's language file ({@code module/tpa/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file keeps
 * its MiniMessage tags (color gradients, glyphs, {@code <click:run_command:...>} buttons, etc.).
 * Placeholders such as {@code {player}} / {@code {delay}} / {@code {usage}} / {@code {command}}
 * are substituted on the raw MiniMessage template before deserialization.</p>
 */
public final class TpaLang {

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

    /** Deserialize the template at {@code key} after substituting the given {@code key, value} pairs. */
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

    public Component playerOffline() {
        return component("global.player_offline");
    }

    // --- /tpa command description / usage ---

    public Component description() {
        return component("commands.tpa.description");
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with
     * {@code {usage}} replaced by the tpa usage pattern and {@code {command}} replaced by the
     * label actually used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.tpa.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    // --- request flow ---

    /** "已向 {player} 发送传送请求" — shown to the requester after a same-server request. */
    public Component requestSent(String target) {
        return render("commands.tpa.message", "{player}", target);
    }

    /** "已向 {player} 发送跨服传送请求" — shown to the requester after a cross-server request. */
    public Component requestSentCross(String target) {
        return render("commands.tpa.cross.sent", "{player}", target);
    }

    /** The clickable accept/reject request panel shown to the target. */
    public Component requestPanel(String requester) {
        return render("commands.tpa.request_message", "{player}", requester);
    }

    /** "冷却中，请 {delay} 秒后再试". */
    public Component inDelay(long seconds) {
        return render("commands.tpa.in_delay", "{delay}", String.valueOf(seconds));
    }

    /** "没有收到 {player} 的请求". */
    public Component noRequest(String player) {
        return render("commands.tpa.no_request", "{player}", player);
    }

    public Component sendSelf() {
        return component("commands.tpa.send_self");
    }

    // --- accept ---

    /** "已接受 {player} 的传送请求" — shown to the accepting player. */
    public Component acceptMessage(String requester) {
        return render("commands.tpa.accept.message", "{player}", requester);
    }

    /** "{player} 接受了你的传送请求" — shown to the requester. */
    public Component accepted(String target) {
        return render("commands.tpa.accept.accepted", "{player}", target);
    }

    /** Cross-server accept degrade shown to the accepting player. */
    public Component acceptCrossDegrade(String requester) {
        return render("commands.tpa.cross.accept_degrade", "{player}", requester);
    }

    /** Cross-server accept notice shown to the requester on the origin server. */
    public Component acceptCrossNotify(String target) {
        return render("commands.tpa.cross.accept_notify", "{player}", target);
    }

    // --- reject ---

    /** "已拒绝 {player} 的传送请求" — shown to the rejecting player. */
    public Component rejectMessage(String requester) {
        return render("commands.tpa.reject.message", "{player}", requester);
    }

    /** "{player} 拒绝了你的传送请求" — shown to the requester. */
    public Component rejected(String target) {
        return render("commands.tpa.reject.rejected", "{player}", target);
    }

    /** Cross-server reject notice shown to the requester on the origin server. */
    public Component rejectCrossNotify(String target) {
        return render("commands.tpa.cross.reject_notify", "{player}", target);
    }

    // --- timeout ---

    /** "发给 {player} 的传送请求已过期" — shown to the requester. */
    public Component expireRequester(String target) {
        return render("commands.tpa.delay.message", "{player}", target);
    }

    /** "{player} 的传送请求已过期" — shown to the target. */
    public Component expireTarget(String requester) {
        return render("commands.tpa.delay.time_out", "{player}", requester);
    }

    // --- menu ---

    public Component openMenuMessage() {
        return component("commands.tpa.open_menu_message");
    }

    public Component menuNoPlayers() {
        return component("commands.tpa.menu_no_players");
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
