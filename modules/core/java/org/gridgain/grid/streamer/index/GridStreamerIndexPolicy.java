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

package org.gridgain.grid.streamer.index;

/**
 * Streamer index policy, which defines how events
 * are tracked within an index.
 *
 * @author @java.author
 * @version @java.version
 */
public enum GridStreamerIndexPolicy {
    /**
     * Do not track events.
     * <p>
     * Only a value, generated by {@link GridStreamerIndexUpdater},
     * will be stored in an index; event objects will be thrown away.
     */
    EVENT_TRACKING_OFF,

    /**
     * Track events.
     * <p>
     * All event objects will stored in an index along with the values,
     * generated by {@link GridStreamerIndexUpdater}.
     */
    EVENT_TRACKING_ON,

    /**
     * Track events with de-duplication.
     * <p>
     * All event objects will stored in an index along with the values,
     * generated by {@link GridStreamerIndexUpdater}. For duplicate (equal)
     * events, only a single event object will be stored, which corresponds
     * to a first event.
     */
    EVENT_TRACKING_ON_DEDUP
}
