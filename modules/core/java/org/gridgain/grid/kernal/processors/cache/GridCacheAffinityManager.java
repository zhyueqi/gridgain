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

package org.gridgain.grid.kernal.processors.cache;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.processors.affinity.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.typedef.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Cache affinity manager.
 */
public class GridCacheAffinityManager<K, V> extends GridCacheManagerAdapter<K, V> {
    /** Factor for maximum number of attempts to calculate all partition affinity keys. */
    private static final int MAX_PARTITION_KEY_ATTEMPT_RATIO = 10;

    /** Affinity cached function. */
    private GridAffinityCache aff;

    /** Affinity keys. */
    private GridPartitionLockKey[] partAffKeys;

    /** {@inheritDoc} */
    @Override public void start0() throws GridException {
        aff = new GridAffinityCache(cctx.kernalContext(), cctx.namex(), cctx.config().getAffinity(),
            cctx.config().getAffinityMapper(), cctx.config().getBackups());

        // Generate internal keys for partitions.
        int partCnt = partitions();

        partAffKeys = new GridPartitionLockKey[partCnt];

        Collection<Integer> found = new HashSet<>();

        long affKey = 0;

        while (true) {
            GridPartitionLockKey key = new GridPartitionLockKey(affKey);

            int part = aff.partition(key);

            if (found.add(part)) {
                // This is a key for not yet calculated partition.
                key.partitionId(part);

                partAffKeys[part] = key;

                if (found.size() == partCnt)
                    break;
            }

            affKey++;

            if (affKey > partCnt * MAX_PARTITION_KEY_ATTEMPT_RATIO)
                throw new IllegalStateException("Failed to calculate partition affinity keys for given affinity " +
                    "function [attemptCnt=" + affKey + ", found=" + found + ", cacheName=" + cctx.name() + ']');
        }
    }

    /** {@inheritDoc} */
    @Override protected void stop0(boolean cancel) {
        aff = null;
    }

    /**
     * Clean up outdated cache items.
     *
     * @param topVer Actual topology version, older versions will be removed.
     */
    public void cleanUpCache(long topVer) {
        aff.cleanUpCache(topVer);
    }

    /**
     * @return Partition count.
     */
    public int partitions() {
        return aff.partitions();
    }

    /**
     * Gets partition affinity key for given partition id. Partition affinity keys are precalculated
     * on manager start.
     *
     * @param partId Partition ID.
     * @return Affinity key.
     */
    public GridPartitionLockKey partitionAffinityKey(int partId) {
        assert partId >=0 && partId < partAffKeys.length;

        return partAffKeys[partId];
    }

    /**
     * NOTE: Use this method always when you need to calculate partition id for
     * a key provided by user. It's required since we should apply affinity mapper
     * logic in order to find a key that will eventually be passed to affinity function.
     *
     * @param key Key.
     * @return Partition.
     */
    public <T> int partition(T key) {
        return aff.partition(key);
    }

    /**
     * @param keys keys.
     * @return Nodes for the keys.
     */
    public Collection<GridNode> nodes(Iterable<? extends K> keys) {
        long topVer = topologyVersion();

        Collection<Collection<GridNode>> colcol = new LinkedList<>();

        for (K key : keys)
            colcol.add(nodes(key, topVer));

        return F.flat(colcol);
    }

    /**
     * @param key Key.
     * @return Affinity nodes.
     */
    public Collection<GridNode> nodes(K key) {
        return nodes(key, topologyVersion());
    }

    /**
     * @param part Partition.
     * @return Affinity nodes.
     */
    public Collection<GridNode> nodes(int part) {
        return nodes(part, topologyVersion());
    }

    /**
     * @param key Key.
     * @param topVer Topology version.
     * @return Affinity nodes.
     */
    public Collection<GridNode> nodes(K key, long topVer) {
        return nodes(partition(key), topVer);
    }

    /**
     * @param part Partition.
     * @param topVer Topology version.
     * @return Affinity nodes.
     */
    public Collection<GridNode> nodes(int part, long topVer) {
        return aff.nodes(part, topVer);
    }

    /**
     * @param key Key to check.
     * @return Primary node for given key.
     */
    @Nullable public GridNode primary(K key) {
        return primary(key, topologyVersion());
    }

    /**
     * @param key Key to check.
     * @param topVer Topology version.
     * @return Primary node for given key.
     */
    @Nullable public GridNode primary(K key, long topVer) {
        return primary(partition(key), topVer);
    }

    /**
     * @param part Partition.
     * @param topVer Topology version.
     * @return Primary node for given key.
     */
    @Nullable public GridNode primary(int part, long topVer) {
        Collection<GridNode> nodes = nodes(part, topVer);

        if (nodes.isEmpty())
            return null;

        return nodes.iterator().next();
    }

    /**
     * @param n Node to check.
     * @param key Key to check.
     * @return {@code True} if checked node is primary for given key.
     */
    public boolean primary(GridNode n, K key) {
        return primary(n, key, topologyVersion());
    }

    /**
     * @param n Node to check.
     * @param key Key to check.
     * @param topVer Topology version.
     * @return {@code True} if checked node is primary for given key.
     */
    public boolean primary(GridNode n, K key, long topVer) {
        return F.eq(primary(key, topVer), n);
    }

    /**
     * @param n Node to check.
     * @param part Partition.
     * @return {@code True} if checked node is primary for given key.
     */
    public boolean primary(GridNode n, int part) {
        return F.eq(primary(part, topologyVersion()), n);
    }

    /**
     * @param key Key to check.
     * @return Backup nodes.
     */
    public Collection<GridNode> backups(K key) {
        return backups(partition(key), topologyVersion());
    }

    /**
     * @param part Partition.
     * @param topVer Topology version.
     * @return Backup nodes.
     */
    public Collection<GridNode> backups(int part, long topVer) {
        Collection<GridNode> nodes = nodes(part, topVer);

        assert !F.isEmpty(nodes);

        if (nodes.size() <= 1)
            return Collections.emptyList();

        return F.view(nodes, F.notEqualTo(nodes.iterator().next()));
    }

    /**
     * @param keys keys.
     * @return Nodes for the keys.
     */
    public Collection<GridNode> remoteNodes(Iterable<? extends K> keys) {
        Collection<Collection<GridNode>> colcol = new GridLeanSet<>();

        long topVer = topologyVersion();

        for (K key : keys)
            colcol.add(nodes(key, topVer));

        return F.view(F.flat(colcol), F.remoteNodes(cctx.localNodeId()));
    }

    /**
     * @param key Key to check.
     * @return {@code true} if given key belongs to local node.
     */
    public boolean localNode(K key) {
        return localNode(partition(key));
    }

    /**
     * @param part Partition number to check.
     * @return {@code true} if given partition belongs to local node.
     */
    public boolean localNode(int part) {
        return localNode(part, topologyVersion());
    }

    /**
     * @param part Partition number to check.
     * @param topVer Topology version.
     * @return {@code true} if given partition belongs to local node.
     */
    public boolean localNode(int part, long topVer) {
        assert part >= 0 : "Invalid partition: " + part;

        return nodes(part, topVer).contains(cctx.localNode());
    }

    /**
     * @param node Node.
     * @param part Partition number to check.
     * @return {@code true} if given partition belongs to specified node.
     */
    public boolean belongs(GridNode node, int part) {
        assert node != null;
        assert part >= 0 : "Invalid partition: " + part;

        return nodes(part, topologyVersion()).contains(node);
    }

    /**
     * @param nodeId Node ID.
     * @return Partitions for which given node is primary.
     */
    public Set<Integer> primaryPartitions(UUID nodeId) {
        return aff.primaryPartitions(nodeId, topologyVersion());
    }

    /**
     * @param nodeId Node ID.
     * @param topVer Topology version to calculate affinity.
     * @return Partitions for which given node is primary.
     */
    public Set<Integer> primaryPartitions(UUID nodeId, long topVer) {
        return aff.primaryPartitions(nodeId, topVer);
    }

    /**
     * @param nodeId Node ID.
     * @param topVer Topology version to calculate affinity.
     * @return Partitions for which given node is backup.
     */
    public Set<Integer> backupPartitions(UUID nodeId, long topVer) {
        return aff.backupPartitions(nodeId, topVer);
    }

    /**
     * @return Topology version.
     */
    private long topologyVersion() {
        return cctx.discovery().topologyVersion();
    }
}
