package cn.linmumua.mutool.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/**
 * Isolated thin wrapper around the PlaceholderAPI static API.
 *
 * <p>This class references {@code me.clip.placeholderapi.PlaceholderAPI} directly and is
 * therefore only class-loaded on servers where the PlaceholderAPI plugin is installed
 * (its classes are brought onto the classpath via {@code paper-plugin.yml}
 * {@code join-classpath: true}). {@link PlaceholderService} only touches it after
 * confirming the plugin is present, so a missing PlaceholderAPI never triggers a
 * {@link NoClassDefFoundError}.</p>
 */
final class PlaceholderApiHook {

    private PlaceholderApiHook() {
    }

    static String parse(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
