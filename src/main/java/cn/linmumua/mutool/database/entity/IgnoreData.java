package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_ignore} — a player's ignore list, one row per ignored player.
 *
 * <p>Auto-increment {@code id} primary key; {@code player} is the owner and
 * {@code ignore} the ignored player.</p>
 */
@DatabaseTable(tableName = "mumutools_ignore")
public final class IgnoreData {

    @DatabaseField(generatedId = true, canBeNull = false)
    private Integer id;

    @DatabaseField(canBeNull = false)
    private UUID player;

    @DatabaseField(canBeNull = false)
    private UUID ignore;

    public IgnoreData() {
    }

    public IgnoreData(UUID player, UUID ignore) {
        this.player = player;
        this.ignore = ignore;
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

    public UUID getIgnore() {
        return this.ignore;
    }

    public void setIgnore(UUID ignore) {
        this.ignore = ignore;
    }

    @Override
    public String toString() {
        return "IgnoreData{id=" + this.id + ", player=" + this.player + ", ignore=" + this.ignore + "}";
    }
}
