package cn.linmumua.mutool.module.chat;

import cn.linmumua.mutool.placeholder.PlaceholderService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.regex.Pattern;

/**
 * Stateless helpers shared by the chat listener and the private-message commands:
 * colour / MiniMessage permission gating, message rendering, and chat-format composition.
 */
final class ChatService {

    /** Permission allowing the player to type legacy {@code &} colour codes. */
    static final String PERM_COLOR = "mutool.chat.color";
    /** Permission allowing the player to type MiniMessage tags. */
    static final String PERM_MINIMESSAGE = "mutool.chat.minimessage";

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern MINIMESSAGE_TAG = Pattern.compile("</?[a-zA-Z0-9_:-]+[^>]*>");

    private ChatService() {
    }

    /**
     * Strip the parts of {@code message} the sender is not allowed to use: legacy colour codes when
     * they lack {@link #PERM_COLOR}, and MiniMessage tags when they lack {@link #PERM_MINIMESSAGE}.
     */
    static String sanitize(CommandSender sender, String message) {
        if (message == null) {
            return "";
        }
        if (!sender.hasPermission(PERM_COLOR)) {
            message = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));
        }
        if (!sender.hasPermission(PERM_MINIMESSAGE)) {
            message = MINIMESSAGE_TAG.matcher(message).replaceAll("");
        }
        return message;
    }

    /**
     * Sanitize then render the raw typed message into a {@link Component}, honouring the sender's
     * colour / MiniMessage permissions.
     */
    static Component renderMessage(CommandSender sender, String rawMessage) {
        String sanitized = sanitize(sender, rawMessage);
        if (sender.hasPermission(PERM_MINIMESSAGE)) {
            return MINI.deserialize(sanitized);
        }
        if (sender.hasPermission(PERM_COLOR)) {
            return LEGACY_AMP.deserialize(sanitized);
        }
        return Component.text(sanitized);
    }

    /**
     * Compose the final chat line: pick the player's format group, resolve placeholders
     * ({@code %papi%}, {@code {player}}, {@code {prefix}}) and splice the message component in
     * where the template has {@code {message}}.
     */
    static Component applyFormat(Player player, Component message, ChatConfig config) {
        ChatConfig.Group group = config.select(player);
        String format = group.format();

        format = PlaceholderService.parse(player, format);
        format = format.replace("{player}", player.getName());
        format = format.replace("{prefix}", resolvePrefix(player));

        return ChatLang.spliceMessage(format, message);
    }

    /** Resolve the luckperms / vault prefix via PlaceholderAPI; empty when unavailable. */
    private static String resolvePrefix(Player player) {
        String prefix = tryPlaceholder(player, "%luckperms_prefix%");
        if (prefix == null) {
            prefix = tryPlaceholder(player, "%vault_prefix%");
        }
        return prefix == null ? "" : prefix;
    }

    private static String tryPlaceholder(Player player, String placeholder) {
        try {
            String resolved = PlaceholderService.parse(player, placeholder);
            if (resolved == null || resolved.equals(placeholder) || resolved.indexOf('%') >= 0) {
                return null;
            }
            return resolved;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
