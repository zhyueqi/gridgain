/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed.dht.atomic;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.*;
import org.gridgain.grid.kernal.processors.cache.distributed.dht.preloader.*;
import org.gridgain.grid.kernal.processors.cache.distributed.near.*;
import org.gridgain.grid.kernal.processors.cache.dr.*;
import org.gridgain.grid.kernal.processors.dr.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;
import sun.misc.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static org.gridgain.grid.GridSystemProperties.*;
import static org.gridgain.grid.cache.GridCacheAtomicWriteOrderMode.*;
import static org.gridgain.grid.cache.GridCachePeekMode.*;
import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheOperation.*;
import static org.gridgain.grid.kernal.processors.dr.GridDrType.*;

/**
 * Non-transactional partitioned cache.
 */
@GridToStringExclude
public class GridDhtAtomicCache<K, V> extends GridDhtCacheAdapter<K, V> {
    /** Deferred update response buffer size. */
    private static final int DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE =
        Integer.getInteger(GG_ATOMIC_DEFERRED_ACK_BUFFER_SIZE, 256);

    /** Deferred update response timeout. */
    private static final int DEFERRED_UPDATE_RESPONSE_TIMEOUT =
        Integer.getInteger(GG_ATOMIC_DEFERRED_ACK_TIMEOUT, 500);

    /** Unsafe instance. */
    private static final Unsafe UNSAFE = GridUnsafe.unsafe();

    /** Will be {@code true} if affinity has backups. */
    private boolean hasBackups;

    /** Update reply closure. */
    private CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> updateReplyClos;

    /** Pending  */
    private ConcurrentHashMap8<UUID, DeferredResponseBuffer> pendingResponses = new ConcurrentHashMap8<>();

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridDhtAtomicCache() {
        // No-op.
    }

    /**
     * @param ctx Cache context.
     */
    public GridDhtAtomicCache(GridCacheContext<K, V> ctx) {
        super(ctx);
    }

    /**
     * @param ctx Cache context.
     * @param map Cache concurrent map.
     */
    public GridDhtAtomicCache(GridCacheContext<K, V> ctx, GridCacheConcurrentMap<K, V> map) {
        super(ctx, map);
    }

    /** {@inheritDoc} */
    @Override public boolean isDhtAtomic() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected void init() {
        map.setEntryFactory(new GridCacheMapEntryFactory<K, V>() {
            /** {@inheritDoc} */
            @Override public GridCacheMapEntry<K, V> create(GridCacheContext<K, V> ctx, long topVer, K key, int hash,
                V val, GridCacheMapEntry<K, V> next, long ttl, int hdrId) {
                return new GridDhtAtomicCacheEntry<>(ctx, topVer, key, hash, val, next, ttl, hdrId);
            }
        });

        updateReplyClos = new CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>>() {
            @Override public void apply(GridNearAtomicUpdateRequest<K, V> req, GridNearAtomicUpdateResponse<K, V> res) {
                if (ctx.config().getAtomicWriteOrderMode() == CLOCK) {
                    // Always send reply in CLOCK ordering mode.
                    sendNearUpdateReply(res.nodeId(), res);

                    return;
                }

                // Request should be for primary keys only in PRIMARY ordering mode.
                assert req.hasPrimary();

                if (req.writeSynchronizationMode() != FULL_ASYNC)
                    sendNearUpdateReply(res.nodeId(), res);
                else {
                    if (!F.isEmpty(res.remapKeys()))
                        // Remap keys on primary node in FULL_ASYNC mode.
                        remapToNewPrimary(req);
                    else if (res.error() != null) {
                        U.error(log, "Failed to process write update request in FULL_ASYNC mode for keys: " +
                            res.failedKeys(), res.error());
                    }
                }
            }
        };
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"IfMayBeConditional", "SimplifiableIfStatement"})
    @Override public void start() throws GridException {
        hasBackups = ctx.config().getBackups() > 0;

        preldr = new GridDhtPreloader<>(ctx);

        preldr.start();

        ctx.io().addHandler(GridNearGetRequest.class, new CI2<UUID, GridNearGetRequest<K, V>>() {
            @Override public void apply(UUID nodeId, GridNearGetRequest<K, V> req) {
                processNearGetRequest(nodeId, req);
            }
        });

        ctx.io().addHandler(GridNearGetResponse.class, new CI2<UUID, GridNearGetResponse<K, V>>() {
            @Override public void apply(UUID nodeId, GridNearGetResponse<K, V> res) {
                processNearGetResponse(nodeId, res);
            }
        });

        ctx.io().addHandler(GridNearAtomicUpdateRequest.class, new CI2<UUID, GridNearAtomicUpdateRequest<K, V>>() {
            @Override public void apply(UUID nodeId, GridNearAtomicUpdateRequest<K, V> req) {
                processNearAtomicUpdateRequest(nodeId, req);
            }
        });

        ctx.io().addHandler(GridNearAtomicUpdateResponse.class, new CI2<UUID, GridNearAtomicUpdateResponse<K, V>>() {
            @Override public void apply(UUID nodeId, GridNearAtomicUpdateResponse<K, V> res) {
                processNearAtomicUpdateResponse(nodeId, res);
            }
        });

        ctx.io().addHandler(GridDhtAtomicUpdateRequest.class, new CI2<UUID, GridDhtAtomicUpdateRequest<K, V>>() {
            @Override public void apply(UUID nodeId, GridDhtAtomicUpdateRequest<K, V> req) {
                processDhtAtomicUpdateRequest(nodeId, req);
            }
        });

        ctx.io().addHandler(GridDhtAtomicUpdateResponse.class, new CI2<UUID, GridDhtAtomicUpdateResponse<K, V>>() {
            @Override public void apply(UUID nodeId, GridDhtAtomicUpdateResponse<K, V> res) {
                processDhtAtomicUpdateResponse(nodeId, res);
            }
        });

        ctx.io().addHandler(GridDhtAtomicDeferredUpdateResponse.class,
            new CI2<UUID, GridDhtAtomicDeferredUpdateResponse<K, V>>() {
                @Override public void apply(UUID nodeId, GridDhtAtomicDeferredUpdateResponse<K, V> res) {
                    processDhtAtomicDeferredUpdateResponse(nodeId, res);
                }
            });
    }

    /** {@inheritDoc} */
    @Override public GridNearCache<K, V> near() {
        assert false : "Should not be called.";

        return null;
    }

    /**
     * @return Whether backups are configured for this cache.
     */
    public boolean hasBackups() {
        return hasBackups;
    }

    /** {@inheritDoc} */
    @Override public GridCacheEntry<K, V> entry(K key) {
        return new GridDhtCacheEntryImpl<>(ctx.projectionPerCall(), ctx, key, null);
    }

    /** {@inheritDoc} */
    @Override public V peek(K key, @Nullable Collection<GridCachePeekMode> modes) throws GridException {
        GridTuple<V> val = null;

        if (ctx.isReplicated() || !modes.contains(NEAR_ONLY)) {
            try {
                val = peek0(true, key, modes, ctx.tm().txx());
            }
            catch (GridCacheFilterFailedException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Filter validation failed for key: " + key);

                return null;
            }
        }

        return val != null ? val.get() : null;
    }

    /** {@inheritDoc} */
    @Override public GridCacheTxLocalAdapter<K, V> newTx(
        boolean implicit,
        boolean implicitSingle,
        GridCacheTxConcurrency concurrency,
        GridCacheTxIsolation isolation,
        long timeout,
        boolean invalidate,
        boolean syncCommit,
        boolean syncRollback,
        boolean swapEnabled,
        boolean storeEnabled,
        int txSize,
        @Nullable Object grpLockKey,
        boolean partLock
    ) {
        throw new UnsupportedOperationException("Transactions are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)");
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Map<K, V>> getAllAsync(
        @Nullable final Collection<? extends K> keys,
        final boolean forcePrimary,
        boolean skipTx,
        @Nullable final GridCacheEntryEx<K, V> entry,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        return asyncOp(new CO<GridFuture<Map<K, V>>>() {
            @Override public GridFuture<Map<K, V>> apply() {
                return getAllAsync0(keys, false, forcePrimary, filter);
            }
        });
    }

    /** {@inheritDoc} */
    @Override public V put(K key, V val, @Nullable GridCacheEntryEx<K, V> cached, long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        return putAsync(key, val, cached, ttl, filter).get();
    }

    /** {@inheritDoc} */
    @Override public boolean putx(K key, V val, @Nullable GridCacheEntryEx<K, V> cached,
        long ttl, @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        return putxAsync(key, val, cached, ttl, filter).get();
    }

    /** {@inheritDoc} */
    @Override public boolean putx(K key, V val,
        GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        return putxAsync(key, val, filter).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<V> putAsync(K key, V val, @Nullable GridCacheEntryEx<K, V> entry,
        long ttl, @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        return updateAllAsync0(F0.asMap(key, val), null, null, null, true, false, entry, ttl, filter);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<Boolean> putxAsync(K key, V val, @Nullable GridCacheEntryEx<K, V> entry, long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        return updateAllAsync0(F0.asMap(key, val), null, null, null, false, false, entry, ttl, filter);
    }

    /** {@inheritDoc} */
    @Override public V putIfAbsent(K key, V val) throws GridException {
        return putIfAbsentAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<V> putIfAbsentAsync(K key, V val) {
        return putAsync(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean putxIfAbsent(K key, V val) throws GridException {
        return putxIfAbsentAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> putxIfAbsentAsync(K key, V val) {
        return putxAsync(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @Override public V replace(K key, V val) throws GridException {
        return replaceAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<V> replaceAsync(K key, V val) {
        return putAsync(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean replacex(K key, V val) throws GridException {
        return replacexAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> replacexAsync(K key, V val) {
        return putxAsync(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean replace(K key, V oldVal, V newVal) throws GridException {
        return replaceAsync(key, oldVal, newVal).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> replaceAsync(K key, V oldVal, V newVal) {
        return putxAsync(key, newVal, ctx.equalsPeekArray(oldVal));
    }

    /** {@inheritDoc} */
    @Override public GridCacheReturn<V> removex(K key, V val) throws GridException {
        return removexAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridCacheReturn<V> replacex(K key, V oldVal, V newVal) throws GridException {
        return replacexAsync(key, oldVal, newVal).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<GridCacheReturn<V>> removexAsync(K key, V val) {
        return removeAllAsync0(F.asList(key), null, null, true, true, ctx.equalsPeekArray(val));
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<GridCacheReturn<V>> replacexAsync(K key, V oldVal, V newVal) {
        return updateAllAsync0(F.asMap(key, newVal), null, null, null, true, true, null, 0,
            ctx.equalsPeekArray(oldVal));
    }

    /** {@inheritDoc} */
    @Override public void putAll(Map<? extends K, ? extends V> m,
        GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        putAllAsync(m, filter).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> putAllAsync(Map<? extends K, ? extends V> m,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return updateAllAsync0(m, null, null, null, false, false, null, 0, filter);
    }

    /** {@inheritDoc} */
    @Override public void putAllDr(Map<? extends K, GridCacheDrInfo<V>> drMap) throws GridException {
        putAllDrAsync(drMap).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> putAllDrAsync(Map<? extends K, GridCacheDrInfo<V>> drMap) {
        metrics.onReceiveCacheEntriesReceived(drMap.size());

        return updateAllAsync0(null, null, drMap, null, false, false, null, 0, null);
    }

    /** {@inheritDoc} */
    @Override public void transform(K key, GridClosure<V, V> transformer) throws GridException {
        transformAsync(key, transformer).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> transformAsync(K key, GridClosure<V, V> transformer,
        @Nullable GridCacheEntryEx<K, V> entry, long ttl) {
        return updateAllAsync0(null, Collections.singletonMap(key, transformer), null, null, false, false, entry, ttl,
            null);
    }

    /** {@inheritDoc} */
    @Override public void transformAll(@Nullable Map<? extends K, ? extends GridClosure<V, V>> m) throws GridException {
        transformAllAsync(m).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> transformAllAsync(@Nullable Map<? extends K, ? extends GridClosure<V, V>> m) {
        if (F.isEmpty(m))
            return new GridFinishedFuture<Object>(ctx.kernalContext());

        return updateAllAsync0(null, m, null, null, false, false, null, 0, null);
    }

    /** {@inheritDoc} */
    @Override public V remove(K key, @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        return removeAsync(key, entry, filter).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<V> removeAsync(K key, @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        return removeAllAsync0(Collections.singletonList(key), null, entry, true, false, filter);
    }

    /** {@inheritDoc} */
    @Override public void removeAll(Collection<? extends K> keys,
        GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        removeAllAsync(keys, filter).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeAllAsync(Collection<? extends K> keys,
        GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return removeAllAsync0(keys, null, null, false, false, filter);
    }

    /** {@inheritDoc} */
    @Override public boolean removex(K key, @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        return removexAsync(key, entry, filter).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<Boolean> removexAsync(K key, @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        return removeAllAsync0(Collections.singletonList(key), null, entry, false, false, filter);
    }

    /** {@inheritDoc} */
    @Override public boolean remove(K key, V val) throws GridException {
        return removeAsync(key, val).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> removeAsync(K key, V val) {
        return removexAsync(key, ctx.equalsPeekArray(val));
    }

    /** {@inheritDoc} */
    @Override public void removeAll(GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        removeAllAsync(filter).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeAllAsync(GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return removeAllAsync(keySet(filter), filter);
    }

    /** {@inheritDoc} */
    @Override public void removeAllDr(Map<? extends K, GridCacheVersion> drMap) throws GridException {
        removeAllDrAsync(drMap).get();
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeAllDrAsync(Map<? extends K, GridCacheVersion> drMap) {
        metrics.onReceiveCacheEntriesReceived(drMap.size());

        return removeAllAsync0(null, drMap, null, false, false, null);
    }

    /**
     * @return {@code True} if store enabled and batch update on commit is enabled.
     */
    private boolean batchStoreUpdate() {
        return storeEnabled() && ctx.config().isBatchUpdateOnCommit();
    }

    /**
     * @return {@code True} if store enabled.
     */
    private boolean storeEnabled() {
        return ctx.isStoreEnabled() && ctx.config().getStore() != null;
    }

    /**
     * @param op Operation closure.
     * @return Future.
     */
    @SuppressWarnings("unchecked")
    protected <T> GridFuture<T> asyncOp(final CO<GridFuture<T>> op) {
        GridFuture<T> fail = asyncOpAcquire();

        if (fail != null)
            return fail;

        FutureHolder holder = lastFut.get();

        holder.lock();

        try {
            GridFuture fut = holder.future();

            if (fut != null && !fut.isDone()) {
                GridFuture<T> f = new GridEmbeddedFuture<>(fut,
                    new C2<T, Exception, GridFuture<T>>() {
                        @Override public GridFuture<T> apply(T t, Exception e) {
                            return op.apply();
                        }
                    }, ctx.kernalContext());

                saveFuture(holder, f);

                return f;
            }

            GridFuture<T> f = op.apply();

            saveFuture(holder, f);

            return f;
        }
        finally {
            holder.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public GridDhtFuture<Boolean> lockAllAsyncInternal(@Nullable Collection<? extends K> keys, long timeout,
        GridCacheTxLocalEx<K, V> txx, boolean isInvalidate, boolean isRead, boolean retval,
        GridCacheTxIsolation isolation, GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return new FinishedLockFuture(new UnsupportedOperationException("Locks are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)"));
    }

    /**
     * Entry point for all public API put/transform methods.
     *
     * @param map Put map. Either {@code map}, {@code transformMap} or {@code drMap} should be passed.
     * @param transformMap Transform map. Either {@code map}, {@code transformMap} or {@code drMap} should be passed.
     * @param drPutMap DR put map.
     * @param drRmvMap DR remove map.
     * @param retval Return value required flag.
     * @param rawRetval Return {@code GridCacheReturn} instance.
     * @param cached Cached cache entry for key. May be passed if and only if map size is {@code 1}.
     * @param ttl Entry time-to-live.
     * @param filter Cache entry filter for atomic updates.
     * @return Completion future.
     */
    private GridFuture updateAllAsync0(
        @Nullable final Map<? extends K, ? extends V> map,
        @Nullable final Map<? extends K, ? extends GridClosure<V, V>> transformMap,
        @Nullable final Map<? extends K, GridCacheDrInfo<V>> drPutMap,
        @Nullable final Map<? extends K, GridCacheVersion> drRmvMap,
        final boolean retval,
        final boolean rawRetval,
        @Nullable GridCacheEntryEx<K, V> cached,
        long ttl,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        final GridNearAtomicUpdateFuture<K, V> updateFut = new GridNearAtomicUpdateFuture<>(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            transformMap != null ? TRANSFORM : UPDATE,
            map != null ? map.keySet() : transformMap != null ? transformMap.keySet() : drPutMap != null ?
                drPutMap.keySet() : drRmvMap.keySet(),
            map != null ? map.values() : transformMap != null ? transformMap.values() : null,
            drPutMap != null ? drPutMap.values() : null,
            drRmvMap != null ? drRmvMap.values() : null,
            retval,
            rawRetval,
            cached,
            ttl,
            filter);

        return asyncOp(new CO<GridFuture<Object>>() {
            @Override public GridFuture<Object> apply() {
                updateFut.map();

                return updateFut;
            }
        });
    }

    /**
     * Entry point for all public API remove methods.
     *
     * @param keys Keys to remove.
     * @param drMap DR map.
     * @param cached Cached cache entry for key. May be passed if and only if keys size is {@code 1}.
     * @param retval Return value required flag.
     * @param rawRetval Return {@code GridCacheReturn} instance.
     * @param filter Cache entry filter for atomic removes.
     * @return Completion future.
     */
    private GridFuture removeAllAsync0(
        @Nullable final Collection<? extends K> keys,
        @Nullable final Map<? extends K, GridCacheVersion> drMap,
        @Nullable GridCacheEntryEx<K, V> cached,
        final boolean retval,
        boolean rawRetval,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        assert keys != null || drMap != null;

        final GridNearAtomicUpdateFuture<K, V> updateFut = new GridNearAtomicUpdateFuture<>(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            DELETE,
            keys != null ? keys : drMap.keySet(),
            null,
            null,
            keys != null ? null : drMap.values(),
            retval,
            rawRetval,
            cached,
            0,
            filter);

        return asyncOp(new CO<GridFuture<Object>>() {
            @Override public GridFuture<Object> apply() {
                updateFut.map();

                return updateFut;
            }
        });
    }

    /**
     * Entry point to all public API get methods.
     *
     * @param keys Keys to remove.
     * @param reload Reload flag.
     * @param forcePrimary Force primary flag.
     * @param filter Filter.
     * @return Get future.
     */
    private GridFuture<Map<K, V>> getAllAsync0(@Nullable Collection<? extends K> keys, boolean reload,
        boolean forcePrimary, @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter) {
        if (F.isEmpty(keys))
            return new GridFinishedFuture<>(ctx.kernalContext(), Collections.<K, V>emptyMap());

        // Optimisation: try to resolve value locally and escape 'get future' creation.
        if (!reload && !forcePrimary) {
            Map<K, V> locVals = new HashMap<>(keys.size(), 1.0f);

            GridCacheVersion obsoleteVer = null;

            boolean success = true;

            // Optimistically expect that all keys are available locally (avoid creation of get future).
            for (K key : keys) {
                GridCacheEntryEx<K, V> entry = null;

                while (true) {
                    try {
                        entry = ctx.isSwapOrOffheapEnabled() ? entryEx(key) : peekEx(key);

                        // If our DHT cache do has value, then we peek it.
                        if (entry != null) {
                            boolean isNew = entry.isNewLocked();

                            V v = entry.innerGet(null, /*swap*/true, /*read-through*/false, /*fail-fast*/true,
                                /*unmarshal*/true, /**update-metrics*/true, true, filter);

                            // Entry was not in memory or in swap, so we remove it from cache.
                            if (v == null) {
                                if (obsoleteVer == null)
                                    obsoleteVer = context().versions().next();

                                if (isNew && entry.markObsoleteIfEmpty(obsoleteVer))
                                    removeIfObsolete(key);

                                success = false;
                            }
                            else
                                locVals.put(key, v);
                        }
                        else
                            success = false;

                        break; // While.
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        // No-op, retry.
                    }
                    catch (GridCacheFilterFailedException ignored) {
                        // No-op, skip the key.
                        break;
                    }
                    catch (GridDhtInvalidPartitionException ignored) {
                        success = false;

                        break; // While.
                    }
                    catch (GridException e) {
                        return new GridFinishedFuture<>(ctx.kernalContext(), e);
                    }
                    finally {
                        if (entry != null)
                            ctx.evicts().touch(entry);
                    }
                }

                if (!success)
                    break;
            }

            if (success)
                return ctx.wrapCloneMap(new GridFinishedFuture<>(ctx.kernalContext(), locVals));
        }

        // Either reload or not all values are available locally.
        GridPartitionedGetFuture<K, V> fut = new GridPartitionedGetFuture<>(ctx, keys, reload, forcePrimary, null,
            filter);

        fut.init();

        return ctx.wrapCloneMap(fut);
    }

    /**
     * Executes local update.
     *
     * @param nodeId Node ID.
     * @param req Update request.
     * @param cached Cached entry if updating single local entry.
     * @param completionCb Completion callback.
     */
    public void updateAllAsyncInternal(
        final UUID nodeId,
        final GridNearAtomicUpdateRequest<K, V> req,
        @Nullable final GridCacheEntryEx<K, V> cached,
        final CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> completionCb
    ) {
        GridFuture<Object> forceFut = preldr.request(req.keys(), req.topologyVersion());

        if (forceFut.isDone())
            updateAllAsyncInternal0(nodeId, req, cached, completionCb);
        else {
            forceFut.listenAsync(new CI1<GridFuture<Object>>() {
                @Override public void apply(GridFuture<Object> t) {
                    updateAllAsyncInternal0(nodeId, req, cached, completionCb);
                }
            });
        }
    }

    /**
     * Executes local update after preloader fetched values.
     *
     * @param nodeId Node ID.
     * @param req Update request.
     * @param cached Cached entry if updating single local entry.
     * @param completionCb Completion callback.
     */
    public void updateAllAsyncInternal0(
        UUID nodeId,
        GridNearAtomicUpdateRequest<K, V> req,
        @Nullable GridCacheEntryEx<K, V> cached,
        CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> completionCb
    ) {
        GridNearAtomicUpdateResponse<K, V> res = new GridNearAtomicUpdateResponse<>(nodeId, req.futureVersion());

        List<K> keys = req.keys();

        assert !req.returnValue() || keys.size() == 1;

        GridDhtAtomicUpdateFuture<K, V> dhtFut = null;

        boolean remap = false;

        try {
            // If batch store update is enabled, we need to lock all entries.
            // First, need to acquire locks on cache entries, then check filter.
            List<GridCacheMapEntry<K, V>> locked = lockEntries(keys, req.topologyVersion());
            Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> deleted = null;

            try {
                topology().readLock();

                try {
                    // Do not check topology version for CLOCK versioning since
                    // partition exchange will wait for near update future.
                    if (topology().topologyVersion() == req.topologyVersion() ||
                        ctx.config().getAtomicWriteOrderMode() == CLOCK) {
                        GridCacheVersion ver = req.updateVersion();

                        if (ver == null)
                            // Assign next version for update inside entries lock.
                            ver = ctx.versions().next(req.topologyVersion());

                        assert ver != null : "Got null version for update request: " + req;

                        if (log.isDebugEnabled())
                            log.debug("Using cache version for update request on primary node [ver=" + ver +
                                ", req=" + req + ']');

                        dhtFut = createDhtFuture(ver, req, res, completionCb);

                        GridCacheReturn<V> retVal = null;

                        boolean replicate = ctx.isReplicationEnabled();

                        if (batchStoreUpdate() && keys.size() > 1 && cacheCfg.getDrReceiverConfiguration() == null)
                            // This method can only be used when there are no replicated entries in the batch.
                            deleted = updateWithBatch(nodeId, req, res, locked, ver, dhtFut, replicate);
                        else {
                            GridBiTuple<GridCacheReturn<V>, Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>>> t =
                                updateSingle(nodeId, req, res, locked, ver, dhtFut, replicate);

                            retVal = t.get1();
                            deleted = t.get2();
                        }

                        if (retVal == null)
                            retVal = new GridCacheReturn<>(null, true);

                        res.returnValue(retVal);
                    }
                    else
                        // Should remap all keys.
                        remap = true;
                }
                finally {
                    topology().readUnlock();
                }
            }
            catch (GridCacheEntryRemovedException e) {
                assert false : "Entry should not become obsolete while holding lock.";

                e.printStackTrace();
            }
            finally {
                unlockEntries(locked);

                // Enqueue if necessary after locks release.
                if (deleted != null) {
                    assert !deleted.isEmpty();
                    assert ctx.deferredDelete();

                    for (GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion> e : deleted)
                        ctx.onDeferredDelete(e.get1(), e.get2());
                }
            }
        }
        catch (GridDhtInvalidPartitionException ignore) {
            assert ctx.config().getAtomicWriteOrderMode() == PRIMARY;

            if (log.isDebugEnabled())
                log.debug("Caught invalid partition exception for cache entry (will remap update request): " + req);

            remap = true;
        }

        if (remap) {
            assert dhtFut == null;

            res.remapKeys(req.keys());

            completionCb.apply(req, res);
        }
        else {
            // If there are backups, map backup update future.
            if (dhtFut != null)
                dhtFut.map();
                // Otherwise, complete the call.
            else
                completionCb.apply(req, res);
        }
    }

    /**
     * Updates locked entries using batched write-through.
     *
     * @param nodeId Sender node ID.
     * @param req Update request.
     * @param res Update response.
     * @param locked Locked entries.
     * @param ver Assigned version.
     * @param dhtFut Optional DHT future.
     * @param replicate Whether replication is enabled.
     * @return Deleted entries.
     * @throws GridCacheEntryRemovedException Should not be thrown.
     */
    @Nullable private Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> updateWithBatch(
        UUID nodeId,
        GridNearAtomicUpdateRequest<K, V> req,
        GridNearAtomicUpdateResponse<K, V> res,
        List<GridCacheMapEntry<K, V>> locked,
        GridCacheVersion ver,
        @Nullable GridDhtAtomicUpdateFuture<K, V> dhtFut,
        boolean replicate
    ) throws GridCacheEntryRemovedException {
        // Cannot update in batches during DR due to possible conflicts.
        assert !req.returnValue(); // Should not request return values for putAll.

        int size = req.keys().size();

        Map<K, V> putMap = null;
        Collection<K> rmvKeys = null;
        Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> deleted = null;
        List<GridCacheMapEntry<K, V>> filtered = new ArrayList<>(size);
        GridCacheOperation op = req.operation();

        for (int i = 0; i < locked.size(); i++) {
            GridCacheMapEntry<K, V> entry = locked.get(i);

            try {
                if (!checkFilter(entry, req, res)) {
                    if (log.isDebugEnabled())
                        log.debug("Entry did not pass the filter (will skip write) [entry=" + entry +
                            ", filter=" + Arrays.toString(req.filter()) + ", res=" + res + ']');

                    continue;
                }

                filtered.add(entry);

                if (op == TRANSFORM) {
                    V old = entry.innerGet(null, true, true, false, true, true, true, CU.<K, V>empty());

                    GridClosure<V, V> transform = req.transformClosure(i);

                    V updated = transform.apply(old);

                    if (updated == null) {
                        // Update previous batch.
                        if (putMap != null) {
                            deleted = updatePartialBatch(
                                filtered,
                                ver,
                                nodeId,
                                putMap,
                                null,
                                dhtFut,
                                req,
                                res,
                                replicate,
                                deleted);

                            putMap = null;

                            filtered = new ArrayList<>();
                        }

                        // Start collecting new batch.
                        if (rmvKeys == null)
                            rmvKeys = new ArrayList<>(size);

                        rmvKeys.add(entry.key());
                    }
                    else {
                        // Update previous batch.
                        if (rmvKeys != null) {
                            deleted = updatePartialBatch(
                                filtered,
                                ver,
                                nodeId,
                                null,
                                rmvKeys,
                                dhtFut,
                                req,
                                res,
                                replicate,
                                deleted);

                            rmvKeys = null;

                            filtered = new ArrayList<>();
                        }

                        if (putMap == null)
                            putMap = new LinkedHashMap<>(size, 1.0f);

                        putMap.put(entry.key(), updated);
                    }
                }
                else if (op == UPDATE) {
                    if (putMap == null)
                        putMap = new LinkedHashMap<>(size, 1.0f);

                    V updated = req.value(i);

                    assert updated != null;

                    putMap.put(entry.key(), updated);
                }
                else {
                    assert op == DELETE;

                    if (rmvKeys == null)
                        rmvKeys = new ArrayList<>(size);

                    rmvKeys.add(entry.key());
                }
            }
            catch (GridException e) {
                res.addFailedKey(entry.key(), e);
            }
            catch (GridCacheFilterFailedException ignore) {
                assert false : "Filter should never fail with failFast=false and empty filter.";
            }
        }

        // Store final batch.
        if (putMap != null || rmvKeys != null) {
            deleted = updatePartialBatch(
                filtered,
                ver,
                nodeId,
                putMap,
                rmvKeys,
                dhtFut,
                req,
                res,
                replicate,
                deleted);
        }
        else
            assert filtered.isEmpty();

        return deleted;
    }

    /**
     * Updates locked entries one-by-one.
     *
     * @param nodeId Originating node ID.
     * @param req Update request.
     * @param res Update response.
     * @param locked Locked entries.
     * @param ver Assigned update version.
     * @param dhtFut Optional DHT future.
     * @param replicate Whether DR is enabled for that cache.
     * @return Return value.
     * @throws GridCacheEntryRemovedException Should be never thrown.
     */
    private GridBiTuple<GridCacheReturn<V>, Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>>> updateSingle(
        UUID nodeId,
        GridNearAtomicUpdateRequest<K, V> req,
        GridNearAtomicUpdateResponse<K, V> res,
        List<GridCacheMapEntry<K, V>> locked,
        GridCacheVersion ver,
        @Nullable GridDhtAtomicUpdateFuture<K, V> dhtFut,
        boolean replicate
    ) throws GridCacheEntryRemovedException {
        GridCacheReturn<V> retVal = null;
        Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> deleted = null;

        List<K> keys = req.keys();

        // Avoid iterator creation.
        for (int i = 0; i < keys.size(); i++) {
            K k = keys.get(i);

            GridCacheOperation op = req.operation();

            // We are holding java-level locks on entries at this point.
            // No GridCacheEntryRemovedException can be thrown.
            try {
                GridCacheEntryEx<K, V> entry = locked.get(i);

                GridCacheVersion newDrVer = req.drVersion(i);
                long newDrTtl = req.drTtl(i);
                long newDrExpireTime = req.drExpireTime(i);

                assert !(newDrVer instanceof GridCacheVersionEx) : newDrVer; // Plain version is expected here.

                if (newDrVer == null)
                    newDrVer = ver;

                boolean primary = !req.fastMap() || ctx.affinity().primary(ctx.localNode(), entry.key(),
                    req.topologyVersion());

                byte[] newValBytes = req.valueBytes(i);

                GridCacheUpdateAtomicResult<K, V> updRes = entry.innerUpdate(
                    ver,
                    nodeId,
                    locNodeId,
                    op,
                    req.writeValue(i),
                    newValBytes,
                    primary && storeEnabled(),
                    req.returnValue(),
                    req.ttl(),
                    true,
                    true,
                    primary,
                    ctx.config().getAtomicWriteOrderMode() == CLOCK, // Check version in CLOCK mode on primary node.
                    req.filter(),
                    replicate ? primary ? DR_PRIMARY : DR_BACKUP : DR_NONE,
                    newDrTtl,
                    newDrExpireTime,
                    newDrVer,
                    true);

                if (dhtFut != null) {
                    if (updRes.sendToDht()) { // Send to backups even in case of remove-remove scenarios.
                        GridDrReceiverConflictContextImpl ctx = updRes.drConflictContext();

                        long ttl = updRes.newTtl();
                        long drExpireTime = updRes.drExpireTime();

                        if (ctx == null)
                            newDrVer = null;
                        else if (ctx.isMerge()) {
                            newDrVer = null; // DR version is discarded in case of merge.
                            newValBytes = null; // Value has been changed.
                        }

                        dhtFut.addWriteEntry(entry, updRes.newValue(), newValBytes, drExpireTime >= 0L ? ttl : -1L,
                            drExpireTime, newDrVer, drExpireTime < 0L ? ttl : 0L);
                    }
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Entry did not pass the filter or conflict resolution (will skip write) " +
                                "[entry=" + entry + ", filter=" + Arrays.toString(req.filter()) + ']');
                    }
                }

                if (updRes.removeVersion() != null) {
                    if (deleted == null)
                        deleted = new ArrayList<>(keys.size());

                    deleted.add(F.t(entry, updRes.removeVersion()));
                }

                // Create only once.
                if (retVal == null)
                    retVal = new GridCacheReturn<>(updRes.oldValue(), updRes.success());
            }
            catch (GridException e) {
                res.addFailedKey(k, e);
            }
        }

        return F.t(retVal, deleted);
    }

    /**
     * @param entries Entries to update.
     * @param ver Version to set.
     * @param nodeId Originating node ID.
     * @param putMap Values to put.
     * @param rmvKeys Keys to remove.
     * @param dhtFut DHT update future if has backups.
     * @param req Request.
     * @param res Response.
     * @param replicate Whether replication is enabled.
     * @param deleted Deleted entries.
     * @return Deleted entries.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    @Nullable private Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> updatePartialBatch(
        List<GridCacheMapEntry<K, V>> entries,
        final GridCacheVersion ver,
        UUID nodeId,
        @Nullable Map<K, V> putMap,
        @Nullable Collection<K> rmvKeys,
        @Nullable GridDhtAtomicUpdateFuture<K, V> dhtFut,
        final GridNearAtomicUpdateRequest<K, V> req,
        final GridNearAtomicUpdateResponse<K, V> res,
        boolean replicate,
        @Nullable Collection<GridBiTuple<GridCacheEntryEx<K, V>, GridCacheVersion>> deleted
    ) {
        assert putMap == null ^ rmvKeys == null;

        assert req.drVersions() == null : "updatePartialBatch cannot be called when there are DR entries in the batch.";

        try {
            GridCacheOperation op;

            if (putMap != null) {
                // If fast mapping, filter primary keys for write to store.
                Map<K, V> storeMap = req.fastMap() ?
                    F.view(putMap, new P1<K>() {
                        @Override public boolean apply(K key) {
                            return ctx.affinity().primary(ctx.localNode(), key, req.topologyVersion());
                        }
                    }) :
                    putMap;

                ctx.store().putAllToStore(null, F.viewReadOnly(storeMap, new C1<V, GridBiTuple<V, GridCacheVersion>>() {
                    @Override public GridBiTuple<V, GridCacheVersion> apply(V v) {
                        return F.t(v, ver);
                    }
                }));

                op = UPDATE;
            }
            else {
                // If fast mapping, filter primary keys for write to store.
                Collection<K> storeKeys = req.fastMap() ?
                    F.view(rmvKeys, new P1<K>() {
                        @Override public boolean apply(K key) {
                            return ctx.affinity().primary(ctx.localNode(), key, req.topologyVersion());
                        }
                    }) :
                    rmvKeys;

                ctx.store().removeAllFromStore(null, storeKeys);

                op = DELETE;
            }

            // Avoid iterator creation.
            for (int i = 0; i < entries.size(); i++) {
                GridCacheEntryEx<K, V> entry = entries.get(i);

                assert Thread.holdsLock(entry);

                if (entry.obsolete()) {
                    assert req.operation() == DELETE : "Entry can become obsolete only after remove: " + entry;

                    continue;
                }

                try {
                    // We are holding java-level locks on entries at this point.
                    V writeVal = op == UPDATE ? putMap.get(entry.key()) : null;

                    assert writeVal != null || op == DELETE : "null write value found.";

                    boolean primary = !req.fastMap() || ctx.affinity().primary(ctx.localNode(), entry.key(),
                        req.topologyVersion());

                    GridCacheUpdateAtomicResult<K, V> updRes = entry.innerUpdate(
                        ver,
                        nodeId,
                        locNodeId,
                        op,
                        writeVal,
                        null,
                        false,
                        false,
                        req.ttl(),
                        true,
                        true,
                        primary,
                        ctx.config().getAtomicWriteOrderMode() == CLOCK, // Check version in CLOCK mode on primary node.
                        req.filter(),
                        replicate ? primary ? DR_PRIMARY : DR_BACKUP : DR_NONE,
                        -1L,
                        -1L,
                        null,
                        false);

                    if (updRes.removeVersion() != null) {
                        if (deleted == null)
                            deleted = new ArrayList<>(entries.size());

                        deleted.add(F.t(entry, updRes.removeVersion()));
                    }

                    if (dhtFut != null) {
                        GridCacheValueBytes valBytesTuple = op == DELETE ? GridCacheValueBytes.nil():
                            entry.valueBytes();

                        byte[] valBytes = valBytesTuple.getIfMarshaled();

                        dhtFut.addWriteEntry(entry, writeVal, valBytes, -1, -1, null, req.ttl());
                    }
                }
                catch (GridCacheEntryRemovedException e) {
                    assert false : "Entry cannot become obsolete while holding lock.";

                    e.printStackTrace();
                }
            }
        }
        catch (GridException e) {
            res.addFailedKeys(putMap != null ? putMap.keySet() : rmvKeys, e);
        }

        return deleted;
    }

    /**
     * Acquires java-level locks on cache entries. Returns collection of locked entries.
     *
     * @param keys Keys to lock.
     * @param topVer Topology version to lock on.
     * @return Collection of locked entries.
     * @throws GridDhtInvalidPartitionException If entry does not belong to local node. If exception is thrown,
     *      locks are released.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private List<GridCacheMapEntry<K, V>> lockEntries(List<K> keys, long topVer)
        throws GridDhtInvalidPartitionException {
        if (keys.size() == 1) {
            K key = keys.get(0);

            while (true) {
                GridDhtCacheEntry<K, V> entry = entryExx(key, topVer);

                UNSAFE.monitorEnter(entry);

                if (entry.obsolete())
                    UNSAFE.monitorExit(entry);
                else
                    return Collections.<GridCacheMapEntry<K, V>>singletonList(entry);
            }
        }
        else {
            List<GridCacheMapEntry<K, V>> locked = new ArrayList<>(keys.size());

            while (true) {
                for (K key : keys) {
                    GridDhtCacheEntry<K, V> entry = entryExx(key, topVer);

                    locked.add(entry);
                }

                for (int i = 0; i < locked.size(); i++) {
                    GridCacheMapEntry<K, V> entry = locked.get(i);

                    UNSAFE.monitorEnter(entry);

                    if (entry.obsolete()) {
                        // Unlock all locked.
                        for (int j = 0; j <= i; j++)
                            UNSAFE.monitorExit(locked.get(j));

                        // Clear entries.
                        locked.clear();

                        // Retry.
                        break;
                    }
                }

                if (!locked.isEmpty())
                    return locked;
            }
        }
    }

    /**
     * Releases java-level locks on cache entries.
     *
     * @param locked Locked entries.
     */
    private void unlockEntries(Collection<GridCacheMapEntry<K, V>> locked) {
        // Process deleted entries before locks release.
        assert ctx.deferredDelete();

        // Entries to skip eviction manager notification for.
        // Enqueue entries while holding locks.
        Collection<K> skip = null;

        for (GridCacheMapEntry<K, V> entry : locked) {
            if (entry.deleted()) {
                if (skip == null)
                    skip = new HashSet<>(locked.size(), 1.0f);

                skip.add(entry.key());
            }
        }

        // Release locks.
        for (GridCacheMapEntry<K, V> entry : locked)
            UNSAFE.monitorExit(entry);

        if (skip != null && skip.size() == locked.size())
            // Optimization.
            return;

        // Must touch all entries since update may have deleted entries.
        // Eviction manager will remove empty entries.
        for (GridCacheMapEntry<K, V> entry : locked) {
            if (skip == null || !skip.contains(entry.key()))
                ctx.evicts().touch(entry);
        }
    }

    /**
     * @param entry Entry to check.
     * @param req Update request.
     * @param res Update response. If filter evaluation failed, key will be added to failed keys and method
     *      will return false.
     * @return {@code True} if filter evaluation succeeded.
     */
    private boolean checkFilter(GridCacheEntryEx<K, V> entry, GridNearAtomicUpdateRequest<K, V> req,
        GridNearAtomicUpdateResponse<K, V> res) {
        try {
            return ctx.isAll(entry.wrapFilterLocked(), req.filter());
        }
        catch (GridException e) {
            res.addFailedKey(entry.key(), e);

            return false;
        }
    }

    /**
     * @param req Request to remap.
     */
    private void remapToNewPrimary(GridNearAtomicUpdateRequest<K, V> req) {
        if (log.isDebugEnabled())
            log.debug("Remapping near update request locally: " + req);

        Collection<?> vals;
        Collection<GridCacheDrInfo<V>> drPutVals;
        Collection<GridCacheVersion> drRmvVals;

        if (req.drVersions() == null) {
            vals = req.values();

            drPutVals = null;
            drRmvVals = null;
        }
        else if (req.operation() == UPDATE) {
            int size = req.keys().size();

            drPutVals = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                Long ttl = req.drTtl(i);

                if (ttl == null)
                    drPutVals.add(new GridCacheDrInfo<>(req.value(i), req.drVersion(i)));
                else
                    drPutVals.add(new GridCacheDrExpirationInfo<>(req.value(i), req.drVersion(i), ttl,
                        req.drExpireTime(i)));
            }

            vals = null;
            drRmvVals = null;
        }
        else {
            assert req.operation() == DELETE;

            drRmvVals = req.drVersions();

            vals = null;
            drPutVals = null;
        }

        final GridNearAtomicUpdateFuture<K, V> updateFut = new GridNearAtomicUpdateFuture<>(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            req.operation(),
            req.keys(),
            vals,
            drPutVals,
            drRmvVals,
            req.returnValue(),
            false,
            null,
            req.ttl(),
            req.filter());

        updateFut.map();
    }

    /**
     * Creates backup update future if necessary.
     *
     * @param writeVer Write version.
     * @param updateReq Update request.
     * @param updateRes Update response.
     * @param completionCb Completion callback to invoke when future is completed.
     * @return Backup update future or {@code null} if there are no backups.
     */
    @Nullable private GridDhtAtomicUpdateFuture<K, V> createDhtFuture(
        GridCacheVersion writeVer,
        GridNearAtomicUpdateRequest<K, V> updateReq,
        GridNearAtomicUpdateResponse<K, V> updateRes,
        CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> completionCb
    ) {
        long topVer = updateReq.topologyVersion();

        if (!hasBackups || updateReq.fastMap())
            return null;

        Collection<GridNode> nodes = ctx.kernalContext().discovery().cacheAffinityNodes(name(), topVer);

        // We are on primary node for some key.
        assert !nodes.isEmpty();

        if (nodes.size() == 1) {
            if (log.isDebugEnabled())
                log.debug("Partitioned cache topology has only one node, will not create DHT atomic update future " +
                    "[topVer=" + topVer + ", updateReq=" + updateReq + ']');

            return null;
        }

        GridDhtAtomicUpdateFuture<K, V> fut = new GridDhtAtomicUpdateFuture<>(ctx, completionCb, writeVer, updateReq,
            updateRes);

        ctx.mvcc().addAtomicFuture(fut.version(), fut);

        return fut;
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Near get response.
     */
    private void processNearGetResponse(UUID nodeId, GridNearGetResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing near get response [nodeId=" + nodeId + ", res=" + res + ']');

        GridPartitionedGetFuture<K, V> fut = (GridPartitionedGetFuture<K, V>)ctx.mvcc().<Map<K, V>>future(
            res.version(), res.futureId());

        if (fut == null) {
            if (log.isDebugEnabled())
                log.debug("Failed to find future for get response [sender=" + nodeId + ", res=" + res + ']');

            return;
        }

        fut.onResult(nodeId, res);
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Near atomic update request.
     */
    private void processNearAtomicUpdateRequest(UUID nodeId, GridNearAtomicUpdateRequest<K, V> req) {
        if (log.isDebugEnabled())
            log.debug("Processing near atomic update request [nodeId=" + nodeId + ", req=" + req + ']');

        req.nodeId(ctx.localNodeId());

        updateAllAsyncInternal(nodeId, req, null, updateReplyClos);
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Near atomic update response.
     */
    @SuppressWarnings("unchecked")
    private void processNearAtomicUpdateResponse(UUID nodeId, GridNearAtomicUpdateResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing near atomic update response [nodeId=" + nodeId + ", res=" + res + ']');

        res.nodeId(ctx.localNodeId());

        GridNearAtomicUpdateFuture<K, V> fut = (GridNearAtomicUpdateFuture)ctx.mvcc().atomicFuture(res.futureVersion());

        if (fut != null)
            fut.onResult(nodeId, res);
        else
            U.warn(log, "Failed to find near update future for update response (will ignore) " +
                "[nodeId=" + nodeId + ", res=" + res + ']');
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Dht atomic update request.
     */
    private void processDhtAtomicUpdateRequest(UUID nodeId, GridDhtAtomicUpdateRequest<K, V> req) {
        if (log.isDebugEnabled())
            log.debug("Processing dht atomic update request [nodeId=" + nodeId + ", req=" + req + ']');

        GridCacheVersion ver = req.writeVersion();

        // Always send update reply.
        GridDhtAtomicUpdateResponse<K, V> res = new GridDhtAtomicUpdateResponse<>(req.futureVersion());

        Boolean replicate = ctx.isReplicationEnabled();

        for (int i = 0; i < req.size(); i++) {
            K key = req.key(i);

            try {
                while (true) {
                    GridCacheEntryEx<K, V> entry = null;

                    try {
                        entry = entryEx(key);

                        V val = req.value(i);
                        byte[] valBytes = req.valueBytes(i);

                        GridCacheOperation op = (val != null || valBytes != null) ? UPDATE : DELETE;

                        GridCacheUpdateAtomicResult<K, V> updRes = entry.innerUpdate(
                                ver,
                                nodeId,
                                nodeId,
                                op,
                                val,
                                valBytes,
                                /*write-through*/false,
                                /*retval*/false,
                                req.ttl(),
                                /*event*/true,
                                /*metrics*/true,
                                /*primary*/false,
                                /*check version*/true,
                                CU.<K, V>empty(),
                                replicate ? DR_BACKUP : DR_NONE,
                                req.drTtl(i),
                                req.drExpireTime(i),
                                req.drVersion(i),
                                false);

                        if (updRes.removeVersion() != null)
                            ctx.onDeferredDelete(entry, updRes.removeVersion());

                        break; // While.
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Got removed entry while updating backup value (will retry): " + key);

                        entry = null;
                    }
                    finally {
                        if (entry != null)
                            ctx.evicts().touch(entry);
                    }
                }
            }
            catch (GridDhtInvalidPartitionException ignored) {
                // Ignore.
            }
            catch (GridException e) {
                if (res != null)
                    res.addFailedKey(key, new GridException("Failed to update key on backup node: " + key, e));
                else
                    U.error(log, "Failed to update key on backup node: " + key, e);
            }
        }

        try {
            if (res != null) {
                if (res.failedKeys() != null || req.writeSynchronizationMode() == FULL_SYNC)
                    ctx.io().send(nodeId, res);
                else {
                    // No failed keys and sync mode is not FULL_SYNC, thus sending deferred response.
                    sendDeferredUpdateResponse(nodeId, req.futureVersion());
                }
            }
        }
        catch (GridTopologyException ignored) {
            U.warn(log, "Failed to send DHT atomic update response to node because it left grid: " +
                req.nodeId());
        }
        catch (GridException e) {
            U.error(log, "Failed to send DHT atomic update response (did node leave grid?) [nodeId=" + nodeId +
                ", req=" + req + ']', e);
        }
    }

    /**
     * @param nodeId Node ID to send message to.
     * @param ver Version to ack.
     */
    private void sendDeferredUpdateResponse(UUID nodeId, GridCacheVersion ver) {
        while (true) {
            DeferredResponseBuffer buf = pendingResponses.get(nodeId);

            if (buf == null) {
                buf = new DeferredResponseBuffer(nodeId);

                DeferredResponseBuffer old = pendingResponses.putIfAbsent(nodeId, buf);

                if (old == null) {
                    // We have successfully added buffer to map.
                    ctx.time().addTimeoutObject(buf);
                }
                else
                    buf = old;
            }

            if (!buf.addResponse(ver))
                // Some thread is sending filled up buffer, we can remove it.
                pendingResponses.remove(nodeId, buf);
            else
                break;
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Dht atomic update response.
     */
    private void processDhtAtomicUpdateResponse(UUID nodeId, GridDhtAtomicUpdateResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing dht atomic update response [nodeId=" + nodeId + ", res=" + res + ']');

        GridDhtAtomicUpdateFuture<K, V> updateFut = (GridDhtAtomicUpdateFuture<K, V>)ctx.mvcc().
            atomicFuture(res.futureVersion());

        if (updateFut != null)
            updateFut.onResult(nodeId, res);
        else
            U.warn(log, "Failed to find DHT update future for update response [nodeId=" + nodeId +
                ", res=" + res + ']');
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Deferred atomic update response.
     */
    private void processDhtAtomicDeferredUpdateResponse(UUID nodeId, GridDhtAtomicDeferredUpdateResponse<K, V> res) {
        if (log.isDebugEnabled())
            log.debug("Processing deferred dht atomic update response [nodeId=" + nodeId + ", res=" + res + ']');

        for (GridCacheVersion ver : res.futureVersions()) {
            GridDhtAtomicUpdateFuture<K, V> updateFut = (GridDhtAtomicUpdateFuture<K, V>)ctx.mvcc().atomicFuture(ver);

            if (updateFut != null)
                updateFut.onResult(nodeId);
            else
                U.warn(log, "Failed to find DHT update future for deferred update response [nodeId=" +
                    nodeId + ", res=" + res + ']');
        }
    }

    /**
     * @param nodeId Originating node ID.
     * @param res Near update response.
     */
    private void sendNearUpdateReply(UUID nodeId, GridNearAtomicUpdateResponse<K, V> res) {
        try {
            ctx.io().send(nodeId, res);
        }
        catch (GridTopologyException ignored) {
            U.warn(log, "Failed to send near update reply to node because it left grid: " +
                nodeId);
        }
        catch (GridException e) {
            U.error(log, "Failed to send near update reply (did node leave grid?) [nodeId=" + nodeId +
                ", res=" + res + ']', e);
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDhtAtomicCache.class, this, super.toString());
    }

    /**
     *
     */
    private static class FinishedLockFuture extends GridFinishedFutureEx<Boolean> implements GridDhtFuture<Boolean> {
        /**
         * Empty constructor required by {@link Externalizable}.
         */
        public FinishedLockFuture() {
            // No-op.
        }

        /**
         * @param err Error.
         */
        private FinishedLockFuture(Throwable err) {
            super(err);
        }

        /** {@inheritDoc} */
        @Override public Collection<Integer> invalidPartitions() {
            return Collections.emptyList();
        }
    }

    /**
     * Deferred response buffer.
     */
    private class DeferredResponseBuffer extends ReentrantReadWriteLock implements GridTimeoutObject {
        /** Filled atomic flag. */
        private AtomicBoolean guard = new AtomicBoolean(false);

        /** Response versions. */
        private Collection<GridCacheVersion> respVers = new ConcurrentLinkedDeque8<>();

        /** Node ID. */
        private final UUID nodeId;

        /** Timeout ID. */
        private final GridUuid timeoutId;

        /** End time. */
        private final long endTime;

        /**
         * @param nodeId Node ID to send message to.
         */
        private DeferredResponseBuffer(UUID nodeId) {
            this.nodeId = nodeId;

            timeoutId = GridUuid.fromUuid(nodeId);

            endTime = U.currentTimeMillis() + DEFERRED_UPDATE_RESPONSE_TIMEOUT;
        }

        /** {@inheritDoc} */
        @Override public GridUuid timeoutId() {
            return timeoutId;
        }

        /** {@inheritDoc} */
        @Override public long endTime() {
            return endTime;
        }

        /** {@inheritDoc} */
        @Override public void onTimeout() {
            if (guard.compareAndSet(false, true)) {
                writeLock().lock();

                try {
                    finish();
                }
                finally {
                    writeLock().unlock();
                }
            }
        }

        /**
         * Adds deferred response to buffer.
         *
         * @param ver Version to send.
         * @return {@code True} if response was handled, {@code false} if this buffer is filled and cannot be used.
         */
        public boolean addResponse(GridCacheVersion ver) {
            readLock().lock();

            boolean snd = false;

            try {
                if (guard.get())
                    return false;

                respVers.add(ver);

                if  (respVers.size() > DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE && guard.compareAndSet(false, true))
                    snd = true;
            }
            finally {
                readLock().unlock();
            }

            if (snd) {
                // Wait all threads in read lock to finish.
                writeLock().lock();

                try {
                    finish();

                    ctx.time().removeTimeoutObject(this);
                }
                finally {
                    writeLock().unlock();
                }
            }

            return true;
        }

        /**
         * Sends deferred notification message and removes this buffer from pending responses map.
         */
        private void finish() {
            GridDhtAtomicDeferredUpdateResponse<K, V> msg = new GridDhtAtomicDeferredUpdateResponse<>(respVers);

            try {
                ctx.io().send(nodeId, msg);
            }
            catch (GridTopologyException ignored) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send deferred dht update response to remote node (did node leave grid?) " +
                        "[nodeId=" + nodeId + ", msg=" + msg + ']');
            }
            catch (GridException e) {
                U.error(log, "Failed to send deferred dht update response to remote node [nodeId="
                    + nodeId + ", msg=" + msg + ']', e);
            }

            pendingResponses.remove(nodeId, this);
        }
    }
}
