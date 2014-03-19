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

package org.gridgain.grid.kernal.processors.dr;

import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;

/**
 * DR utility methods.
 */
public class GridDrUtils {
    /** Key used to store {@code GridDrPauseInfo} in system cache. */
    public static final Integer DR_GLOBAL_SYNC = 0;

    /** Maximum amount of data centers. */
    public static final int MAX_DATA_CENTERS = 32;

    /**
     * Read marshalled DR entry.
     *
     * @param in Input.
     * @param dataCenterId Data center ID.
     * @return Marshalled DR entry.
     * @throws IOException If failed.
     */
    public static <K, V> GridDrRawEntry<K, V> readDrEntry(DataInput in, byte dataCenterId) throws IOException {
        byte[] keyBytes = U.readByteArray(in);
        byte[] valBytes = U.readByteArray(in);

        long ttl;
        long expireTime;

        if (in.readBoolean()) {
            ttl = in.readLong();
            expireTime = in.readLong();
        }
        else {
            ttl = 0L;
            expireTime = 0L;
        }

        GridCacheVersion ver = new GridCacheVersion(in.readInt(), in.readLong(), in.readLong(), in.readInt(),
            dataCenterId);

        return new GridDrRawEntry<>(null, keyBytes, null, valBytes, ttl, expireTime, ver);
    }

    /**
     * Write marshalled DR entry.
     *
     * @param out Output.
     * @param entry Entry.
     * @throws IOException If failed.
     */
    public static <K, V> void writeDrEntry(DataOutput out, GridDrRawEntry<K, V> entry) throws IOException {
        assert entry.keyBytes() != null;

        // Write key and value.
        U.writeByteArray(out, entry.keyBytes());
        U.writeByteArray(out, entry.valueBytes());

        // Writ expiration info.
        if (entry.ttl() > 0) {
            out.writeBoolean(true);
            out.writeLong(entry.ttl());
            out.writeLong(entry.expireTime());
        }
        else
            out.writeBoolean(false);

        // Write version.
        out.writeInt(entry.version().topologyVersion());
        out.writeLong(entry.version().globalTime());
        out.writeLong(entry.version().order());
        out.writeInt(entry.version().nodeOrder());
    }

    /**
     * Force singleton.
     */
    private GridDrUtils() {
        // No-op.
    }
}
