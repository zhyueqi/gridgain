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

package org.gridgain.grid.spi.checkpoint.sharedfs;

import org.gridgain.grid.util.typedef.internal.*;
import java.io.*;

/**
 * Wrapper of all checkpoint that are saved to the file system. It
 * extends every checkpoint with expiration time and host name
 * which created this checkpoint.
 * <p>
 * Host name is used by {@link GridSharedFsCheckpointSpi} SPI to give node
 * correct files if it is restarted.
 */
class GridSharedFsCheckpointData implements Serializable {
    /** Checkpoint data. */
    private final byte[] state;

    /** Checkpoint expiration time. */
    private final long expTime;

    /** Host name that created this checkpoint. */
    private final String host;

    /** Checkpoint key. */
    private final String key;

    /**
     * Creates new instance of checkpoint data wrapper.
     *
     * @param state Checkpoint data.
     * @param expTime Checkpoint expiration time in milliseconds.
     * @param host Name of host that created this checkpoint.
     * @param key Key of checkpoint.
     */
    GridSharedFsCheckpointData(byte[] state, long expTime, String host, String key) {
        assert expTime >= 0;
        assert host != null;

        this.state = state;
        this.expTime = expTime;
        this.host = host;
        this.key = key;
    }

    /**
     * Gets checkpoint data.
     *
     * @return Checkpoint data.
     */
    byte[] getState() {
        return state;
    }

    /**
     * Gets checkpoint expiration time.
     *
     * @return Expire time in milliseconds.
     */
    long getExpireTime() {
        return expTime;
    }

    /**
     * Gets checkpoint host name.
     *
     * @return Host name.
     */
    String getHost() {
        return host;
    }

    /**
     * Gets key of checkpoint.
     *
     * @return Key of checkpoint.
     */
    public String getKey() {
        return key;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridSharedFsCheckpointData.class, this);
    }
}
