package cn.linmumua.mutool.condition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone, dependency-free self-test for the condition engine.
 *
 * <p>This is <strong>not</strong> wired into {@code Mutool#onEnable} (and lives outside the
 * {@code module} package so {@code ModuleManager} never scans it). It exists purely so the
 * engine's pure logic — every supported type, including nested {@code &&}/{@code ||} —
 * can be exercised without a running server, e.g.:</p>
 *
 * <pre>{@code
 * java -cp <classes> cn.linmumua.mutool.condition.ConditionEngineTest
 * }</pre>
 *
 * <p>Player-dependent types (e.g. {@code permission}) are checked with a {@code null}
 * player, exercising the documented "false when no player" path. Conditions are built from
 * maps and fed through {@link ConditionEngine#testAll} so both the parser and the evaluator
 * are covered.</p>
 */
public final class ConditionEngineTest {

    private ConditionEngineTest() {
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();
        int total = 0;

        total++;
        if (!expect(true, cond("==", "value1", "1", "value2", "1"))) {
            failures.add("== (1 == 1) 期望 true");
        }
        total++;
        if (!expect(false, cond("==", "value1", "1", "value2", "2"))) {
            failures.add("== (1 == 2) 期望 false");
        }
        total++;
        if (!expect(true, cond(">", "value1", "5", "value2", "3"))) {
            failures.add("> (5 > 3) 期望 true");
        }
        total++;
        if (!expect(true, cond(">=", "value1", "3", "value2", "3"))) {
            failures.add(">= (3 >= 3) 期望 true");
        }
        total++;
        if (!expect(true, cond("<", "value1", "2", "value2", "3"))) {
            failures.add("< (2 < 3) 期望 true");
        }
        total++;
        if (!expect(true, cond("<=", "value1", "3", "value2", "3"))) {
            failures.add("<= (3 <= 3) 期望 true");
        }
        total++;
        if (!expect(false, cond("<", "value1", "not-a-number", "value2", "3"))) {
            failures.add("< (非数字) 期望 false");
        }
        total++;
        if (!expect(true, cond("contains", "string", "hello world", "value", "lo wo"))) {
            failures.add("contains 期望 true");
        }
        total++;
        if (!expect(false, cond("contains", "string", "hello", "value", "xyz"))) {
            failures.add("contains 不匹配 期望 false");
        }
        total++;
        if (!expect(true, cond("equals", "string", "abc", "value", "abc"))) {
            failures.add("equals 期望 true");
        }
        total++;
        if (!expect(false, cond("equals", "string", "abc", "value", "ABC"))) {
            failures.add("equals 大小写不同 期望 false");
        }
        total++;
        if (!expect(true, containsValueCond())) {
            failures.add("contains_value 命中 期望 true");
        }
        total++;
        if (!expect(false, containsValueCond2())) {
            failures.add("contains_value 未命中 期望 false");
        }
        total++;
        if (!expect(false, cond("permission", "permission", "some.perm"))) {
            failures.add("permission(null 玩家) 期望 false");
        }
        total++;
        if (!expect(false, cond("this_type_does_not_exist"))) {
            failures.add("未知类型 期望 false");
        }
        total++;
        if (!expect(true, andCond())) {
            failures.add("&& 全部满足 期望 true");
        }
        total++;
        if (!expect(false, andCondFail())) {
            failures.add("&& 有一项不满足 期望 false");
        }
        total++;
        if (!expect(true, orCond())) {
            failures.add("|| 有一项满足 期望 true");
        }
        total++;
        if (!expect(false, orCondFail())) {
            failures.add("|| 全部不满足 期望 false");
        }
        total++;
        if (!expect(true, List.of())) {
            failures.add("空条件列表 期望 true");
        }

        int passed = total - failures.size();
        System.out.println("[ConditionEngineTest] " + passed + "/" + total + " 通过");
        if (failures.isEmpty()) {
            System.out.println("[ConditionEngineTest] 全部通过");
        } else {
            for (String failure : failures) {
                System.out.println("[ConditionEngineTest] 失败: " + failure);
            }
            throw new IllegalStateException(failures.size() + " 项自测失败");
        }
    }

    // ------------------------------------------------------------- test helpers

    private static boolean expect(boolean expected, Object conditionOrList) {
        List<Object> conditions;
        if (conditionOrList instanceof List<?> list) {
            conditions = new ArrayList<>(list);
        } else {
            conditions = new ArrayList<>();
            conditions.add(conditionOrList);
        }
        boolean actual = ConditionEngine.testAll(null, conditions, new LinkedHashMap<>());
        return actual == expected;
    }

    private static Map<String, Object> cond(String type, String... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> containsValueCond() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "contains_value");
        map.put("list", List.of("admin", "owner", "vip"));
        map.put("value", "owner");
        return map;
    }

    private static Map<String, Object> containsValueCond2() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "contains_value");
        map.put("list", List.of("admin", "owner", "vip"));
        map.put("value", "guest");
        return map;
    }

    private static Map<String, Object> andCond() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "&&");
        map.put("conditionss", List.of(
                cond(">", "value1", "5", "value2", "3"),
                cond("<", "value1", "1", "value2", "2")
        ));
        return map;
    }

    private static Map<String, Object> andCondFail() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "&&");
        map.put("conditionss", List.of(
                cond(">", "value1", "5", "value2", "3"),
                cond(">", "value1", "1", "value2", "2")
        ));
        return map;
    }

    private static Map<String, Object> orCond() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "||");
        map.put("conditionss", List.of(
                cond("==", "value1", "1", "value2", "2"),
                cond("==", "value1", "2", "value2", "2")
        ));
        return map;
    }

    private static Map<String, Object> orCondFail() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "||");
        map.put("conditionss", List.of(
                cond("==", "value1", "1", "value2", "2"),
                cond("==", "value1", "3", "value2", "2")
        ));
        return map;
    }
}
