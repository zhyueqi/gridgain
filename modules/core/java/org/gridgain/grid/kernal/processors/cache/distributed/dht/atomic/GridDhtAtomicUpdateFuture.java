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
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.cache.GridCacheWriteSynchronizationMode.*;

/**
 * DHT atomic cache backup update future.
 */
public class GridDhtAtomicUpdateFuture<K, V> extends GridFutureAdapter<Void>
    implements GridCacheAtomicFuture<K, Void> {
    /** Logger reference. */
    private static final AtomicReference<GridLogger> logRef = new AtomicReference<>();

    /** Logger. */
    protected static GridLogger log;

    /** Cache context. */
    private GridCacheContext<K, V> cctx;

    /** Future version. */
    private GridCacheVersion futVer;

    /** Write version. */
    private GridCacheVersion writeVer;

    /** Completion callback. */
    @GridToStringExclude
    private CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> completionCb;

    /** Mappings. */
    @GridToStringInclude
    private ConcurrentMap<UUID, GridDhtAtomicUpdateRequest<K, V>> mappings = new ConcurrentHashMap8<>();

    /** Update request. */
    private GridNearAtomicUpdateRequest<K, V> updateReq;

    /** Update response. */
    private GridNearAtomicUpdateResponse<K, V> updateRes;

    /** Remote nodes predicate. */
    @GridToStringExclude
    private GridPredicate<GridNode> rmtNodes;

    /** Future keys. */
    private Collection<K> keys;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridDhtAtomicUpdateFuture() {
        // No-op.
    }

    /**
     * @param cctx Cache context.
     * @param completionCb Callback to invoke when future is completed.
     * @param writeVer Write version.
     * @param updateReq Update request.
     * @param updateRes Update response.
     */
    public GridDhtAtomicUpdateFuture(
        GridCacheContext<K, V> cctx,
        CI2<GridNearAtomicUpdateRequest<K, V>, GridNearAtomicUpdateResponse<K, V>> completionCb,
        GridCacheVersion writeVer,
        GridNearAtomicUpdateRequest<K, V> updateReq,
        GridNearAtomicUpdateResponse<K, V> updateRes
    ) {
        super(cctx.kernalContext());

        this.cctx = cctx;
        this.writeVer = writeVer;

        futVer = cctx.versions().next(updateReq.topologyVersion());
        this.updateReq = updateReq;
        this.completionCb = completionCb;
        this.updateRes = updateRes;

        log = U.logger(ctx, logRef, GridDhtAtomicUpdateFuture.class);

        rmtNodes = F.remoteNodes(ctx.localNodeId());

        keys = new ArrayList<>(updateReq.keys().size());
    }

    /** {@inheritDoc} */
    @Override public GridUuid futureId() {
        return futVer.asGridUuid();
    }

    /** {@inheritDoc} */
    @Override public GridCacheVersion version() {
        return futVer;
    }

    /** {@inheritDoc} */
    @Override public Collection<? extends GridNode> nodes() {
        return F.view(F.viewReadOnly(mappings.keySet(), U.id2Node(cctx.kernalContext())), F.notNull());
    }

    /** {@inheritDoc} */
    @Override public boolean onNodeLeft(UUID nodeId) {
        if (log.isDebugEnabled())
            log.debug("Processing node leave event [fut=" + this + ", nodeId=" + nodeId + ']');

        GridDhtAtomicUpdateRequest<K, V> req = mappings.get(nodeId);

        if (req != null) {
            updateRes.addFailedKeys(req.keys(), new GridTopologyException("Failed to write keys on backup (node left" +
                " grid before response is received): " + nodeId));

            // Remove only after added keys to failed set.
            mappings.remove(nodeId);

            checkComplete();

            return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean trackable() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public void markNotTrackable() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public boolean waitForPartitionExchange() {
        // Wait dht update futures in PRIMARY mode.
        return cctx.config().getAtomicWriteOrderMode() == GridCacheAtomicWriteOrderMode.PRIMARY;
    }

    /** {@inheritDoc} */
    @Override public long topologyVersion() {
        return updateReq.topologyVersion();
    }

    /** {@inheritDoc} */
    @Override public Collection<? extends K> keys() {
        return keys;
    }

    /**
     * @param entry Entry to map.
     * @param val Value to write.
     * @param valBytes Value bytes.
     * @param drTtl DR TTL (optional).
     * @param drExpireTime DR expire time (optional).
     * @param drVer DR version (optional).
     * @param ttl Time to live.
     */
    public void addWriteEntry(GridCacheEntryEx<K, V> entry, @Nullable V val, @Nullable byte[] valBytes, long drTtl,
        long drExpireTime, @Nullable GridCacheVersion drVer, long ttl) {
        long topVer = updateReq.topologyVersion();

        Collection<GridNode> dhtNodes = cctx.dht().topology().nodes(entry.partition(), topVer);

        if (log.isDebugEnabled())
            log.debug("Mapping entry to DHT nodes [nodes=" + U.nodeIds(dhtNodes) + ", entry=" + entry + ']');

        Collection<GridNode> dhtRemoteNodes = F.view(dhtNodes, rmtNodes); // Exclude local node.

        GridCacheWriteSynchronizationMode syncMode = updateReq.writeSynchronizationMode();

        keys.add(entry.key());

        for (GridNode node : dhtRemoteNodes) {
            UUID nodeId = node.id();

            GridDhtAtomicUpdateRequest<K, V> updateReq = mappings.get(nodeId);

            if (updateReq == null) {
                updateReq = new GridDhtAtomicUpdateRequest<>(nodeId, futVer, writeVer, syncMode, topVer, ttl);

                mappings.put(nodeId, updateReq);
            }

            updateReq.addWriteValue(entry.key(), entry.keyBytes(), val, valBytes, drTtl, drExpireTime, drVer);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean onDone(@Nullable Void res, @Nullable Throwable err) {
        if (super.onDone(res, err)) {
            cctx.mvcc().removeAtomicFuture(version());

            if (updateReq.writeSynchronizationMode() == FULL_SYNC)
                completionCb.apply(updateReq, updateRes);

            return true;
        }

        return false;
    }

    /**
     * Sends requests to remote nodes.
     */
    public void map() {
        if (!mappings.isEmpty()) {
            for (GridDhtAtomicUpdateRequest<K, V> req : mappings.values()) {
                try {
                    if (log.isDebugEnabled())
                        log.debug("Sending DHT atomic update request [nodeId=" + req.nodeId() + ", req=" + req + ']');

                    cctx.io().send(req.nodeId(), req);
                }
                catch (GridTopologyException ignored) {
                    U.warn(log, "Failed to send update request to backup node because it left grid: " +
                        req.nodeId());

                    mappings.remove(req.nodeId());
                }
                catch (GridException e) {
                    U.error(log, "Failed to send update request to backup node (did node leave the grid?): "
                        + req.nodeId(), e);

                    mappings.remove(req.nodeId());
                }
            }
        }

        checkComplete();

        // Send response right away if no ACKs from backup is required.
        // Backups will send ACKs anyway, future will be completed after all backups have replied.
        if (updateReq.writeSynchronizationMode() != FULL_SYNC)
            completionCb.apply(updateReq, updateRes);
    }

    /**
     * Callback for backup update response.
     *
     * @param nodeId Backup node ID.
     * @param updateRes Update response.
     */
    public void onResult(UUID nodeId, GridDhtAtomicUpdateResponse<K, V> updateRes) {
        if (log.isDebugEnabled())
            log.debug("Received DHT atomic update future result [nodeId=" + nodeId + ", updateRes=" + updateRes + ']');

        if (updateRes.error() != null)
            this.updateRes.addFailedKeys(updateRes.failedKeys(), updateRes.error());

        mappings.remove(nodeId);

        checkComplete();
    }

    /**
     * Deferred update response.
     *
     * @param nodeId Backup node ID.
     */
    public void onResult(UUID nodeId) {
        if (log.isDebugEnabled())
            log.debug("Received deferred DHT atomic update future result [nodeId=" + nodeId + ']');

        mappings.remove(nodeId);

        checkComplete();
    }

    /**
     * Checks if all required responses are received.
     */
    private void checkComplete() {
        // Always wait for replies from all backups.
        if (mappings.isEmpty()) {
            if (log.isDebugEnabled())
                log.debug("Completing DHT atomic update future: " + this);

            onDone();
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDhtAtomicUpdateFuture.class, this);
    }
}
