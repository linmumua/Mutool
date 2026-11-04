package cn.linmumua.mutool;

/**
 * Placeholder marker for TabooLib's runtime-dependency mechanism.
 *
 * <p>On startup TabooLib's RuntimeEnv scans classes annotated with
 * {@code taboolib.common.env.RuntimeDependency} and downloads the declared Maven
 * artifacts at runtime (no need to shade them). This class intentionally carries no
 * annotation itself — the actual database runtime dependencies now live in
 * {@link cn.linmumua.mutool.database.DatabaseRuntimeDeps}, which pins HikariCP to
 * 6.1.0 (the previous sample here referenced 5.1.0 and would have produced a
 * conflicting classpath).</p>
 */
public final class RuntimeDeps {

    private RuntimeDeps() {
    }
}
