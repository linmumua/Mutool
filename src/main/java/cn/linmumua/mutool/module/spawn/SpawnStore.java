package cn.linmumua.mutool.module.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import taboolib.module.configuration.Configuration;
import taboolib.module.configuration.Type;

import java.io.File;

/**
 * Persistent, per-world spawn store backed by {@code module/spawn/spawn.yml}.
 *
 * <p>Single-server port note: MumuTools stored one global spawn (carrying a BungeeCord
 * {@code server} field). Mutool has no cross-server, so spawn points are kept
 * <strong>per world</strong> under {@code worlds.<world>} with
 * {@code x / y / z / yaw / pitch}. The whole document is read and written through
 * TabooLib's {@link Configuration} (a Bukkit-style {@code ConfigurationSection} that
 * exposes {@link Configuration#saveToFile(File)}).</p>
 */
public final class SpawnStore {

    private static final String ROOT = "worlds";

    private Configuration config;
    private File file;

    /** Load (or reload from disk) the given spawn data file. */
    public void load(File file) {
        this.file = file;
        this.config = Configuration.Companion.loadFromFile(file, Type.YAML, false);
    }

    /** Reload the already-loaded spawn data file. No-op when never loaded. */
    public void reload() {
        if (this.config != null) {
            this.config.reload();
        }
    }

    /** The spawn point recorded for the given world, or {@code null} when none is set. */
    public SpawnPoint get(String worldName) {
        if (this.config == null) {
            return null;
        }
        String base = ROOT + "." + worldName;
        if (!this.config.isConfigurationSection(base)) {
            return null;
        }
        double x = this.config.getDouble(base + ".x", 0.0);
        double y = this.config.getDouble(base + ".y", 60.0);
        double z = this.config.getDouble(base + ".z", 0.0);
        float yaw = (float) this.config.getDouble(base + ".yaw", 0.0);
        float pitch = (float) this.config.getDouble(base + ".pitch", 0.0);
        return new SpawnPoint(worldName, x, y, z, yaw, pitch);
    }

    /** Record &amp; persist the spawn point for the given world. */
    public void set(String worldName, Location location) {
        if (this.config == null || this.file == null) {
            return;
        }
        String base = ROOT + "." + worldName;
        this.config.set(base + ".world", worldName);
        this.config.set(base + ".x", location.getX());
        this.config.set(base + ".y", location.getY());
        this.config.set(base + ".z", location.getZ());
        this.config.set(base + ".yaw", (double) location.getYaw());
        this.config.set(base + ".pitch", (double) location.getPitch());
        this.config.saveToFile(this.file);
    }

    /** A resolved spawn point for one world. */
    public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

        /** Rebuild a Bukkit {@link Location}, or {@code null} when the world is not loaded. */
        public Location toLocation() {
            World bukkitWorld = Bukkit.getWorld(this.world);
            if (bukkitWorld == null) {
                return null;
            }
            return new Location(bukkitWorld, this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }
}
