package cn.linmumua.mutool.database;

import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.support.ConnectionSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin, synchronous, type-safe wrapper over an ORMLite
 * {@link com.j256.ormlite.dao.Dao}.
 *
 * <p>Provides the small surface the DB-based modules need — {@link #getById(Object)},
 * {@link #getList()}, {@link #createOrUpdate(Object)}, {@link #update(Object)},
 * {@link #delete(Object)} — backed by an optional in-memory cache. The cache is a
 * plain {@link ConcurrentHashMap} keyed by primary key: {@code getById} fills it, and
 * any write clears it (the tables are small, so a full clear is cheap and never goes
 * stale).</p>
 *
 * @param <T>  entity type
 * @param <ID> primary-key type
 */
public class Dao<T, ID> {

    private final ConnectionSource connectionSource;
    private final Class<T> type;
    private final com.j256.ormlite.dao.Dao<T, ID> delegate;
    private final Map<ID, T> cache = new ConcurrentHashMap<>();

    public Dao(ConnectionSource connectionSource, Class<T> type) {
        this.connectionSource = connectionSource;
        this.type = type;
        try {
            this.delegate = DaoManager.createDao(connectionSource, type);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "无法为实体 " + type.getName() + " 创建 ORMLite DAO", exception);
        }
    }

    /** The underlying ORMLite DAO, for advanced queries. */
    public com.j256.ormlite.dao.Dao<T, ID> ormlite() {
        return this.delegate;
    }

    public Class<T> type() {
        return this.type;
    }

    public ConnectionSource connectionSource() {
        return this.connectionSource;
    }

    /** Fetch one row by primary key (cached). Returns {@code null} when absent. */
    public T getById(ID id) {
        T cached = this.cache.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            T value = this.delegate.queryForId(id);
            if (value != null) {
                this.cache.put(id, value);
            }
            return value;
        } catch (SQLException exception) {
            throw wrap("查询", exception);
        }
    }

    /** Fetch every row in the table (no caching). */
    public List<T> getList() {
        try {
            return this.delegate.queryForAll();
        } catch (SQLException exception) {
            throw wrap("列出", exception);
        }
    }

    /** Insert or update the row, then clear the cache. */
    public T createOrUpdate(T data) {
        try {
            this.delegate.createOrUpdate(data);
            this.cache.clear();
            return data;
        } catch (SQLException exception) {
            throw wrap("创建/更新", exception);
        }
    }

    /** Update an existing row, then clear the cache. */
    public int update(T data) {
        try {
            int rows = this.delegate.update(data);
            this.cache.clear();
            return rows;
        } catch (SQLException exception) {
            throw wrap("更新", exception);
        }
    }

    /** Delete a single row, then clear the cache. */
    public int delete(T data) {
        try {
            int rows = this.delegate.delete(data);
            this.cache.clear();
            return rows;
        } catch (SQLException exception) {
            throw wrap("删除", exception);
        }
    }

    /** Count the rows in the table. */
    public long count() {
        try {
            return this.delegate.countOf();
        } catch (SQLException exception) {
            throw wrap("统计", exception);
        }
    }

    /** Drop every cached entity. */
    public void clearCache() {
        this.cache.clear();
    }

    private IllegalStateException wrap(String action, SQLException exception) {
        return new IllegalStateException(
                "数据库操作失败（" + action + " " + this.type.getSimpleName() + "）", exception);
    }
}
