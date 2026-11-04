package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_home} — a player's named warp point.
 *
 * <p>Auto-increment {@code id} primary key; {@code player} + {@code home} identify the
 * entry logically. Coordinates keep the original types: {@code Double} x/y/z and
 * {@code Float} yaw/pitch.</p>
 */
@DatabaseTable(tableName = "mumutools_home")
public final class HomeData {

    @DatabaseField(generatedId = true, canBeNull = false)
    private Integer id;

    @DatabaseField(canBeNull = false)
    private UUID player;

    @DatabaseField(canBeNull = false)
    private String home;

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

    public HomeData() {
    }

    public HomeData(UUID player, String home) {
        this.player = player;
        this.home = home;
    }

    public Integer getId() {
        return this.id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public String getHome() {
        return this.home;
    }

    public void setHome(String home) {
        this.home = home;
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
        return "HomeData{id=" + this.id + ", player=" + this.player + ", home='" + this.home
                + "', server='" + this.server + "', world='" + this.world + "', x=" + this.x
                + ", y=" + this.y + ", z=" + this.z + ", yaw=" + this.yaw + ", pitch=" + this.pitch + "}";
    }
}
