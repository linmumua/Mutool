package cn.linmumua.mutool.module.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import taboolib.platform.util.FoliaExecutorKt;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles {@code @player} mentions: detecting who was mentioned, highlighting the mentions inside
 * the rendered message component, and pushing a title to the mentioned players.
 */
final class AtService {

    /** Sentinel added to the mention set when {@code @all} (or a configured keyword) is used. */
    static final String AT_ALL = "*";

    /** Permission required to use the "all" broadcast mention. */
    static final String PERM_ALL = "mutool.chat.all";

    private AtService() {
    }

    /**
     * Collect the names of online players whose name appears in the raw message, plus {@link #AT_ALL}
     * when the sender is allowed to and used a configured "all" keyword.
     */
    static Set<String> getAtList(Player player, String rawMessage, ChatConfig config) {
        Set<String> mentions = new HashSet<>();
        if (!config.atEnabled()) {
            return mentions;
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (rawMessage.contains(online.getName())) {
                mentions.add(online.getName());
            }
        }

        if (allowAll(player)) {
            for (String keyword : config.atAllMessage()) {
                if (rawMessage.contains(keyword)) {
                    mentions.add(AT_ALL);
                    break;
                }
            }
        }
        return mentions;
    }

    private static boolean allowAll(Permissible permissible) {
        return permissible.hasPermission(PERM_ALL);
    }

    /**
     * Highlight every {@code @name} (and configured {@code @allKeyword}) occurrence in the message
     * component using the configured at-format. Only tokens that include the leading {@code @} are
     * highlighted, which keeps the text readable and avoids replacing bare names.
     */
    static Component applyAt(Component message, Set<String> atList, ChatConfig config, ChatLang lang) {
        if (atList.isEmpty()) {
            return message;
        }
        Component result = message;
        for (String target : atList) {
            if (AT_ALL.equals(target)) {
                Component replacement = lang.atFormat(lang.atAllText());
                for (String keyword : config.atAllMessage()) {
                    result = highlight(result, "@" + keyword, replacement);
                }
                continue;
            }
            result = highlight(result, "@" + target, lang.atFormat(target));
        }
        return result;
    }

    private static Component highlight(Component message, String literal, Component replacement) {
        return message.replaceText(TextReplacementConfig.builder()
                .matchLiteral(literal)
                .replacement(replacement)
                .build());
    }

    /** Show the "you were mentioned" title to every online player in the mention set. */
    static void notifyTitles(String by, Set<String> atList, ChatLang lang) {
        if (atList.isEmpty()) {
            return;
        }
        Component title = lang.atTitleTitle(by);
        Component subtitle = lang.atTitleSubtitle(by);
        Title.Times times = Title.Times.times(
                Duration.ofMillis(lang.atTitleIn() * 50L),
                Duration.ofMillis(lang.atTitleStep() * 50L),
                Duration.ofMillis(lang.atTitleOut() * 50L)
        );

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!atList.contains(AT_ALL) && !atList.contains(online.getName())) {
                continue;
            }
            FoliaExecutorKt.runTask(online, () -> online.showTitle(Title.title(title, subtitle, times)));
        }
    }
}
