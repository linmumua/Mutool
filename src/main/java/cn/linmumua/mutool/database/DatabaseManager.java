package cn.linmumua.mutool.database;

import cn.linmumua.mutool.Mutool;
import cn.linmumua.mutool.database.entity.*;
import com.j256.ormlite.jdbc.DataSourceConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the connection pool (HikariCP) and the ORMLite connection source for the whole
 * plugin.
 *
 * <p>Lifecycle: {@link #connect()} builds and validates the pool, {@link
 * #createAllTables()} creates the ten {@code mumutools_*} tables if missing, and
 * {@link #close()} tears everything down. The plugin wires these into
 * {@code onEnable}/{@code onDisable}.</p>
 *
 * <p>Two back-ends are supported via {@link DatabaseSettings}:
 * <ul>
 *   <li><b>h2</b> (default): {@code jdbc:h2:<dataFolder>/database.db}</li>
 *   <li><b>mysql</b>: {@code jdbc:mysql://<host>/<database>}</li>
 * </ul>
 * Table and column names are kept byte-for-byte identical to the legacy MumuTools
 * schema so an existing {@code database.db.mv.db} keeps working once copied into the
 * new data folder.</p>
 */
public final class DatabaseManager implements AutoCloseable {

    /** Every entity that maps to one of the ten persistent tables. */
    private static final List<Class<?>> ENTITIES = List.of(
            PlayerData.class,
            EconomyData.class,
            FlyStatus.class,
            PvpStatus.class,
            VanishStatus.class,
            NickData.class,
            HomeData.class,
            IgnoreData.class,
            WarpData.class,
            BackData.class
    );

    private final DatabaseSettings settings;
    private final Logger logger;

    private HikariDataSource dataSource;
    private DataSourceConnectionSource connectionSource;
    private String jdbcUrl;

    private final Map<Class<?>, Dao<?, ?>> daos = new ConcurrentHashMap<>();

    public DatabaseManager(@NotNull Mutool plugin) {
        this.settings = new DatabaseSettings(plugin.getDataFolder());
        this.logger = plugin.getLogger();
    }

    // ---- lifecycle ----

    /** Build the Hikari pool and validate it by opening one connection. */
    public synchronized void connect() {
        if (this.dataSource != null) {
            return;
        }

        DatabaseSettings.Config config = this.settings.getConfig();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("Mutool");

        if (this.settings.isMySQL()) {
            DatabaseSettings.Config.MySQL mysql = config.mysql();
            this.jdbcUrl = "jdbc:mysql://" + mysql.host() + "/" + mysql.database()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC";
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(mysql.user());
            hikari.setPassword(mysql.password());
            hikari.setMaximumPoolSize(10);
        } else {
            File file = this.settings.h2File();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建数据库目录: " + parent.getAbsolutePath());
            }
            // Absolute path, forward slashes for cross-platform H2 URL parsing.
            String absolute = file.getAbsolutePath().replace('\\', '/');
            this.jdbcUrl = "jdbc:h2:" + absolute + ";DB_CLOSE_ON_EXIT=FALSE";
            hikari.setDriverClassName("org.h2.Driver");
            hikari.setUsername("sa");
            hikari.setPassword("");
            hikari.setMaximumPoolSize(10);
        }

        hikari.setJdbcUrl(this.jdbcUrl);
        hikari.setAutoCommit(true);

        this.dataSource = new HikariDataSource(hikari);

        // Fail fast: surface connection problems during onEnable, not on first query.
        try (Connection connection = this.dataSource.getConnection()) {
            this.logger.info("[Mutool] 数据库连接成功: " + this.backendLabel() + " @ " + this.jdbcUrl);
        } catch (SQLException exception) {
            closeQuietly();
            throw new IllegalStateException(
                    "数据库连接失败。若使用 H2，常见原因是同一个数据库文件被另一个服务端实例占用，"
                            + "或上次未正常关闭仍在锁文件: " + exception.getMessage(),
                    exception);
        }

        try {
            this.connectionSource = new DataSourceConnectionSource(this.dataSource, this.jdbcUrl);
        } catch (SQLException exception) {
            closeQuietly();
            throw new IllegalStateException("创建 ORMLite 连接源失败: " + exception.getMessage(), exception);
        }
    }

    /** Create the ten tables if they do not already exist. Requires {@link #connect()}. */
    public synchronized void createAllTables() {
        requireConnection();
        for (Class<?> entity : ENTITIES) {
            try {
                TableUtils.createTableIfNotExists(this.connectionSource, entity);
            } catch (SQLException exception) {
                throw new IllegalStateException(
                        "创建数据表失败: " + entity.getSimpleName(), exception);
            }
        }
        this.logger.info("[Mutool] 数据表检查完成，共 " + ENTITIES.size() + " 张表。");
    }

    // ---- access for modules ----

    /** Obtain (and memoize) a typed DAO for one entity. */
    @SuppressWarnings("unchecked")
    public synchronized <T, ID> Dao<T, ID> dao(@NotNull Class<T> entityType) {
        requireConnection();
        return (Dao<T, ID>) this.daos.computeIfAbsent(entityType, key -> new Dao<>(this.connectionSource, entityType));
    }

    /** The raw ORMLite connection source (for advanced use). */
    public ConnectionSource connectionSource() {
        requireConnection();
        return this.connectionSource;
    }

    public DatabaseSettings settings() {
        return this.settings;
    }

    public boolean isConnected() {
        return this.dataSource != null;
    }

    /** The entity classes managed by this manager. */
    public static List<Class<?>> entities() {
        return new ArrayList<>(ENTITIES);
    }

    @Override
    public synchronized void close() {
        this.daos.clear();
        if (this.connectionSource != null) {
            try {
                this.connectionSource.close();
            } catch (Exception exception) {
                this.logger.log(Level.WARNING, "[Mutool] 关闭数据库连接源失败", exception);
            }
            this.connectionSource = null;
        }
        closeQuietly();
    }

    // ---- internals ----

    private void closeQuietly() {
        if (this.dataSource != null) {
            try {
                this.dataSource.close();
            } catch (Exception exception) {
                this.logger.log(Level.WARNING, "[Mutool] 关闭 Hikari 连接池失败", exception);
            }
            this.dataSource = null;
        }
    }

    private void requireConnection() {
        if (this.connectionSource == null) {
            throw new IllegalStateException("数据库尚未连接，请先调用 connect()");
        }
    }

    private String backendLabel() {
        return this.settings.isMySQL() ? "MySQL" : "H2";
    }
}
