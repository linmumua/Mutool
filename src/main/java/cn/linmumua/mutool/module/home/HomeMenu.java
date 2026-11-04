package cn.linmumua.mutool.module.home;

import cn.linmumua.mutool.database.entity.HomeData;
import cn.linmumua.mutool.menu.Menu;
import cn.linmumua.mutool.menu.MenuItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home list GUI — one icon per home in the configured {@code H} slots, plus optional
 * previous / next page buttons, built on the shared {@link Menu} framework.
 *
 * <p>A fresh instance is created for every open / page switch (see
 * {@link HomeModule#openMenu(Player, int)}). Item placement happens in {@link #build()},
 * which {@link Menu#open(Player)} runs on the player's own region thread — so reading the
 * (already parsed) menu config and generating {@link ItemStack}s is Folia-safe.</p>
 */
public final class HomeMenu extends Menu {

    private final HomeModule module;
    private final Player player;
    private int page;

    public HomeMenu(HomeModule module, Player player, int page) {
        // Size is row-count * 9; Menu normalises it into [9, 54].
        super(module.menuConfig().title(), Math.max(module.menuConfig().slots().size(), 1) * 9);
        this.module = module;
        this.player = player;
        this.page = Math.max(1, page);
    }

    @Override
    protected void build() {
        clearItems();

        HomeMenuConfig config = this.module.menuConfig();
        List<String> slots = config.slots();
        List<HomeData> homes = this.module.homesOf(this.player.getUniqueId());

        int pageSize = config.homeSlots().size();
        int maxPage = pageSize == 0 ? 1 : Math.max(1, (int) Math.ceil(homes.size() / (double) pageSize));
        this.page = Math.max(1, Math.min(this.page, maxPage));

        int start = (this.page - 1) * pageSize;
        int homeIndex = start;

        Map<String, String> pagePlaceholders = Map.of(
                "page", String.valueOf(this.page),
                "max_page", String.valueOf(maxPage)
        );

        // Never place beyond the actual (normalised) inventory size.
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

                if (key.equals(config.homeKey())) {
                    if (homeIndex < homes.size()) {
                        HomeData data = homes.get(homeIndex++);
                        setItem(slot, new MenuItem(
                                this.homeIcon(config, data, pagePlaceholders),
                                event -> this.module.teleport(this.player, data)
                        ));
                    }
                    continue;
                }

                HomeMenuConfig.Item item = config.resolve(key, this.page, maxPage);
                if (item == null) {
                    continue;
                }

                HomeMenuConfig.PageAction action = item.action();
                if (action == HomeMenuConfig.PageAction.PREV) {
                    int target = Math.max(1, this.page - 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else if (action == HomeMenuConfig.PageAction.NEXT) {
                    int target = Math.min(maxPage, this.page + 1);
                    setItem(slot, new MenuItem(item.build(pagePlaceholders),
                            event -> this.module.openMenu(this.player, target)));
                } else {
                    setItem(slot, new MenuItem(item.build(pagePlaceholders)));
                }
            }
        }
    }

    private ItemStack homeIcon(HomeMenuConfig config, HomeData data, Map<String, String> base) {
        HomeMenuConfig.Item template = config.homeItem();
        if (template == null) {
            return new ItemStack(Material.RED_BED);
        }
        Map<String, String> placeholders = new HashMap<>(base);
        placeholders.put("name", data.getHome() == null ? "" : data.getHome());
        placeholders.put("server", data.getServer() == null ? "" : data.getServer());
        placeholders.put("world", data.getWorld() == null ? "" : data.getWorld());
        placeholders.put("x", format(data.getX()));
        placeholders.put("y", format(data.getY()));
        placeholders.put("z", format(data.getZ()));
        placeholders.put("yaw", format(data.getYaw()));
        placeholders.put("pitch", format(data.getPitch()));
        return template.build(placeholders);
    }

    private static String format(Double value) {
        return value == null ? "?" : format(value.doubleValue());
    }

    private static String format(Float value) {
        return value == null ? "?" : format(value.doubleValue());
    }

    private static String format(double value) {
        if (!Double.isInfinite(value) && !Double.isNaN(value) && value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
