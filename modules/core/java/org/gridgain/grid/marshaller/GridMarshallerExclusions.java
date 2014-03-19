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

package org.gridgain.grid.marshaller;

import org.gridgain.grid.compute.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.executor.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.*;
import org.springframework.context.*;

import javax.management.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Controls what classes should be excluded from marshalling by default.
 */
public final class GridMarshallerExclusions {
    /**
     * Classes that must be included in serialization. All marshallers must
     * included these classes.
     * <p>
     * Note that this list supercedes {@link #EXCL_CLASSES}.
     */
    private static final Class<?>[] INCL_CLASSES = new Class[] {
        // GridGain classes.
        GridLoggerProxy.class,
        GridExecutorService.class
    };

    /** */
    private static final Map<Class<?>, Boolean> cache = new GridBoundedConcurrentLinkedHashMap<>(
        512, 512, 0.75f, 16);

    /**
     * Excluded grid classes from serialization. All marshallers must omit
     * these classes. Fields of these types should be serialized as {@code null}.
     * <p>
     * Note that {@link #INCL_CLASSES} supercedes this list.
     */
    private static final Class<?>[] EXCL_CLASSES = new Class[] {
        // Non-GridGain classes.
        MBeanServer.class,
        ExecutorService.class,
        ApplicationContext.class,
        ClassLoader.class,
        Thread.class,

        // GridGain classes.
        GridLogger.class,
        GridComputeTaskSession.class,
        GridComputeLoadBalancer.class,
        GridComputeJobContext.class,
        GridMarshaller.class,
        GridComponent.class,
        GridComputeTaskContinuousMapper.class
    };

    /**
     * Ensures singleton.
     */
    private GridMarshallerExclusions() {
        // No-op.
    }

    /**
     * Checks given class against predefined set of excluded types.
     *
     * @param cls Class to check.
     * @return {@code true} if class should be excluded, {@code false} otherwise.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private static boolean isExcluded0(Class<?> cls) {
        assert cls != null;

        final Class<?>[] inc = INCL_CLASSES;

        // NOTE: don't use foreach for performance reasons.
        for (int i = 0; i < inc.length; i++)
            if (inc[i].isAssignableFrom(cls))
                return false;

        final Class<?>[] exc = EXCL_CLASSES;

        // NOTE: don't use foreach for performance reasons.
        for (int i = 0; i < exc.length; i++)
            if (exc[i].isAssignableFrom(cls))
                return true;

        return false;
    }

    /**
     * Checks whether or not given class should be excluded from marshalling.
     *
     * @param cls Class to check.
     * @return {@code true} if class should be excluded, {@code false} otherwise.
     */
    public static boolean isExcluded(Class<?> cls) {
        Boolean res = cache.get(cls);

        if (res == null) {
            res = isExcluded0(cls);

            cache.put(cls, res);
        }

        return res;
    }
}
