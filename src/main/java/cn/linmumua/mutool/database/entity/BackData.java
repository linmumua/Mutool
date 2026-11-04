package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_back} — a remembered location for the {@code /back} command.
 *
 * <p>Auto-increment {@code id} primary key; {@code player} and {@code type} are
 * indexed to make per-player/per-type lookups fast. Coordinates keep the original
 * types: {@code Double} x/y/z and {@code Float} yaw/pitch.</p>
 */
@DatabaseTable(tableName = "mumutools_back")
public final class BackData {

    @DatabaseField(generatedId = true)
    private int id;

    @DatabaseField(index = true, canBeNull = false)
    private UUID player;

    @DatabaseField(index = true, canBeNull = false)
    private String type;

    @DatabaseField(canBeNull = false)
    private long time = System.currentTimeMillis();

    @DatabaseField(canBeNull = false)
    private String server;

    @DatabaseField(canBeNull = false)
    private String world;

    @DatabaseField(canBeNull = false)
    private Double x;

    @DatabaseField(canBeNull = false)
    private Double y;

    @DatabaseField(canBeNull = false)
    private Double z;

    @DatabaseField(canBeNull = false)
    private Float yaw;

    @DatabaseField(canBeNull = false)
    private Float pitch;

    public BackData() {
    }

    public BackData(UUID player, String type) {
        this.player = player;
        this.type = type;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getServer() {
        return this.server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getWorld() {
        return this.world;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public Double getX() {
        return this.x;
    }

    public void setX(Double x) {
        this.x = x;
    }

    public Double getY() {
        return this.y;
    }

    public void setY(Double y) {
        this.y = y;
    }

    public Double getZ() {
        return this.z;
    }

    public void setZ(Double z) {
        this.z = z;
    }

    public Float getYaw() {
        return this.yaw;
    }

    public void setYaw(Float yaw) {
        this.yaw = yaw;
    }

    public Float getPitch() {
        return this.pitch;
    }

    public void setPitch(Float pitch) {
        this.pitch = pitch;
    }

    @Override
    public String toString() {
        return "BackData{id=" + this.id + ", player=" + this.player + ", type='" + this.type
                + "', time=" + this.time + ", server='" + this.server + "', world='" + this.world
                + "', x=" + this.x + ", y=" + this.y + ", z=" + this.z + ", yaw=" + this.yaw + ", pitch=" + this.pitch + "}";
    }
}
