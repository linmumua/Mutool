package cn.linmumua.mutool.redis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import taboolib.common.platform.function.IOKt;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Typed view over {@code plugins/Mutool/redis.yml}.
 *
 * <p>The default file is released from the jar on first use; afterwards the on-disk copy
 * governs. When {@link #isEnable()} is {@code false} (the shipped default) the whole
 * cross-server stack degrades to local-only delivery — see {@link MessageBus}.</p>
 */
public final class RedisSettings {

    /** Resource path of the default redis.yml shipped inside the jar. */
    private static final String DEFAULT_RESOURCE = "redis.yml";

    private final File dataFolder;
    private Configuration configuration;
    private Config value;

    public RedisSettings(@NotNull File dataFolder) {
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

        boolean enable = this.configuration.getBoolean("enable", false);
        String host = trimToDefault(this.configuration.getString("host", "127.0.0.1"), "127.0.0.1");
        int port = this.configuration.getInt("port", 6379);
        if (port <= 0 || port > 65535) {
            port = 6379;
        }

        String password = this.configuration.getString("password", "");
        if (password != null) {
            password = password.trim();
        }
        if (password != null && password.isEmpty()) {
            password = null;
        }

        String serverName = trimToDefault(this.configuration.getString("server_name", "server-1"), "server-1");
        String channelPrefix = trimToDefault(this.configuration.getString("channel_prefix", "mutool"), "mutool");

        this.value = new Config(enable, host, port, password, serverName, channelPrefix);
    }

    private static String trimToDefault(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    @NotNull
    public Config getConfig() {
        return this.value;
    }

    public boolean isEnable() {
        return this.value.enable();
    }

    @NotNull
    public String getHost() {
        return this.value.host();
    }

    public int getPort() {
        return this.value.port();
    }

    /** Redis password, or {@code null} when unset/blank. */
    @Nullable
    public String getPassword() {
        return this.value.password();
    }

    /** This server's unique id, used for source-based de-duplication. */
    @NotNull
    public String getServerName() {
        return this.value.serverName();
    }

    @NotNull
    public String getChannelPrefix() {
        return this.value.channelPrefix();
    }

    /** The single channel all bus traffic is multiplexed on. */
    @NotNull
    public String busChannel() {
        return this.value.channelPrefix();
    }

    public record Config(
            boolean enable,
            String host,
            int port,
            String password,
            String serverName,
            String channelPrefix
    ) {
    }
}
