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

package org.gridgain.grid.dr;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.dr.cache.receiver.*;
import org.gridgain.grid.dr.cache.sender.*;
import org.gridgain.grid.dr.hub.receiver.*;
import org.gridgain.grid.dr.hub.sender.*;

/**
 * Data center replication (DR) interface.
 * <p>
 * DR is a process of transferring updates from a cache located in one topology to a cache located in another topology.
 * Usual use case for DR is synchronizing cache state between several geographically remote topologies through WAN.
 * <p>
 * DR operates on the following terms:
 * <ul>
 *     <li><b>Data center</b> - particular GridGain topology with unique ID;</li>
 *     <li><b>Sender cache</b> - cache from which data is being replicated;</li>
 *     <li><b>Receiver cache</b> - cache which applies updates from remote data center;</li>
 *     <li><b>Sender hub</b> - node which accumulates updates from sender data nodes and sends that to receiver hub in
 *     remote data center;</li>
 *     <li><b>receiver hub</b> - node which receives updates from remote data center sender hub and applies them to
 *     receiver data nodes.</li>
 * </ul>
 *
 * Cache is considered as sender in case it has {@link GridCacheConfiguration#getDrSenderConfiguration()}, and it is
 * considered as receiver cache in case it has {@link GridCacheConfiguration#getDrReceiverConfiguration()}.
 * <p>
 * Node is considered as sender hub in case it has {@link GridConfiguration#getDrSenderHubConfiguration()},
 * and it is considered as receiver hub in case it has
 * {@link GridConfiguration#getDrReceiverHubConfiguration()}.
 * <p>
 * Particular cache can be both sender and receiver at the same time. Particular node can be both sender and receiver
 * hub and host sender/receiver caches at the same time.
 * <p>
 * This API provides ability to manage DR process on sender cache node as well as get metrics for sender cache,
 * receiver cache, sender hub and receiver hub.
 */
public interface GridDr {
    /**
     * Starts full state transfer for the given sender cache.
     * <p>
     * In case node doesn't have cache with the given name or this cache is not sender cache, an exception will be
     * thrown.
     * <p>
     * Only one state transfer for particular cache and data center is allowed at a time. In case another state
     * transfer for the given cache and data center had been already in progress when this method was called,
     * then no new state transfer for this (cache name, data center) pair will be initiated and returned future
     * will "join" existing state transfer.
     *
     * @param cacheName Sender cache name.
     * @param dataCenterId Remote data center IDs for which full state transfer was requested.
     * @return Future that will be completed when all required data is transferred from sender caches to sender hubs.
     * @throws GridException If state transfer initiation failed.
     */
    public GridFuture<?> senderCacheDrStateTransfer(String cacheName, byte... dataCenterId) throws GridException;

    /**
     * Pauses data center replication for particular sender cache.
     *
     * @param cacheName Sender cache name.
     * @throws GridException If failed to pause replication.
     */
    public void senderCacheDrPause(String cacheName) throws GridException;

    /**
     * Resumes data center replication for particular sender cache.
     *
     * @param cacheName Cache name.
     * @throws GridException If failed to resume replication.
     */
    public void senderCacheDrResume(String cacheName)throws GridException;

    /**
     * Gets sender cache metrics.
     * <p>
     * In case node doesn't have cache with the given name then {@link IllegalArgumentException}
     * will be thrown, and if this cache is not sender cache then {@link IllegalStateException} will be thrown.
     *
     * @param cacheName Sender cache name.
     * @return Sender cache metrics.
     */
    public GridDrSenderCacheMetrics senderCacheMetrics(String cacheName);

    /**
     * Gets receiver cache metrics.
     * <p>
     * In case node doesn't have cache with the given name then {@link IllegalArgumentException}
     * will be thrown, and if this cache is not receiver cache {@link IllegalStateException} will be thrown.
     *
     * @param cacheName Receiver cache name.
     * @return Receiver cache metrics..
     */
    public GridDrReceiverCacheMetrics receiverCacheMetrics(String cacheName);

    /**
     * Gets sender hub incoming data metrics for specified sender cache.
     * <p>
     * In case node is not sender hub then {@link IllegalStateException} will be thrown,
     * and if this sender hub does not work with the given cache {@link IllegalArgumentException} will be thrown.
     *
     * @param cacheName Sender cache name.
     * @return Sender hub incoming data metrics.
     */
    public GridDrSenderHubInMetrics senderHubInMetrics(String cacheName);

    /**
     * Gets sender hub incoming data metrics aggregated across all caches.
     * <p>
     * In case node is not sender hub then {@link IllegalStateException} will be thrown.
     *
     * @return Sender hub incoming data metrics.
     */
    public GridDrSenderHubInMetrics senderHubAggregatedInMetrics();

    /**
     * Gets sender hub outgoing data metrics for specified remote data center ID.
     * <p>
     * In case node is not sender hub then {@link IllegalStateException} will be thrown,
     * and if this sender hub does not work with the given data center {@link IllegalArgumentException}
     * will be thrown.
     *
     * @param dataCenterId Remote data center ID.
     * @return Sender hub outgoing data metrics.
     */
    public GridDrSenderHubOutMetrics senderHubOutMetrics(byte dataCenterId);

    /**
     * Gets sender hub outgoing data metrics aggregated across all remote data centers.
     * <p>
     * In case node is not sender hub then {@link IllegalStateException} will be thrown.
     *
     * @return Sender hub outgoing data metrics.
     */
    public GridDrSenderHubOutMetrics senderHubAggregatedOutMetrics();

    /**
     * Gets receiver hub incoming data metrics for specified remote data center ID.
     * <p>
     * In case node is not receiver hub then {@link IllegalStateException},
     * and if this receiver hub does not work with the given data center {@link IllegalArgumentException}
     * will be thrown.
     *
     * @param dataCenterId Remote data center ID.
     * @return Receiver hub incoming data metrics.
     */
    public GridDrReceiverHubInMetrics receiverHubInMetrics(byte dataCenterId);

    /**
     * Gets receiver hub incoming data metrics aggregated across all remote data centers.
     * <p>
     * In case node is not receiver hub then {@link IllegalStateException} will be thrown.
     *
     * @return Receiver hub incoming data metrics.
     */
    public GridDrReceiverHubInMetrics receiverHubAggregatedInMetrics();

    /**
     * Gets receiver hub outgoing data metrics aggregated across all receiver caches.
     * <p>
     * In case node is not receiver hub then {@link IllegalStateException} will be thrown.
     *
     * @return Receiver hub outgoing data metrics.
     */
    public GridDrReceiverHubOutMetrics receiverHubAggregatedOutMetrics();

    /**
     * Reset all sender hub and receiver hub metrics on this node. In case this node is neither sender hub, nor
     * receiver hub, the method is no-op.
     * <p>
     * Sender and receiver cache metrics must be reset through {@link GridCache#resetMetrics()}.
     */
    public void resetMetrics();
}
