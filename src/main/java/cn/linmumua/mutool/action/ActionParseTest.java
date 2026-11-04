package cn.linmumua.mutool.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone, dependency-free self-test for the action engine's <em>parsing</em> layer.
 *
 * <p>Like {@link cn.linmumua.mutool.condition.ConditionEngineTest} this lives in the main source
 * set (outside {@code module}) so {@code ModuleManager} never scans it, and it is not wired into
 * {@code Mutool#onEnable}. It only exercises {@link Action#parse(Object)} and the pure string
 * helpers — never {@link Action#run} / {@link ActionEngine#runAll}, which require a live server —
 * so it can be run with a plain {@code java -cp <classes> ...} invocation:</p>
 *
 * <pre>{@code
 * java -cp <classes> cn.linmumua.mutool.action.ActionParseTest
 * }</pre>
 */
public final class ActionParseTest {

    private ActionParseTest() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        int total = 0;

        // type canonicalisation + aliases
        total++;
        if (!expectKind("message", map("type", "message", "message", "hi"), "message")) {
            failures.add("message 类型识别");
        }
        total++;
        if (!expectKind("action_bar", map("type", "action_bar", "message", "hi"), "actionbar")) {
            failures.add("action_bar 别名 -> actionbar");
        }
        total++;
        if (!expectKind("play_sound", map("type", "play_sound", "sound", "minecraft:ui.button.click"), "sound")) {
            failures.add("play_sound 别名 -> sound");
        }
        total++;
        if (!expectKind("player_command", map("type", "player_command", "command", "spawn"), "command")) {
            failures.add("player_command 别名 -> command");
        }
        total++;
        if (!expectKind("console_command", map("type", "console_command", "command", "say hi"), "console_command")) {
            failures.add("console_command 识别");
        }
        total++;
        if (!expectKind("close", map("type", "close"), "close")) {
            failures.add("close 识别");
        }
        total++;
        if (!expectKind("unknown", map("type", "boss_bar", "title", "x"), "")) {
            failures.add("未知类型应归为空 kind");
        }

        // delay ticks + nested actions
        total++;
        Action delay = Action.parse(map("type", "delay", "ticks", 40,
                "actions", List.of(map("type", "message", "message", "later"))));
        if (!delay.isDelay()) {
            failures.add("delay.isDelay() 期望 true");
        }
        if (delay.delayTicks() != 40L) {
            failures.add("delay.delayTicks() 期望 40，实际 " + delay.delayTicks());
        }
        if (delay.nestedActions().size() != 1) {
            failures.add("delay.nestedActions() 期望 1 个");
        }

        // delay default ticks when absent
        total++;
        Action bareDelay = Action.parse(map("type", "wait"));
        if (!bareDelay.isDelay() || bareDelay.delayTicks() != 20L || !bareDelay.nestedActions().isEmpty()) {
            failures.add("bare delay 默认 20 ticks / 无子动作");
        }

        // params survive parsing (and 'type'/'actions' are stripped)
        total++;
        Action cmd = Action.parse(map("type", "command", "command", "say {player}", "as_console", true));
        if (cmd.params().containsKey("type") || cmd.params().containsKey("actions")) {
            failures.add("params 不应包含 type/actions");
        }
        if (!"say {player}".equals(String.valueOf(cmd.params().get("command")))) {
            failures.add("command 参数丢失");
        }

        // legacy &-code -> MiniMessage translation (pure string, no server)
        total++;
        String translated = ActionText.legacyToMiniMessage("&ehello &lworld &#ff8800ok");
        if (!translated.contains("<yellow>") || !translated.contains("<bold>")
                || !translated.contains("<#ff8800>")) {
            failures.add("legacyToMiniMessage 转换失败: " + translated);
        }

        if (failures.isEmpty()) {
            System.out.println("[ActionParseTest] OK — " + total + " checks passed");
        } else {
            System.out.println("[ActionParseTest] FAILED — " + failures.size() + "/" + total + " failed:");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    private static boolean expectKind(String label, Map<String, Object> node, String expectedKind) {
        Action action = Action.parse(node);
        String actual = action.kind();
        if (!expectedKind.equals(actual)) {
            System.out.println("  [kind] " + label + " 期望 '" + expectedKind + "' 实际 '" + actual + "'");
            return false;
        }
        return true;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
