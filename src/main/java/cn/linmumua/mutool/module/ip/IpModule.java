package cn.linmumua.mutool.module.ip;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.module.MutoolModule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ip module — {@code /ip <player>} shows a player's IP address and its geographic
 * location.
 *
 * <p>This is the TabooLib port of {@code MumuTools} {@code module_ip}. It owns the
 * module switch, loads {@code module/ip/config.yml} &amp; {@code module/ip/lang.yml}
 * through TabooLib's {@link Configuration}, and registers the {@code /ip} command
 * directly through Bukkit's {@link CommandMap} (no plugin.yml command entries).</p>
 *
 * <p>The original module queried a public IP-info endpoint via fastjson2. Mutool has
 * no fastjson2 dependency, so the same Baidu OpenData endpoint is parsed here with
 * Google Gson (bundled with Paper). See {@link #resolveLocation(String)}.</p>
 */
public final class IpModule implements MutoolModule {

    /** Permission node required to run {@code /ip}. */
    public static final String PERMISSION = "mutool.commands.ip";

    private static final String CONFIG_RESOURCE = "module/ip/config.yml";
    private static final String LANG_RESOURCE = "module/ip/lang.yml";

    /** Baidu IP-info endpoint (ipv4). Same endpoint the original module used. */
    private static final String LOOKUP_ENDPOINT =
            "https://opendata.baidu.com/api.php?query=%s&co=&resource_id=6006&ie=utf8&oe=utf-8&format=json";

    /** Network timeouts so a slow/unreachable endpoint never hangs the server thread. */
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 2500;

    private Mutool plugin;
    private final IpLang lang = new IpLang();

    private Configuration config;
    private boolean enabled;
    private PluginCommand command;

    /** Public no-arg constructor — required so {@link cn.linmumua.mutool.module.ModuleManager} can instantiate it. */
    public IpModule() {
    }

    @Override
    public String id() {
        return "ip";
    }

    /** Release default resources, load config/lang, then register the command. */
    @Override
    public void load(Mutool plugin) {
        this.plugin = plugin;

        File configFile = IOKt.releaseResourceFile(CONFIG_RESOURCE, false, CONFIG_RESOURCE);
        File langFile = IOKt.releaseResourceFile(LANG_RESOURCE, false, LANG_RESOURCE);

        this.config = Configuration.Companion.loadFromFile(configFile, Type.YAML, false);
        this.lang.load(langFile);

        this.applyState();
    }

    /** Reload config + lang from disk and re-apply the enable state / (re)register the command. */
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
            IOKt.info("[Mutool/Ip] IP 模块已关闭 (enable: false)");
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
            aliases.add("ip");
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

            IpExecutor executor = new IpExecutor(this.lang);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.plugin.getServer().getCommandMap();
            commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), cmd);
            this.command = cmd;

            IOKt.info("[Mutool/Ip] 已注册命令: /" + aliases.get(0) + " (权限: " + PERMISSION + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Ip] 注册 /ip 命令失败: " + throwable);
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

    /**
     * Resolve the geographic location for an IP address.
     *
     * <p>Same Baidu OpenData endpoint the original fastjson2-based module called, but
     * parsed here with Google Gson (bundled with Paper). Any failure (network error,
     * empty data, malformed JSON) degrades gracefully to {@code "-"} so the command
     * still shows the raw IP.</p>
     *
     * @param ip the queried IP
     * @return the location string, or {@code "-"} when unknown / lookup failed
     */
    private static String resolveLocation(String ip) {
        if (ip == null || ip.isBlank()) {
            return "-";
        }
        if (ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return "回环地址";
        }

        URLConnection connection = null;
        try {
            URI uri = URI.create(String.format(LOOKUP_ENDPOINT, ip));
            connection = uri.toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Mutool)");

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            JsonElement root = JsonParser.parseString(body.toString());
            if (!root.isJsonObject()) {
                return "-";
            }
            JsonObject json = root.getAsJsonObject();
            JsonArray data = json.getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return "-";
            }
            JsonObject first = data.get(0).getAsJsonObject();
            JsonElement location = first.get("location");
            if (location == null || location.isJsonNull()) {
                return "-";
            }
            String value = location.getAsString();
            return value == null || value.isBlank() ? "-" : value;
        } catch (Throwable throwable) {
            return "-";
        } finally {
            if (connection instanceof java.net.HttpURLConnection http) {
                http.disconnect();
            }
        }
    }

    /** Executor for {@code /ip <player>}; prints the player's IP and location panel. */
    private static final class IpExecutor implements TabExecutor {

        private final IpLang lang;

        private IpExecutor(IpLang lang) {
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            if (args.length != 1) {
                sender.sendMessage(this.lang.usageError(label));
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(this.lang.playerOffline());
                return true;
            }

            String ip = target.getAddress() == null ? "-" : target.getAddress().getHostString();
            String location = resolveLocation(ip);
            sender.sendMessage(this.lang.message(target.getName(), ip, location));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            List<String> names = new ArrayList<>();
            if (args.length == 1) {
                String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    String name = online.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        names.add(name);
                    }
                }
            }
            return names;
        }
    }
}
