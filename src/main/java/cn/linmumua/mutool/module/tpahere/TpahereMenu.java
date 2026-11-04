package cn.linmumua.mutool.module.tpahere;

import cn.linmumua.mutool.menu.Menu;
import cn.linmumua.mutool.menu.MenuItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tpahere request panel shown by {@code /tpahere} (no arguments): one player head per online
 * player on this server, wired so a click sends that player a teleport request. Pagination is
 * driven by the {@code P}/{@code N} glyphs and their {@code prev_page}/{@code next_page} actions in
 * {@code module/tpahere/menu/tpahere.yml}.
 *
 * <p>A fresh instance is built for each open / page switch (see {@link TpahereModule#openMenu}).
 * {@link #build()} runs on the player's own region thread (via {@link Menu#open(Player)}), so
 * reading the parsed menu config and creating {@link ItemStack}s is Folia-safe.</p>
 */
final class TpahereMenu extends Menu {

    private final TpahereModule module;
    private final Player player;
    private int page;

    TpahereMenu(TpahereModule module, Player player, int page) {
        super(module.menuConfig().title(), Math.max(module.menuConfig().slots().size(), 1) * 9);
        this.module = module;
        this.player = player;
        this.page = Math.max(1, page);
    }

    @Override
    protected void build() {
        clearItems();

        TpahereMenuConfig config = this.module.menuConfig();
        List<String> slots = config.slots();

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(p -> p.getName() == null ? "" : p.getName().toLowerCase(Locale.ROOT)));

        int pageSize = config.playerSlots().size();
        int maxPage = pageSize == 0 ? 1 : Math.max(1, (int) Math.ceil(online.size() / (double) pageSize));
        this.page = Math.max(1, Math.min(this.page, maxPage));

        int start = (this.page - 1) * pageSize;
        int playerIndex = start;

        Map<String, String> pagePlaceholders = Map.of(
                "page", String.valueOf(this.page),
                "max_page", String.valueOf(maxPage)
        );

        int rows = Math.min(slots.size(), this.getSize() / 9);
        for (int row = 0; row < rows; row++) {
            String line = slots.get(row);
            for (int column = 0; column < Math.min(line.length(), 9); column++) {
                char symbol = line.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                int slot = row * 9 + column;
                String key = String.valueOf(symbol);

                if (key.equals(config.playerKey())) {
                    if (playerIndex < online.size() && playerIndex < start + pageSize) {
                        Player target = online.get(playerIndex++);
                        setItem(slot, new MenuItem(
                                this.playerIcon(config, target, pagePlaceholders),
                                event -> this.module.sendRequest(this.player, target.getName())
                        ));
                    }
                    continue;
                }

                TpahereMenuConfig.Item item = config.resolve(key, this.page, maxPage);
                if (item == null) {
                    continue;
                }

                TpahereMenuConfig.PageAction action = item.action();
                if (action == TpahereMenuConfig.PageAction.PREV) {
                    int target = Math.max(1, this.page - 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else if (action == TpahereMenuConfig.PageAction.NEXT) {
                    int target = Math.min(maxPage, this.page + 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else {
                    setItem(slot, new MenuItem(item.build(pagePlaceholders)));
                }
            }
        }
    }

    private ItemStack playerIcon(TpahereMenuConfig config, Player target, Map<String, String> base) {
        TpahereMenuConfig.Item template = config.playerItem();
        Map<String, String> placeholders = new HashMap<>(base);
        placeholders.put("target", target.getName());

        ItemStack stack;
        if (template == null) {
            stack = new ItemStack(Material.PLAYER_HEAD);
        } else {
            stack = template.build(placeholders);
        }
        if (stack.getType() == Material.PLAYER_HEAD && stack.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(target);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
