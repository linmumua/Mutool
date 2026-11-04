package cn.linmumua.mutool.module.economy;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.Dao;
import cn.linmumua.mutool.database.entity.EconomyData;
import cn.linmumua.mutool.module.MutoolModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Economy module — player balances backed by the {@code mumutools_economy} table.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_economy}. It owns the
 * module switch, loads {@code module/economy/config.yml} &amp; {@code module/economy/lang.yml}
 * through TabooLib's {@link Configuration}, registers the three commands
 * ({@code /money}, {@code /pay}, {@code /moneyadmin}) directly through Bukkit's
 * {@link CommandMap} (no plugin.yml command entries), each guarded by its own
 * permission node, and — when the Vault plugin is present — exposes the balances to
 * other plugins through a Vault {@code Economy} service provider.</p>
 *
 * <p><b>Money model.</b> Each row stores a {@link BigDecimal} balance. A missing row is
 * treated as a zero balance; the first write ({@code set}/{@code add}/{@code take} or a
 * received transfer) creates it. Balances are always kept non-negative.</p>
 */
public final class EconomyModule implements MutoolModule {

    /** Permission node for {@code /money}. */
    public static final String PERM_MONEY = "mutool.commands.money";
    /** Permission node for {@code /pay}. */
    public static final String PERM_PAY = "mutool.commands.pay";
    /** Permission node for {@code /moneyadmin}. */
    public static final String PERM_MONEYADMIN = "mutool.commands.moneyadmin";

    private static final String CONFIG_RESOURCE = "module/economy/config.yml";
    private static final String LANG_RESOURCE = "module/economy/lang.yml";

    private static final String DEFAULT_MONEY = "money";
    private static final String DEFAULT_PAY = "pay";
    private static final String DEFAULT_MONEYADMIN = "moneyadmin";

    private static final String DEFAULT_CURRENCY = "金币";

    private Mutool plugin;
    private final EconomyLang lang = new EconomyLang();
    private EconomyVaultHook vaultHook;

    private Configuration config;
    private boolean enabled;
    private String currency = DEFAULT_CURRENCY;
    private final List<PluginCommand> commands = new ArrayList<>();
    private final List<String> selfAddedPermissions = new ArrayList<>();

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public EconomyModule() {
    }

    @Override
    public String id() {
        return "economy";
    }

    /** Release default resources, load config/lang, then register the commands (and the Vault hook). */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register commands & hook. */
    @Override
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
        this.lang.reload();
        this.applyState();
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    /** Unregister every command and the Vault hook, then drop state. Called on plugin disable. */
    @Override
    public void unload() {
        this.unregisterCommands();
        this.unregisterVault();
        this.unregisterPermissions();
        this.enabled = false;
    }

    // --- internals ---

    private void applyState() {
        this.currency = this.resolveCurrency();
        boolean nowEnabled = this.config != null && this.config.getBoolean("enable", true);

        this.unregisterCommands();
        this.unregisterVault();
        this.unregisterPermissions();
        if (nowEnabled) {
            this.registerPermissions();
            this.registerCommands();
            this.registerVault();
        }
        this.enabled = nowEnabled && !this.commands.isEmpty();

        if (!nowEnabled) {
            IOKt.info("[Mutool/Economy] 经济模块已关闭 (enable: false)");
        }
    }

    private String resolveCurrency() {
        if (this.config == null) {
            return DEFAULT_CURRENCY;
        }
        String value = this.config.getString("currency", DEFAULT_CURRENCY);
        return value == null || value.isBlank() ? DEFAULT_CURRENCY : value;
    }

    /**
     * Resolve the three command labels from the config {@code commands} list. The list is
     * positional: {@code [money, pay, moneyadmin]}. Any missing / blank entry falls back to
     * the bundled default so the module always registers something usable.
     */
    private List<String> resolveNames() {
        List<String> configured = this.config == null ? null : this.config.getStringList("commands");
        List<String> defaults = List.of(DEFAULT_MONEY, DEFAULT_PAY, DEFAULT_MONEYADMIN);
        List<String> names = new ArrayList<>();
        for (int index = 0; index < defaults.size(); index++) {
            String value = configured != null && configured.size() > index ? configured.get(index) : null;
            if (value == null || value.isBlank()) {
                value = defaults.get(index);
            }
            names.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private void registerCommands() {
        List<String> names = this.resolveNames();
        this.register(names.get(0), PERM_MONEY, Sub.MONEY);
        this.register(names.get(1), PERM_PAY, Sub.PAY);
        this.register(names.get(2), PERM_MONEYADMIN, Sub.MONEYADMIN);
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the
    // only way to render a themed MiniMessage permission denied message per command.
    @SuppressWarnings("deprecation")
    private void register(String name, String permission, Sub sub) {
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(name, this.plugin);
            cmd.setPermission(permission);
            cmd.permissionMessage(this.lang.noPermission());
            cmd.setDescription(this.lang.plain(sub.descriptionKey));

            EconomyExecutor executor = new EconomyExecutor(this, sub);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.commands.add(cmd);

            IOKt.info("[Mutool/Economy] 已注册命令: /" + name + " (权限: " + permission + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Economy] 注册命令 /" + name + " 失败: " + throwable);
        }
    }

    private void unregisterCommands() {
        if (this.commands.isEmpty()) {
            return;
        }
        CommandMap commandMap = this.plugin.getServer().getCommandMap();
        for (PluginCommand command : this.commands) {
            try {
                command.unregister(commandMap);
            } catch (Throwable ignored) {
            }
        }
        this.commands.clear();
    }

    private void registerVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        this.vaultHook = new EconomyVaultHook(this);
        if (!this.vaultHook.register(this.plugin)) {
            this.vaultHook = null;
        }
    }

    private void unregisterVault() {
        if (this.vaultHook == null) {
            return;
        }
        this.vaultHook.unregister();
        this.vaultHook = null;
    }

    // --- permission defaults ---

    /**
     * Register the module's permission nodes when they are not already declared elsewhere
     * (e.g. {@code paper-plugin.yml}). {@code paper-plugin.yml} is out of scope for this
     * port, so without this the undeclared nodes would default to OP-only and {@code /money}
     * / {@code /pay} would be unusable by normal players. {@code /moneyadmin} stays OP-only.
     */
    @SuppressWarnings("deprecation")
    private void registerPermissions() {
        this.addPermissionIfAbsent(PERM_MONEY, "允许使用 /money 查询余额", PermissionDefault.TRUE);
        this.addPermissionIfAbsent(PERM_PAY, "允许使用 /pay 转账", PermissionDefault.TRUE);
        this.addPermissionIfAbsent(PERM_MONEYADMIN, "允许使用 /moneyadmin 管理经济", PermissionDefault.OP);
    }

    @SuppressWarnings("deprecation")
    private void addPermissionIfAbsent(String node, String description, PermissionDefault fallback) {
        try {
            PluginManager manager = this.plugin.getServer().getPluginManager();
            if (manager.getPermission(node) != null) {
                return; // already declared (paper-plugin.yml or another plugin) — leave it alone
            }
            manager.addPermission(new Permission(node, description, fallback));
            this.selfAddedPermissions.add(node);
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Economy] 注册权限节点 " + node + " 失败: " + throwable);
        }
    }

    @SuppressWarnings("deprecation")
    private void unregisterPermissions() {
        if (this.selfAddedPermissions.isEmpty()) {
            return;
        }
        PluginManager manager = this.plugin.getServer().getPluginManager();
        for (String node : this.selfAddedPermissions) {
            try {
                manager.removePermission(node);
            } catch (Throwable ignored) {
            }
        }
        this.selfAddedPermissions.clear();
    }

    // --- data access ---

    /** Typed DAO for the economy table. Throws if the data layer is unavailable. */
    private Dao<EconomyData, UUID> dao() {
        if (this.plugin.getDatabaseManager() == null) {
            throw new IllegalStateException("数据库未连接");
        }
        return this.plugin.getDatabaseManager().dao(EconomyData.class);
    }

    String currencyName() {
        return this.currency;
    }

    /** Whether the given player already owns an economy row. */
    boolean hasAccount(UUID player) {
        return this.dao().getById(player) != null;
    }

    /** Current balance of the player (zero when no row exists). */
    BigDecimal balanceOf(UUID player) {
        EconomyData data = this.dao().getById(player);
        if (data == null || data.getMoney() == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return data.getMoney().setScale(2, RoundingMode.HALF_UP);
    }

    /** Overwrite the player's balance, creating the row if needed. */
    void setBalance(UUID player, BigDecimal amount) {
        EconomyData data = this.dao().getById(player);
        if (data == null) {
            data = new EconomyData(player, normalise(amount));
        } else {
            data.setMoney(normalise(amount));
        }
        this.dao().createOrUpdate(data);
    }

    /** Add to the player's balance, creating the row if needed. */
    void addBalance(UUID player, BigDecimal amount) {
        this.setBalance(player, this.balanceOf(player).add(amount));
    }

    /**
     * Subtract from the player's balance. When {@code clampZero} is {@code true} the result
     * never drops below zero. Returns the new balance.
     */
    BigDecimal takeBalance(UUID player, BigDecimal amount, boolean clampZero) {
        BigDecimal next = this.balanceOf(player).subtract(amount);
        if (clampZero && next.signum() < 0) {
            next = BigDecimal.ZERO;
        }
        this.setBalance(player, next);
        return this.balanceOf(player);
    }

    /** Create a zero-balance account if none exists. Returns {@code true} when created. */
    boolean createAccount(UUID player) {
        if (this.hasAccount(player)) {
            return false;
        }
        this.setBalance(player, BigDecimal.ZERO);
        return true;
    }

    private static BigDecimal normalise(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    void logWarn(String message) {
        IOKt.severe("[Mutool/Economy] " + message);
    }

    // --- player resolution helpers ---

    /** Resolve a player name to a UUID (online first, then offline lookup); {@code null} when unknown. */
    private static UUID resolveUuid(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline == null ? null : offline.getUniqueId();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** Best-effort human-readable name for a UUID, falling back to the supplied raw name. */
    private static String displayName(UUID id, String fallback) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            if (offline != null && offline.getName() != null) {
                return offline.getName();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // --- sub-command handlers (invoked from the executor) ---

    void handleMoney(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(this.lang.usageError(label, Sub.MONEY.usageKey));
                return;
            }
            this.sendBalance(sender, player.getUniqueId(), player.getName());
            return;
        }
        if (args.length == 1) {
            UUID targetId = resolveUuid(args[0]);
            if (targetId == null) {
                sender.sendMessage(this.lang.playerNotFound());
                return;
            }
            this.sendBalance(sender, targetId, args[0]);
            return;
        }
        sender.sendMessage(this.lang.usageError(label, Sub.MONEY.usageKey));
    }

    private void sendBalance(CommandSender sender, UUID id, String fallbackName) {
        sender.sendMessage(this.lang.render("commands.money.message",
                "{player}", displayName(id, fallbackName),
                "{amount}", this.balanceOf(id).toPlainString(),
                "{money_name}", this.currency));
    }

    void handlePay(Player sender, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(this.lang.usageError(label, Sub.PAY.usageKey));
            return;
        }
        if (args[0].equalsIgnoreCase(sender.getName())) {
            sender.sendMessage(this.lang.component("commands.pay.pay_self"));
            return;
        }

        BigDecimal amount = parseAmount(args[1]);
        if (amount == null || amount.signum() <= 0) {
            sender.sendMessage(this.lang.component("commands.pay.money_format_error"));
            return;
        }

        UUID sourceId = sender.getUniqueId();
        BigDecimal sourceBalance = this.balanceOf(sourceId);
        if (sourceBalance.compareTo(amount) < 0) {
            sender.sendMessage(this.lang.component("commands.pay.no_money"));
            return;
        }

        UUID targetId = resolveUuid(args[0]);
        if (targetId == null || targetId.equals(sourceId)) {
            sender.sendMessage(this.lang.playerNotFound());
            return;
        }

        this.setBalance(sourceId, sourceBalance.subtract(amount));
        this.addBalance(targetId, amount);

        String targetDisplay = displayName(targetId, args[0]);
        sender.sendMessage(this.lang.render("commands.pay.message",
                "{player}", targetDisplay,
                "{amount}", amount.toPlainString(),
                "{money_name}", this.currency));

        Player targetOnline = Bukkit.getPlayer(targetId);
        if (targetOnline != null) {
            targetOnline.sendMessage(this.lang.render("commands.pay.received_message",
                    "{player}", sender.getName(),
                    "{amount}", amount.toPlainString(),
                    "{money_name}", this.currency));
        }
    }

    void handleMoneyAdmin(CommandSender sender, String label, String[] args) {
        List<String> subCommands = List.of("set", "add", "take");
        if (args.length != 3 || !subCommands.contains(args[0].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(this.helpMessage(label));
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);

        BigDecimal change = parseAmount(args[2]);
        if (change == null || change.signum() < 0 || (!action.equals("set") && change.signum() == 0)) {
            sender.sendMessage(this.lang.component("commands.moneyadmin.money_format_error"));
            return;
        }

        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sender.sendMessage(this.lang.playerNotFound());
            return;
        }

        switch (action) {
            case "set" -> this.setBalance(targetId, change);
            case "add" -> this.addBalance(targetId, change);
            case "take" -> this.takeBalance(targetId, change, true);
            default -> {
                return;
            }
        }

        BigDecimal after = this.balanceOf(targetId);
        String display = displayName(targetId, args[1]);
        String key = "commands.moneyadmin.sub_commands." + action + ".message";
        sender.sendMessage(this.lang.render(key,
                "{player}", display,
                "{change}", change.toPlainString(),
                "{amount}", after.toPlainString(),
                "{money_name}", this.currency));
    }

    /** Build the moneyadmin help message, substituting the per-subcommand info list. */
    private Component helpMessage(String label) {
        String infoFormat = this.lang.raw("command_info_format");
        String helpList = String.join("\n",
                this.helpLine(infoFormat, label, "help"),
                this.helpLine(infoFormat, label, "set"),
                this.helpLine(infoFormat, label, "add"),
                this.helpLine(infoFormat, label, "take"));
        return this.lang.render("commands.moneyadmin.sub_commands.help.message",
                "{help_list}", helpList,
                "{command}", label);
    }

    private String helpLine(String infoFormat, String label, String sub) {
        String usage = this.lang.raw("commands.moneyadmin.sub_commands." + sub + ".usage")
                .replace("{command}", label);
        String description = this.lang.raw("commands.moneyadmin.sub_commands." + sub + ".description");
        return infoFormat.replace("{usage}", usage).replace("{description}", description);
    }

    /** Which sub-command a given {@link PluginCommand} represents. */
    enum Sub {
        MONEY("commands.money.usage", "commands.money.description"),
        PAY("commands.pay.usage", "commands.pay.description"),
        MONEYADMIN("commands.moneyadmin.usage", "commands.moneyadmin.description");

        private final String usageKey;
        private final String descriptionKey;

        Sub(String usageKey, String descriptionKey) {
            this.usageKey = usageKey;
            this.descriptionKey = descriptionKey;
        }
    }

    /** Executor shared by all three commands; dispatches on the bound {@link Sub}. */
    private static final class EconomyExecutor implements TabExecutor {

        private final EconomyModule module;
        private final Sub sub;

        private EconomyExecutor(EconomyModule module, Sub sub) {
            this.module = module;
            this.sub = sub;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            try {
                switch (this.sub) {
                    case MONEY -> this.module.handleMoney(sender, label, args);
                    case PAY -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage(this.module.lang.onlyPlayer());
                            return true;
                        }
                        this.module.handlePay(player, label, args);
                    }
                    case MONEYADMIN -> this.module.handleMoneyAdmin(sender, label, args);
                }
            } catch (Throwable throwable) {
                sender.sendMessage(this.module.lang.databaseError());
                IOKt.severe("[Mutool/Economy] 执行 /" + label + " 失败: " + throwable);
            }
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (this.sub == Sub.PAY && args.length == 1) {
                return onlineNames();
            }
            if (this.sub == Sub.MONEY && args.length == 1) {
                return onlineNames();
            }
            if (this.sub == Sub.MONEYADMIN) {
                if (args.length == 1) {
                    return List.of("set", "add", "take");
                }
                if (args.length == 2) {
                    return onlineNames();
                }
            }
            return new ArrayList<>();
        }

        private static List<String> onlineNames() {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
    }
}
