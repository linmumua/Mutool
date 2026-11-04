package cn.linmumua.mutool.module.tpa;

import cn.linmumua.mutool.menu.Menu;
import cn.linmumua.mutool.menu.MenuItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code /tpa} online-player picker, built on the shared {@link Menu} framework.
 *
 * <p>Every online player (except the viewer) is rendered as a clickable skull in the configured
 * {@code T} slots. Clicking one sends a teleport request to that player. Previous / next page
 * buttons are driven by the YAML {@code P_*} / {@code N_*} items and their {@code prev_page} /
 * {@code next_page} actions.</p>
 *
 * <p>Item placement happens in {@link #build()}, which {@link Menu#open(Player)} runs on the
 * player's own region thread — so reading the (already parsed) menu config and generating
 * {@link ItemStack}s is Folia-safe.</p>
 */
public final class TpaMenu extends Menu {

    private final TpaModule module;
    private final Player player;
    private int page;

    public TpaMenu(TpaModule module, Player player, int page) {
        super(module.menuConfig().title(), Math.max(module.menuConfig().slots().size(), 1) * 9);
        this.module = module;
        this.player = player;
        this.page = Math.max(1, page);
    }

    @Override
    protected void build() {
        clearItems();

        TpaMenuConfig config = this.module.menuConfig();
        List<String> slots = config.slots();
        List<String> targets = this.onlineTargets();

        int pageSize = config.playerSlots().size();
        int maxPage = pageSize == 0 ? 1 : Math.max(1, (int) Math.ceil(targets.size() / (double) pageSize));
        this.page = Math.max(1, Math.min(this.page, maxPage));

        int start = (this.page - 1) * pageSize;
        int targetIndex = start;

        Map<String, String> pagePlaceholders = Map.of(
                "page", String.valueOf(this.page),
                "max_page", String.valueOf(maxPage)
        );

        int rows = Math.min(slots.size(), this.getSize() / 9);
        for (int row = 0; row < rows; row++) {
            String line = slots.get(row);
            if (line == null) {
                continue;
            }
            for (int column = 0; column < Math.min(line.length(), 9); column++) {
                char symbol = line.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                int slot = row * 9 + column;
                String key = String.valueOf(symbol);

                if (key.equals(config.playerKey())) {
                    if (targetIndex < targets.size()) {
                        String target = targets.get(targetIndex++);
                        setItem(slot, new MenuItem(
                                this.playerIcon(config, target, pagePlaceholders),
                                event -> this.module.sendRequest(this.player, target)
                        ));
                    }
                    continue;
                }

                TpaMenuConfig.Item item = config.resolve(key, this.page, maxPage);
                if (item == null) {
                    continue;
                }

                TpaMenuConfig.PageAction action = item.action();
                if (action == TpaMenuConfig.PageAction.PREV) {
                    int target = Math.max(1, this.page - 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else if (action == TpaMenuConfig.PageAction.NEXT) {
                    int target = Math.min(maxPage, this.page + 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else {
                    setItem(slot, new MenuItem(item.build(pagePlaceholders)));
                }
            }
        }
    }

    /** Names of every online player except the viewer, sorted for stable pagination. */
    private List<String> onlineTargets() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> !name.equalsIgnoreCase(this.player.getName()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private ItemStack playerIcon(TpaMenuConfig config, String target, Map<String, String> base) {
        TpaMenuConfig.Item template = config.playerItem();
        if (template == null) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        Map<String, String> placeholders = new HashMap<>(base);
        placeholders.put("target", target);
        ItemStack stack = template.build(placeholders);
        if (stack.getType() == Material.PLAYER_HEAD && stack.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
