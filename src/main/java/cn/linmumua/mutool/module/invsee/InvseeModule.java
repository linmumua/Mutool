package cn.linmumua.mutool.module.invsee;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;
import taboolib.platform.util.FoliaExecutorKt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Invsee module — lets a staff member inspect another player's armour / inventory / ender chest.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_invsee}. It owns the module
 * switch, loads {@code module/invsee/config.yml}, {@code module/invsee/lang.yml} and
 * {@code module/invsee/menu/armor.yml} through TabooLib's {@link Configuration}, and registers the
 * {@code /invsee} command directly through Bukkit's {@link CommandMap} (no plugin.yml command
 * entries).</p>
 *
 * <h2>Folia safety</h2>
 * <p>Instead of cross-region {@code viewer.openInventory(target.getInventory())} (which Folia
 * rejects and can throw / corrupt state), every view is a <strong>read-only snapshot</strong>:</p>
 * <ol>
 *   <li>On the <em>target's</em> region thread ({@link FoliaExecutorKt#runTask(org.bukkit.entity.Entity, Runnable)})
 *       we clone the relevant {@link ItemStack}s into a detached array / {@link ArmorMenu.ArmorSnapshot}.</li>
 *   <li>Then we build a {@link SnapshotMenu} / {@link ArmorMenu} from that snapshot and open it for the
 *       viewer; {@link cn.linmumua.mutool.menu.Menu#open(Player)} schedules the build+open on the
 *       <em>viewer's</em> region thread. The live target inventory is never touched again.</li>
 * </ol>
 * <p>Both the shared {@link cn.linmumua.mutool.menu.MenuListener} and the absence of any click handler
 * make the view strictly read-only — the viewer can never take, insert or move an item.</p>
 */
public final class InvseeModule implements MutoolModule {

    /** Permission node required to run {@code /invsee}. */
    public static final String PERMISSION = "mutool.commands.invsee";

    private static final String CONFIG_RESOURCE = "module/invsee/config.yml";
    private static final String LANG_RESOURCE = "module/invsee/lang.yml";
    private static final String ARMOR_RESOURCE = "module/invsee/menu/armor.yml";

    private static final int INVENTORY_SIZE = 36;   // 4 rows: main inventory (3) + hotbar (1)
    private static final int ENDERCHEST_SIZE = 27;  // 3 rows

    private Mutool plugin;
    private final InvseeLang lang = new InvseeLang();

    private Configuration config;
    private Configuration armorConfig;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public InvseeModule() {
    }

    @Override
    public String id() {
        return "invsee";
    }

    /** Release default resources, load config / lang / armour menu, then register the command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);
        File armorFile = IOKt.releaseResourceFile(ARMOR_RESOURCE, false, ARMOR_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);
        this.armorConfig = Configuration.Companion.loadFromFile(armorFile, Type.YAML, false);

        this.applyState();
    }

    /** Reload config / lang / armour menu from disk and re-apply the enable state / (re)register the command. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        if (this.armorConfig != null) {
            this.armorConfig.reload();
        }
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister the command and drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommand();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        List<String> aliases = this.resolveAliases();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommand();
        if (nowEnabled) {
            this.registerCommand(aliases);
        }
        this.enabled = nowEnabled && this.command != null;

        if (!nowEnabled) {
            IOKt.info("[Mutool/Invsee] 背包查看模块已关闭 (enable: false)");
        }
    }

    private List<String> resolveAliases() {
        List<String> commands = this.config == null ? null : this.config.getStringList("commands");
        List<String> aliases = new ArrayList<>();
        if (commands != null) {
            for (String value : commands) {
                if (value != null && !value.isBlank()) {
                    aliases.add(value.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (aliases.isEmpty()) {
            aliases.add("invsee");
        }
        return aliases;
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void registerCommand(List<String> aliases) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(aliases.get(0), this.plugin);
            if (aliases.size() > 1) {
                cmd.setAliases(aliases.subList(1, aliases.size()));
            }
            cmd.setPermission(PERMISSION);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plainDescription());

            InvseeExecutor executor = new InvseeExecutor(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Invsee] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Invsee] 注册 /invsee 命令失败: " + throwable);
        }
    }

    private void unregisterCommand() {
        if (this.command == null) {
            return;
        }
        try {
            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            this.command.unregister(commandMap);
        } catch (Throwable ignored) {
        }
        this.command = null;
    }

    // --- snapshot capture / open (called from the executor on the viewer thread) ---

    /**
     * Capture the target's storage inventory on the target's region thread, then open a read-only
     * snapshot for the viewer. The main inventory (slots 9–35) is shown in the top three rows and the
     * hotbar (slots 0–8) in the bottom row, which reads more naturally than a raw index dump.
     */
    private void openInventory(Player viewer, Player target) {
        FoliaExecutorKt.runTask(target, () -> {
            ItemStack[] snapshot = snapshotStorage(target.getInventory().getContents());
            SnapshotMenu menu = new SnapshotMenu(this.lang.inventoryTitle(target.getName()), INVENTORY_SIZE, snapshot);
            menu.open(viewer);
        });
    }

    /** Capture the target's ender chest on its region thread, then open a read-only snapshot. */
    private void openEnderchest(Player viewer, Player target) {
        FoliaExecutorKt.runTask(target, () -> {
            ItemStack[] snapshot = snapshotContents(target.getEnderChest().getContents(), ENDERCHEST_SIZE);
            SnapshotMenu menu = new SnapshotMenu(this.lang.enderchestTitle(target.getName()), ENDERCHEST_SIZE, snapshot);
            menu.open(viewer);
        });
    }

    /** Capture the target's equipment on its region thread, then open the read-only armour layout. */
    private void openArmor(Player viewer, Player target) {
        FoliaExecutorKt.runTask(target, () -> {
            ArmorMenu menu = ArmorMenu.capture(this.armorConfig, target.getInventory(), target.getName());
            menu.open(viewer);
        });
    }

    /** Deep-copy the first {@code size} slots of {@code source}. Must run on the owning region thread. */
    private static ItemStack[] snapshotContents(ItemStack[] source, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int index = 0; index < size && index < source.length; index++) {
            out[index] = cloneOrNull(source[index]);
        }
        return out;
    }

    /**
     * Build a 36-slot snapshot where the last row is the hotbar. Reads {@code getContents()} layout
     * ({@code 0–8} hotbar, {@code 9–35} main inventory) and reorders it so main storage fills the top
     * three rows and the hotbar fills the bottom row.
     */
    private static ItemStack[] snapshotStorage(ItemStack[] source) {
        ItemStack[] out = new ItemStack[INVENTORY_SIZE];
        for (int index = 9; index <= 35 && index < source.length; index++) {
            out[index - 9] = cloneOrNull(source[index]);
        }
        for (int index = 0; index <= 8 && index < source.length; index++) {
            out[27 + index] = cloneOrNull(source[index]);
        }
        return out;
    }

    private static ItemStack cloneOrNull(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }

    /**
     * Validate the requested type, open the matching read-only snapshot and send the confirmation.
     * Invoked from the executor after the sender / target / args have been validated.
     */
    void dispatch(Player viewer, Player target, String type) {
        switch (type) {
            case "inventory" -> openInventory(viewer, target);
            case "enderchest" -> openEnderchest(viewer, target);
            case "armor" -> openArmor(viewer, target);
            default -> {
                viewer.sendMessage(this.lang.noType(type));
                return;
            }
        }
        viewer.sendMessage(this.lang.opened(target.getName(), type));
    }

    /** Executor for {@code /invsee}. */
    private static final class InvseeExecutor implements TabExecutor {

        private static final List<String> TYPES = List.of("armor", "inventory", "enderchest");

        private final InvseeModule module;

        private InvseeExecutor(InvseeModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            InvseeLang lang = this.module.lang;
            if (!(sender instanceof Player player)) {
                sender.sendMessage(lang.onlyPlayer());
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(lang.usageError(label));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                player.sendMessage(lang.playerOffline());
                return true;
            }

            String type = args[1].toLowerCase(Locale.ROOT);
            this.module.dispatch(player, target, type);
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(online.getName());
                    }
                }
                return names;
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> matches = new ArrayList<>();
                for (String type : TYPES) {
                    if (type.startsWith(prefix)) {
                        matches.add(type);
                    }
                }
                return matches;
            }
            return new ArrayList<>();
        }
    }
}
