package cn.linmumua.mutool.module.tpahere;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Read-only accessor for the tpahere module's language file ({@code module/tpahere/lang.yml}).
 *
 * <p>All texts are rendered through Adventure's {@link MiniMessage} so the resource file keeps
 * its MiniMessage tags (color gradients, glyphs, clickable buttons, etc.). Placeholders such as
 * {@code {player}} / {@code {delay}} / {@code {usage}} / {@code {command}} are substituted on the
 * raw MiniMessage template before deserialization so user-provided names can never inject tags.</p>
 *
 * <p>Ported from {@code MumuTools} {@code module_tpahere} {@code LangSetting}; the target-config
 * schema lives in {@code TpahereModule} rather than a separate settings class.</p>
 */
public final class TpahereLang {

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

    /** Deserialize the template at {@code key} after substituting the given name/value pairs. */
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

    public Component blackWorld() {
        return component("global.black_world");
    }

    // --- /tpahere texts ---

    public Component description() {
        return component("commands.tpahere.description");
    }

    public Component message(String target) {
        return render("commands.tpahere.message", "{player}", target);
    }

    public Component requestMessage(String requester) {
        return render("commands.tpahere.request_message", "{player}", requester);
    }

    public Component inDelay(String seconds) {
        return render("commands.tpahere.in_delay", "{delay}", seconds);
    }

    public Component noRequest(String player) {
        return render("commands.tpahere.no_request", "{player}", player);
    }

    public Component noPendingRequest() {
        return component("commands.tpahere.no_pending_request");
    }

    public Component sendSelf() {
        return component("commands.tpahere.send_self");
    }

    public Component openMenu() {
        return component("commands.tpahere.open_menu");
    }

    public Component crossServer() {
        return component("commands.tpahere.cross_server");
    }

    // --- accept ---

    public Component acceptMessage(String requester) {
        return render("commands.tpahere.accept.message", "{player}", requester);
    }

    public Component accepted(String target) {
        return render("commands.tpahere.accept.accepted", "{player}", target);
    }

    public Component acceptDegraded(String target) {
        return render("commands.tpahere.accept.degraded", "{player}", target);
    }

    // --- reject ---

    public Component rejectMessage(String requester) {
        return render("commands.tpahere.reject.message", "{player}", requester);
    }

    public Component rejected(String target) {
        return render("commands.tpahere.reject.rejected", "{player}", target);
    }

    // --- timeout ---

    /** Message shown to the requester when a request expires. */
    public Component delayMessage(String target) {
        return render("commands.tpahere.delay.message", "{player}", target);
    }

    /** Message shown to the target when a request they received expires. */
    public Component delayTimeOut(String requester) {
        return render("commands.tpahere.delay.time_out", "{player}", requester);
    }

    /**
     * Build the "usage error" message: the {@code global.usage_error} template with {@code {usage}}
     * replaced by the tpahere usage pattern and {@code {command}} replaced by the label actually
     * used to invoke the command.
     */
    public Component usageError(String label) {
        String combined = raw("global.usage_error")
                .replace("{usage}", raw("commands.tpahere.usage"))
                .replace("{command}", label);
        return MINI.deserialize(combined);
    }

    /** Plain-text form of the command description, for {@code PluginCommand#setDescription}. */
    public String plainDescription() {
        return PlainTextComponentSerializer.plainText().serialize(description());
    }
}
