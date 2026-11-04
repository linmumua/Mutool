package cn.linmumua.mutool.module.back;

import cn.linmumua.mutool.database.entity.BackData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;

import java.util.ArrayList;
import java.util.List;

/**
 * Executor for {@code /back}.
 *
 * <pre>
 *   /back               → return to the most recent record (any type)
 *   /back menu          → open the location-record menu
 *   /back teleport      → return to the most recent pre-teleport position
 *   /back death         → return to the most recent death position
 * </pre>
 *
 * <p>When no record exists a themed "no location" message is shown; any unexpected failure
 * surfaces as the generic database error so the console keeps the stack trace.</p>
 */
final class BackCommand implements TabExecutor {

    private final BackModule module;

    BackCommand(@NotNull BackModule module) {
        this.module = module;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.module.lang().onlyPlayer());
            return true;
        }
        try {
            if (args.length == 0) {
                this.teleportLatest(player, this.module.records(player.getUniqueId()));
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "menu" -> this.module.openMenu(player);
                case BackModule.TYPE_TELEPORT -> this.teleportLatest(
                        player, this.module.records(player.getUniqueId(), BackModule.TYPE_TELEPORT));
                case BackModule.TYPE_DEATH -> this.teleportLatest(
                        player, this.module.records(player.getUniqueId(), BackModule.TYPE_DEATH));
                default -> player.sendMessage(this.module.lang().usageError(label));
            }
        } catch (Throwable throwable) {
            player.sendMessage(this.module.lang().databaseError());
            IOKt.severe("[Mutool/Back] 执行 /" + label + " 失败: " + throwable);
        }
        return true;
    }

    private void teleportLatest(@NotNull Player player, @NotNull List<BackData> records) {
        if (records.isEmpty()) {
            player.sendMessage(this.module.lang().noLocation());
            return;
        }
        this.module.teleportTo(player, records.get(0));
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            return List.of("menu", BackModule.TYPE_TELEPORT, BackModule.TYPE_DEATH);
        }
        return new ArrayList<>();
    }
}
