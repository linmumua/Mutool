package cn.linmumua.mutool.redis;

import taboolib.common.env.RuntimeDependency;

/**
 * Declares the Redis client stack as TabooLib runtime dependencies.
 *
 * <p>Mirrors {@link cn.linmumua.mutool.database.DatabaseRuntimeDeps}: the artifact is
 * declared {@code compileOnly} in {@code build.gradle.kts} purely so this module
 * compiles, while TabooLib's RuntimeEnv downloads it into {@code cache/taboolib} and
 * injects it into the plugin class loader at startup — nothing is shaded into the jar.</p>
 *
 * <p>{@code transitive = true} also pulls lettuce's own dependencies
 * (netty-handler / netty-buffer / reactor-core / slf4j-api …) so the injected class
 * loader is self-contained; {@code test} is the class TabooLib probes to decide whether
 * the artifact is already present.</p>
 */
@RuntimeDependency(
        value = "io.lettuce:lettuce-core:6.5.5.RELEASE",
        test = "io.lettuce.core.RedisClient",
        transitive = true
)
public final class RedisRuntimeDeps {

    private RedisRuntimeDeps() {
    }
}
