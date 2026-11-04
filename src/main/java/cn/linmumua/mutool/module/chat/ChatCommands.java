package cn.linmumua.mutool.module.chat;

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

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers Java-side {@code /msg} and {@code /reply} commands through Bukkit's {@link CommandMap}
 * (no plugin.yml entries). Command names / aliases come from {@code config.yml} so operators can
 * re-map them freely.
 */
final class ChatCommands {

    static final String PERM_MSG = "mutool.commands.msg";
    static final String PERM_REPLY = "mutool.commands.reply";

    private final ChatModule module;
    private final List<PluginCommand> registered = new ArrayList<>();

    ChatCommands(ChatModule module) {
        this.module = module;
    }

    void registerAll() {
        ChatConfig config = this.module.config();
        if (config == null || !config.msgEnabled()) {
            return;
        }
        register(config.msgCommands(), PERM_MSG, "commands.msg.usage", "commands.msg.description",
                new MsgExecutor(this.module));
        register(config.replyCommands(), PERM_REPLY, "commands.reply.usage", "commands.reply.description",
                new ReplyExecutor(this.module));
    }

    // permissionMessage(Component) is deprecated in the current Paper API but still the only way
    // to render a themed MiniMessage "no permission" message per command.
    @SuppressWarnings("deprecation")
    private void register(List<String> names, String permission, String usageKey, String descKey, TabExecutor executor) {
        if (names == null || names.isEmpty()) {
            return;
        }
        String primary = names.get(0);
        try {
            Constructor<PluginCommand> constructor =
                    PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true);

            PluginCommand cmd = constructor.newInstance(primary, this.module.plugin());
            if (names.size() > 1) {
                cmd.setAliases(names.subList(1, names.size()));
            }
            cmd.setPermission(permission);
            cmd.permissionMessage(this.module.lang().noPermission());
            cmd.setDescription(this.module.lang().plainDescription(descKey));
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);

            CommandMap commandMap = this.module.plugin().getServer().getCommandMap();
            commandMap.register(this.module.plugin().getName().toLowerCase(Locale.ROOT), cmd);
            this.registered.add(cmd);

            IOKt.info("[Mutool/Chat] 已注册命令: /" + primary + " (权限: " + permission + ")");
        } catch (Throwable throwable) {
            IOKt.severe("[Mutool/Chat] 注册命令 /" + primary + " 失败: " + throwable);
        }
    }

    void unregisterAll() {
        CommandMap commandMap = this.module.plugin().getServer().getCommandMap();
        for (PluginCommand cmd : this.registered) {
            try {
                cmd.unregister(commandMap);
            } catch (Throwable ignored) {
            }
        }
        this.registered.clear();
    }

    private static String join(String[] args, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private static List<String> onlineNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    /** {@code /msg <player> <message>}. */
    private static final class MsgExecutor implements TabExecutor {

        private final ChatModule module;

        private MsgExecutor(ChatModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            ChatLang lang = this.module.lang();
            if (args.length < 2) {
                sender.sendMessage(lang.usageError(label, "commands.msg.usage"));
                return true;
            }
            this.module.privateService().sendMsg(sender, args[0], join(args, 1));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            if (args.length == 1) {
                return onlineNames(args[0]);
            }
            return new ArrayList<>();
        }
    }

    /** {@code /reply <message>}. */
    private static final class ReplyExecutor implements TabExecutor {

        private final ChatModule module;

        private ReplyExecutor(ChatModule module) {
            this.module = module;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                 @NotNull String label, String @NotNull [] args) {
            ChatLang lang = this.module.lang();
            if (args.length < 1) {
                sender.sendMessage(lang.usageError(label, "commands.reply.usage"));
                return true;
            }
            String target = this.module.privateService().replyTarget(sender.getName());
            if (target == null) {
                sender.sendMessage(lang.replyNoTarget());
                return true;
            }
            this.module.privateService().sendMsg(sender, target, join(args, 0));
            return true;
        }

        @Override
        public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                   @NotNull String label, String @NotNull [] args) {
            return new ArrayList<>();
        }
    }
}
