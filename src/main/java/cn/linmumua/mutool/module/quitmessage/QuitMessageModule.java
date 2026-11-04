package cn.linmumua.mutool.module.quitmessage;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.condition.ConditionEngine;
import cn.linmumua.mutool.module.MutoolModule;
import cn.linmumua.mutool.placeholder.PlaceholderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import taboolib.common.platform.function.IOKt;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quit-message module — customises the message broadcast when a player leaves the server.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_quitmessage}. It owns the
 * module switch, loads {@code module/quitmessage/config.yml} through TabooLib's
 * {@link Configuration}, and listens for {@link PlayerQuitEvent} at
 * {@link EventPriority#LOWEST} so it can override the message before other plugins read it.</p>
 *
 * <p>Behaviour mirrors the original {@code PlayerQuit} listener:</p>
 * <ul>
 *   <li>{@code remove_message: true} → clear the message ({@code event.quitMessage(null)});</li>
 *   <li>otherwise pick the highest-weight group whose conditions all hold, resolve
 *       placeholders via {@link PlaceholderService#parse(Player, String)}, parse the result
 *       as MiniMessage and set it on the event;</li>
 *   <li>no matching group → {@code event.quitMessage(null)}.</li>
 * </ul>
 */
public final class QuitMessageModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/quitmessage/config.yml";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Mutool plugin;
    private Configuration config;
    private boolean enabled;
    private boolean removeMessage;
    private List<Group> groups = Collections.emptyList();
    private QuitListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public QuitMessageModule() {
    }

    @Override
    public String id() {
        return "quitmessage";
    }

    /** Release the default config, load it, then register the quit listener when enabled. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.applyState();
    }

    /** Reload config from disk and re-apply the enable state / (re)register the listener. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the listener and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterListener();
        this.enabled = false;
    }

    // --- internals ---

    /** Re-read the group table and (un)register the listener according to {@code enable}. */
    private void applyState() {
        this.loadGroups();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterListener();
        if (nowEnabled) {
            this.listener = new QuitListener(this);
            this.plugin.getServer().getPluginManager().registerEvents(this.listener, this.plugin);
        }
        this.enabled = nowEnabled && this.listener != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/QuitMessage] 退服消息模块已关闭 (enable: false)");
        }
    }

    /** Parse {@code remove_message} plus every {@code groups.<key>} entry into an ordered list. */
    private void loadGroups() {
        this.removeMessage = this.config != null && this.config.getBoolean("remove_message", false);

        List<Group> parsed = new ArrayList<>();
        ConfigurationSection groupsSection = this.config == null
                ? null
                : this.config.getConfigurationSection("groups");
        if (groupsSection != null) {
            for (String key : groupsSection.getKeys(false)) {
                ConfigurationSection section = groupsSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                List<Map<?, ?>> conditions = section.getMapList("conditions");
                parsed.add(new Group(
                        section.getInt("weight", 0),
                        section.getString("message", ""),
                        conditions == null ? Collections.<Map<?, ?>>emptyList() : conditions
                ));
            }
        }
        this.groups = parsed;
    }

    /**
     * Core quit handling, mirroring the original {@code PlayerQuit#onPlayerQuit}: resolve the
     * winning group and set (or clear) the quit message on the event.
     */
    void handleQuit(Player player, PlayerQuitEvent event) {
        if (this.removeMessage) {
            event.quitMessage(null);
            return;
        }

        Group selected = null;
        int lastWeight = -1;
        Map<String, String> context = new HashMap<>();
        for (Group group : this.groups) {
            if (!ConditionEngine.testAll(player, group.conditions(), context)) {
                continue;
            }
            int weight = group.weight();
            if (weight <= lastWeight) {
                continue;
            }
            lastWeight = weight;
            selected = group;
        }

        if (selected == null) {
            event.quitMessage(null);
            return;
        }

        String parsed = PlaceholderService.parse(player, selected.message());
        Component component = MINI.deserialize(parsed == null ? "" : parsed);
        event.quitMessage(component);
    }

    private void unregisterListener() {
        if (this.listener != null) {
            HandlerList.unregisterAll(this.listener);
            this.listener = null;
        }
    }

    /** One configured quit-message group: its weight, message template and parsed conditions. */
    private record Group(int weight, String message, List<Map<?, ?>> conditions) {
    }

    /**
     * Bukkit listener delegating {@link PlayerQuitEvent} back to the owning module. Kept as a
     * nested class so {@link cn.linmumua.mutool.module.ModuleManager} (which skips {@code $}
     * inner classes) never treats it as a module.
     */
    private static final class QuitListener implements Listener {

        private final QuitMessageModule module;

        private QuitListener(QuitMessageModule module) {
            this.module = module;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerQuit(PlayerQuitEvent event) {
            this.module.handleQuit(event.getPlayer(), event);
        }
    }
}
