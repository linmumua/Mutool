package cn.linmumua.mutool.database;

import org.jetbrains.annotations.NotNull;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Typed view over {@code plugins/Mutool/database.yml}.
 *
 * <p>The default file is released from the jar on first use; afterwards the on-disk
 * copy governs. Only two back-ends are supported: {@code h2} (default) and
 * {@code mysql}. The H2 target file is resolved against the plugin data folder so the
 * database lives inside {@code plugins/Mutool/}.</p>
 */
public final class DatabaseSettings {

    /** Resource path of the default database.yml shipped inside the jar. */
    private static final String DEFAULT_RESOURCE = "database.yml";

    /** Default H2 database file name (relative to the plugin data folder). */
    private static final String DEFAULT_H2_FILE = "database.db";

    private final File dataFolder;
    private Configuration configuration;
    private Config value;

    public DatabaseSettings(@NotNull File dataFolder) {
        this.dataFolder = dataFolder;
        this.reload();
    }

    /** (Re)release the default resource if missing and re-read every value. */
    public void reload() {
        File resource = IOKt.releaseResourceFile(DEFAULT_RESOURCE, false, DEFAULT_RESOURCE);
        if (this.configuration == null) {
            this.configuration = Configuration.Companion.loadFromFile(resource, Type.YAML, false);
        } else {
            this.configuration.reload();
        }

        String type = this.configuration.getString("type", "h2");

        String host = this.configuration.getString("mysql.host", "127.0.0.1:3306");
        String database = this.configuration.getString("mysql.database", "mutool");
        String user = this.configuration.getString("mysql.user", "root");
        String password = this.configuration.getString("mysql.password", "root");

        String h2File = this.configuration.getString("h2.file", DEFAULT_H2_FILE);

        this.value = new Config(
                type == null ? "h2" : type.trim().toLowerCase(),
                new Config.MySQL(host, database, user, password),
                new Config.H2(h2File == null || h2File.isBlank() ? DEFAULT_H2_FILE : h2File)
        );
    }

    @NotNull
    public Config getConfig() {
        return this.value;
    }

    /** Whether the configured back-end is MySQL (else H2). */
    public boolean isMySQL() {
        return "mysql".equals(this.value.type());
    }

    /** Resolve the H2 database file inside the plugin data folder. */
    @NotNull
    public File h2File() {
        return new File(this.dataFolder, this.value.h2().file());
    }

    public record Config(String type, MySQL mysql, H2 h2) {

        public record MySQL(String host, String database, String user, String password) {
        }

        public record H2(String file) {
        }
    }
}
