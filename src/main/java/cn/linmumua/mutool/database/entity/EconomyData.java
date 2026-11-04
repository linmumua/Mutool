package cn.linmumua.mutool.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code mumutools_economy} — per-player balance.
 *
 * <p>The money column is stored as {@code bigDecimal} with SQL format {@code 20,2},
 * exactly as in the legacy schema.</p>
 */
@DatabaseTable(tableName = "mumutools_economy")
public final class EconomyData {

    @DatabaseField(id = true, canBeNull = false)
    private UUID player;

    @DatabaseField(format = "20,2", columnName = "bigDecimal", canBeNull = false)
    private BigDecimal money;

    public EconomyData() {
    }

    public EconomyData(UUID player, BigDecimal money) {
        this.player = player;
        this.money = money;
    }

    public EconomyData(UUID player) {
        this(player, BigDecimal.ZERO);
    }

    public UUID getPlayer() {
        return this.player;
    }

    public void setPlayer(UUID player) {
        this.player = player;
    }

    public BigDecimal getMoney() {
        return this.money;
    }

    public void setMoney(BigDecimal money) {
        this.money = money;
    }

    @Override
    public String toString() {
        return "EconomyData{player=" + this.player + ", money=" + this.money + "}";
    }
}
