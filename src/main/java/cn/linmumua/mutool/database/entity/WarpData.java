package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

/**
 * {@code mumutools_warp} — a global named warp point (not owned by a player).
 *
 * <p>{@code String} primary key {@code warp}. Coordinates keep the original types:
 * {@code Double} x/y/z and {@code Float} yaw/pitch.</p>
 */
@DatabaseTable(tableName = "mumutools_warp")
public final class WarpData {

    @DatabaseField(id = true, canBeNull = false)
    private String warp;

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

    public WarpData() {
    }

    public WarpData(String warp) {
        this.warp = warp;
    }

    public String getWarp() {
        return this.warp;
    }

    public void setWarp(String warp) {
        this.warp = warp;
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
        return "WarpData{warp='" + this.warp + "', server='" + this.server + "', world='" + this.world
                + "', x=" + this.x + ", y=" + this.y + ", z=" + this.z + ", yaw=" + this.yaw + ", pitch=" + this.pitch + "}";
    }
}
