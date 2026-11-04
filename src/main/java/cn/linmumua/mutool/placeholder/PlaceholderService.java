package cn.linmumua.mutool.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Placeholder resolution service.
 *
 * <p>{@link #parse(Player, String)} first performs a few built-in {@code {player}}-style
 * replacements and then, if the <em>PlaceholderAPI</em> plugin is present, defers the
 * remaining {@code %placeholder%} expansion to it. When PlaceholderAPI is absent — or any
 * of its classes are unavailable — the text is returned unchanged (only the built-in
 * replacements applied); nothing ever throws.</p>
 *
 * <p>The direct PlaceholderAPI call lives in the isolated {@link PlaceholderApiHook} class,
 * which is only touched when the plugin is actually installed. That keeps this class
 * loadable on servers that do not have PlaceholderAPI, where the API classes are not on the
 * classpath.</p>
 */
public final class PlaceholderService {

    private PlaceholderService() {
    }

    /** Whether the PlaceholderAPI plugin is installed on this server. Never throws. */
    public static boolean isPlaceholderApiAvailable() {
        try {
            return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * Resolve placeholders in {@code text} for the given player.
     *
     * @param player the placeholder owner (may be {@code null})
     * @param text   the text to resolve (may be {@code null})
     * @return the resolved text, or {@code null} if {@code text} was {@code null}
     */
    public static String parse(Player player, String text) {
        if (text == null) {
            return null;
        }

        String result = applyBasicReplacements(player, text);

        try {
            if (isPlaceholderApiAvailable()) {
                return PlaceholderApiHook.parse(player, result);
            }
        } catch (Throwable ignored) {
            // PlaceholderAPI present but expansion failed / classes unavailable — fall through.
        }

        return result;
    }

    /** Built-in {@code {player}}-style replacements that don't require PlaceholderAPI. */
    private static String applyBasicReplacements(Player player, String text) {
        if (player == null) {
            return text;
        }

        String name = player.getName();
        String safeName = name == null ? "" : name;

        String display = safeName;
        try {
            String resolvedDisplay = player.getDisplayName();
            if (resolvedDisplay != null && !resolvedDisplay.isEmpty()) {
                display = resolvedDisplay;
            }
        } catch (Throwable ignored) {
            // keep fallback
        }

        String result = text
                .replace("{player}", safeName)
                .replace("{player_name}", safeName)
                .replace("{name}", safeName)
                .replace("{display_name}", display)
                .replace("{displayname}", display)
                .replace("{uuid}", String.valueOf(player.getUniqueId()));

        try {
            if (player.getWorld() != null) {
                result = result.replace("{world}", player.getWorld().getName());
            }
        } catch (Throwable ignored) {
            // world unavailable — keep as-is
        }

        return result;
    }
}
