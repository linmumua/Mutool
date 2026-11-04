package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_fly} — player flight toggle with an expiry timestamp.
 *
 * <p>The boolean maps to column {@code isEnable}, matching the legacy schema.</p>
 */
@DatabaseTable(tableName = "mumutools_fly")
public final class FlyStatus {

    @DatabaseField(id = true, canBeNull = false)
    private UUID player;

    @DatabaseField(columnName = "isEnable", canBeNull = false)
    private boolean enable;

    @DatabaseField(canBeNull = false)
    private long time;

    public FlyStatus() {
    }

    public FlyStatus(UUID player, boolean enable, long time) {
        this.player = player;
        this.enable = enable;
        this.time = time;
    }

    public FlyStatus(UUID player, boolean enable) {
        this(player, enable, -1L);
    }

    public FlyStatus(UUID player) {
        this(player, false);
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public boolean isEnable() {
        return this.enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    @Override
    public String toString() {
        return "FlyStatus{player=" + this.player + ", enable=" + this.enable + ", time=" + this.time + "}";
    }
}
