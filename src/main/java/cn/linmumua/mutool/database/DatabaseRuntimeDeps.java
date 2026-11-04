package cn.linmumua.mutool.database;

import taboolib.common.env.RuntimeDependency;

/**
 * Declares the database stack as TabooLib runtime dependencies.
 *
 * <p>TabooLib's RuntimeEnv scans these annotations at startup and downloads each
 * artifact into {@code cache/taboolib}, then injects them into the plugin's class
 * loader — so nothing is shaded into the jar. The four artifacts are also declared
 * {@code compileOnly} in {@code build.gradle.kts} purely so this module compiles.</p>
 *
 * <p>Versions are intentionally pinned to the exact stack the legacy MumuTools data
 * was produced with:</p>
 * <ul>
 *   <li>ormlite-core / ormlite-jdbc 6.1 — the ORM</li>
 *   <li>HikariCP 6.1.0 — the connection pool ({@code transitive = true} also pulls
 *       slf4j-api, keeping the injected class loader self-contained)</li>
 *   <li>h2 2.3.232 — matches the {@code .mv.db} 2.x file format of the old data</li>
 *   <li>mysql-connector-j 9.1.0 — MySQL driver</li>
 * </ul>
 */
@RuntimeDependency(
        value = "com.j256.ormlite:ormlite-jdbc:6.1",
        test = "com.j256.ormlite.jdbc.JdbcConnectionSource"
)
@RuntimeDependency(
        value = "com.zaxxer:HikariCP:6.1.0",
        test = "com.zaxxer.hikari.HikariDataSource",
        transitive = true
)
@RuntimeDependency(
        value = "com.h2database:h2:2.3.232",
        test = "org.h2.Driver"
)
@RuntimeDependency(
        value = "com.mysql:mysql-connector-j:9.1.0",
        test = "com.mysql.cj.jdbc.Driver"
)
public final class DatabaseRuntimeDeps {

    private DatabaseRuntimeDeps() {
    }
}
