/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.fleet.retrieval;

import com.hitorro.kvstore.KVStore;
import com.hitorro.kvstore.Result;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Read-only RocksDB view of a KV directory that a pipeline writer owns.
 * Uses {@code RocksDB.openAsSecondary} — the secondary instance keeps a
 * separate LOG follower and never contends with the primary's LOCK file,
 * so the pipeline can keep writing while fleet-retrieval serves fetches.
 *
 * <p>All write ops throw {@link UnsupportedOperationException}.
 * {@link #catchUp()} is called before every read so the secondary picks
 * up newly-committed segments.</p>
 */
public class ReadOnlyKvStore implements KVStore {

    static { RocksDB.loadLibrary(); }

    private final Path primaryPath;
    private final Path secondaryPath;
    private final Options options;
    private final RocksDB db;

    public ReadOnlyKvStore(Path primaryPath, Path secondaryPath) throws IOException, RocksDBException {
        this.primaryPath = primaryPath;
        this.secondaryPath = secondaryPath;
        Files.createDirectories(secondaryPath);
        this.options = new Options();
        this.db = RocksDB.openAsSecondary(options, primaryPath.toString(), secondaryPath.toString());
    }

    /** Sync the secondary view with the primary. Fast on quiet DBs. */
    private void catchUp() {
        try { db.tryCatchUpWithPrimary(); } catch (RocksDBException ignore) {}
    }

    @Override public Result<byte[]> get(byte[] key) {
        catchUp();
        try {
            byte[] v = db.get(key);
            return v != null ? Result.success(v) : Result.success(null);
        } catch (RocksDBException e) {
            return Result.failure(e.getMessage());
        }
    }

    @Override public Result<List<byte[]>> batchGet(List<byte[]> keys) {
        catchUp();
        List<byte[]> out = new ArrayList<>(keys.size());
        try {
            for (byte[] k : keys) out.add(db.get(k));
            return Result.success(out);
        } catch (RocksDBException e) {
            return Result.failure(e.getMessage());
        }
    }

    // ── write ops disabled ────────────────────────────────────────
    @Override public Result<Void> put(byte[] key, byte[] value)                       { return refused(); }
    @Override public <T> Result<Void> put(T o, Function<T, byte[]> ke, Function<T, byte[]> vs) { return refused(); }
    @Override public Result<Void> delete(byte[] key)                                  { return refused(); }
    @Override public Result<Void> batchPut(Map<byte[], byte[]> entries, boolean tx)   { return refused(); }
    @Override public Result<Void> batchDelete(List<byte[]> keys, boolean tx)          { return refused(); }
    private static <T> Result<T> refused() {
        return Result.failure("read-only RocksDB (openAsSecondary) — pipeline writers own this KV");
    }

    // ── scan / iterate — read-only, catch-up first ────────────────
    @Override public Iterator<byte[]> streamValues(List<byte[]> keys) {
        catchUp();
        return new Iterator<>() {
            final Iterator<byte[]> it = keys.iterator();
            public boolean hasNext() { return it.hasNext(); }
            public byte[] next() {
                try { return db.get(it.next()); } catch (RocksDBException e) { return null; }
            }
        };
    }
    @Override public Iterator<byte[]> scanByPrefix(byte[] prefix) {
        catchUp();
        var it = db.newIterator();
        it.seek(prefix);
        return new Iterator<>() {
            public boolean hasNext() {
                boolean ok = it.isValid() && startsWith(it.key(), prefix);
                if (!ok) it.close();
                return ok;
            }
            public byte[] next() { var v = it.value(); it.next(); return v; }
        };
    }
    @Override public Iterator<Map.Entry<byte[], byte[]>> scanByPrefixWithKeys(byte[] prefix) {
        catchUp();
        var it = db.newIterator();
        it.seek(prefix);
        return new Iterator<>() {
            public boolean hasNext() {
                boolean ok = it.isValid() && startsWith(it.key(), prefix);
                if (!ok) it.close();
                return ok;
            }
            public Map.Entry<byte[], byte[]> next() {
                var e = Map.entry(it.key(), it.value()); it.next(); return e;
            }
        };
    }
    @Override public void consumeValues(List<byte[]> keys, Consumer<byte[]> c) {
        catchUp();
        try { for (byte[] k : keys) { byte[] v = db.get(k); if (v != null) c.accept(v); } }
        catch (RocksDBException ignore) {}
    }
    @Override public void consumePrefixValues(byte[] prefix, Consumer<byte[]> c) {
        for (var it = scanByPrefix(prefix); it.hasNext(); ) c.accept(it.next());
    }
    @Override public void consumePrefixEntries(byte[] prefix, Consumer<Map.Entry<byte[], byte[]>> c) {
        for (var it = scanByPrefixWithKeys(prefix); it.hasNext(); ) c.accept(it.next());
    }
    @Override public java.util.stream.Stream<byte[]> streamByPrefix(byte[] prefix) {
        Iterator<byte[]> it = scanByPrefix(prefix);
        return java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(it, 0), false);
    }
    @Override public java.util.stream.Stream<Map.Entry<byte[], byte[]>> streamByPrefixWithKeys(byte[] prefix) {
        Iterator<Map.Entry<byte[], byte[]>> it = scanByPrefixWithKeys(prefix);
        return java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(it, 0), false);
    }
    @Override public long getLatestSequenceNumber() { return db.getLatestSequenceNumber(); }
    private static boolean startsWith(byte[] arr, byte[] prefix) {
        if (arr.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (arr[i] != prefix[i]) return false;
        return true;
    }

    @Override public void close() { try { db.close(); } finally { options.close(); } }
    public boolean isOpen() { return true; }

    public Path primaryPath()   { return primaryPath; }
    public Path secondaryPath() { return secondaryPath; }
}
