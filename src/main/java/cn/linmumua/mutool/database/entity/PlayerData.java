package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_player} — UUID to last-known-name mapping.
 *
 * <p>Column layout is identical to the legacy MumuTools schema so an existing
 * database file can be reused unchanged.</p>
 */
@DatabaseTable(tableName = "mumutools_player")
public final class PlayerData {

    @DatabaseField(id = true, canBeNull = false)
    private UUID player;

    @DatabaseField(index = true, canBeNull = false)
    private String name;

    public PlayerData() {
    }

    public PlayerData(UUID player, String name) {
        this.player = player;
        this.name = name;
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "PlayerData{player=" + this.player + ", name='" + this.name + "'}";
    }
}
