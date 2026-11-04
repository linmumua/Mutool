package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_pvp} — player PvP toggle.
 *
 * <p>The boolean maps to column {@code isEnable}, matching the legacy schema.</p>
 */
@DatabaseTable(tableName = "mumutools_pvp")
public final class PvpStatus {

    @DatabaseField(id = true, canBeNull = false)
    private UUID player;

    @DatabaseField(columnName = "isEnable", canBeNull = false)
    private boolean enable;

    public PvpStatus() {
    }

    public PvpStatus(UUID player, boolean enable) {
        this.player = player;
        this.enable = enable;
    }

    public PvpStatus(UUID player) {
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

    @Override
    public String toString() {
        return "PvpStatus{player=" + this.player + ", enable=" + this.enable + "}";
    }
}
