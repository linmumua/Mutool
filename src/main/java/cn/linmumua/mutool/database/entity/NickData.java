package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.UUID;

/**
 * {@code mumutools_nick} — per-player nickname override.
 *
 * <p>{@code nick} is nullable (a player may have no nickname).</p>
 */
@DatabaseTable(tableName = "mumutools_nick")
public final class NickData {

    @DatabaseField(id = true, canBeNull = false)
    private UUID player;

    @DatabaseField
    private String nick;

    public NickData() {
    }

    public NickData(UUID player, String nick) {
        this.player = player;
        this.nick = nick;
    }

    public NickData(UUID player) {
        this(player, null);
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public String getNick() {
        return this.nick;
    }

    public void setNick(String nick) {
        this.nick = nick;
    }

    @Override
    public String toString() {
        return "NickData{player=" + this.player + ", nick='" + this.nick + "'}";
    }
}
