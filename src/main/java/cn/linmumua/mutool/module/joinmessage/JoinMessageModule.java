package cn.linmumua.mutool.module.joinmessage;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.condition.ConditionEngine;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import taboolib.common.platform.function.IOKt;
import taboolib.library.configuration.ConfigurationSection;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JoinMessage module — customises the message broadcast when a player joins.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_joinmessage}. It owns
 * the module switch, loads {@code module/joinmessage/config.yml} through TabooLib's
 * {@link Configuration}, and — while enabled — registers a single
 * {@link org.bukkit.event.player.PlayerJoinEvent} listener (see
 * {@link JoinMessageListener}).</p>
 *
 * <p>Behaviour mirrors the original {@code PlayerJoin} listener:</p>
 * <ol>
 *   <li>{@code remove_message: true} → suppress the join message entirely ({@code joinMessage(null)}).</li>
 *   <li>Otherwise pick the <em>highest-weight</em> group whose {@code conditions} all pass
 *       (via {@link ConditionEngine#testAll}); ties keep the first group in config order.</li>
 *   <li>Render that group's {@code message} (placeholders resolved, then MiniMessage) as the
 *       join message. No matching group → {@code joinMessage(null)}.</li>
 * </ol>
 */
public final class JoinMessageModule implements MutoolModule {

    private static final String CONFIG_RESOURCE = "module/joinmessage/config.yml";

    private Mutool plugin;
    private Configuration config;

    private boolean enabled;
    private boolean removeMessage;
    private List<Group> groups = List.of();
    private JoinMessageListener listener;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public JoinMessageModule() {
    }

    @Override
    public String id() {
        return "joinmessage";
    }

    /** Release the default resource, load config, then (re)apply the enable state + listener. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);

        this.listener = new JoinMessageListener(this);
        this.applyState();
    }

    /** Reload config from disk and re-apply the enable state / listener registration. */
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
        this.removeMessage = false;
        this.groups = List.of();
    }

    // --- internals ---

    private void applyState() {
        this.removeMessage = this.config != null && this.config.getBoolean("remove_message", false);
        this.groups = this.parseGroups();

        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        // Always drop the old registration first so a reload never double-registers.
        this.unregisterListener();
        if (nowEnabled) {
            this.registerListener();
        }
        this.enabled = nowEnabled;

        if (!nowEnabled) {
            IOKt.info("[Mutool/JoinMessage] 进服消息模块已关闭 (enable: false)");
        }
    }

    /**
     * Parse the {@code groups} section into an ordered list of {@link Group}s.
     * Each group carries its raw condition nodes ({@code List<Map<?,?>>}) so they can be
     * fed straight into {@link ConditionEngine#testAll} per join.
     */
    private List<Group> parseGroups() {
        List<Group> result = new ArrayList<>();
        if (this.config == null) {
            return result;
        }
        ConfigurationSection groupsSection = this.config.getConfigurationSection("groups");
        if (groupsSection == null) {
            return result;
        }
        for (String key : groupsSection.getKeys(false)) {
            ConfigurationSection section = groupsSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            int weight = section.getInt("weight", 0);
            String message = section.getString("message", "");
            List<Map<?, ?>> conditions = section.getMapList("conditions");
            result.add(new Group(
                    key,
                    weight,
                    message == null ? "" : message,
                    conditions == null ? List.of() : new ArrayList<>(conditions)
            ));
        }
        return result;
    }

    private void registerListener() {
        if (this.listener == null) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(this.listener, this.plugin);
        IOKt.info("[Mutool/JoinMessage] 已注册进服消息监听器");
    }

    private void unregisterListener() {
        if (this.listener == null) {
            return;
        }
        HandlerList.unregisterAll(this.listener);
    }

    // --- state accessors used by the listener ---

    boolean removeMessage() {
        return this.removeMessage;
    }

    List<Group> groups() {
        return this.groups;
    }

    /**
     * Select the group with the greatest {@code weight} whose conditions all pass for the
     * player. Mirrors the original loop: start at {@code bestWeight = -1} and only accept a
     * group whose weight is strictly greater, so ties resolve to the first in config order.
     *
     * @return the winning group, or {@code null} when no group matches
     */
    Group selectGroup(Player player) {
        Group best = null;
        int bestWeight = -1;
        for (Group group : this.groups) {
            if (!ConditionEngine.testAll(player, group.conditions(), new HashMap<>())) {
                continue;
            }
            if (group.weight() <= bestWeight) {
                continue;
            }
            bestWeight = group.weight();
            best = group;
        }
        return best;
    }

    /**
     * A join-message group.
     *
     * @param key        the config key (group name), kept for logging/debug
     * @param weight     selection weight; the highest matching weight wins
     * @param message    the raw MiniMessage template (may still contain {@code {player}} / PAPI placeholders)
     * @param conditions raw condition nodes passed to {@link ConditionEngine#testAll}
     */
    public record Group(String key, int weight, String message, List<?> conditions) {
    }
}
