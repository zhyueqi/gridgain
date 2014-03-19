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

package org.gridgain.grid.kernal.processors.affinity;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.affinity.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.deployment.*;
import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.lang.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Affinity utility methods.
 */
class GridAffinityUtils {
    /**
     * Creates a job that will look up {@link GridCacheAffinityKeyMapper} and {@link GridCacheAffinityFunction} on a cache with
     * given name. If they exist, this job will serialize and transfer them together with all deployment information
     * needed to unmarshal objects on remote node. Result is returned as a {@link GridTuple3}, where first object is
     * {@link GridAffinityMessage} for {@link GridCacheAffinityFunction}, second object is {@link GridAffinityMessage} for
     * {@link GridCacheAffinityKeyMapper} and third object is optional {@link GridException} representing deployment
     * exception. If exception field is not null, first two objects must be discarded. If cache with name {@code
     * cacheName} does not exist on a node, the job will return {@code null}.
     *
     * @param cacheName Cache name.
     * @return Affinity job.
     */
    static Callable<GridTuple4<GridAffinityMessage, GridAffinityMessage, Integer, GridException>> affinityJob(
        String cacheName) {
        return new AffinityJob(cacheName);
    }

    /**
     * @param ctx  {@code GridKernalContext} instance which provides deployment manager
     * @param o Object for which deployment should be obtained.
     * @return Deployment object for given instance,
     * @throws GridException If node cannot create deployment for given object.
     */
    private static GridAffinityMessage affinityMessage(GridKernalContext ctx, Object o) throws GridException {
        Class cls = o.getClass();

        GridDeployment dep = ctx.deploy().deploy(cls, cls.getClassLoader());

        if (dep == null)
            throw new GridDeploymentException("Failed to deploy affinity object with class: " + cls.getName());

        return new GridAffinityMessage(
            ctx.config().getMarshaller().marshal(o),
            cls.getName(),
            dep.classLoaderId(),
            dep.deployMode(),
            dep.userVersion(),
            dep.participants());
    }

    /**
     * Unmarshalls transfer object from remote node within a given context.
     *
     * @param ctx Grid kernal context that provides deployment and marshalling services.
     * @param sndNodeId {@link UUID} of the sender node.
     * @param msg Transfer object that contains original serialized object and deployment information.
     * @return Unmarshalled object.
     * @throws GridException If node cannot obtain deployment.
     */
    static Object unmarshall(GridKernalContext ctx, UUID sndNodeId, GridAffinityMessage msg)
        throws GridException {
        GridDeployment dep = ctx.deploy().getGlobalDeployment(
            msg.deploymentMode(),
            msg.sourceClassName(),
            msg.sourceClassName(),
            msg.userVersion(),
            sndNodeId,
            msg.classLoaderId(),
            msg.loaderParticipants(),
            null);

        if (dep == null)
            throw new GridDeploymentException("Failed to obtain affinity object (is peer class loading turned on?): " +
                msg);

        Object src = ctx.config().getMarshaller().unmarshal(msg.source(), dep.classLoader());

        // Resource injection.
        ctx.resource().inject(dep, dep.deployedClass(msg.sourceClassName()), src);

        return src;
    }

    /** Ensure singleton. */
    private GridAffinityUtils() {
        // No-op.
    }

    /**
     *
     */
    @GridInternal
    private static class AffinityJob implements
        Callable<GridTuple4<GridAffinityMessage, GridAffinityMessage, Integer, GridException>>, Externalizable {
        /** */
        @GridInstanceResource
        private Grid grid;

        /** */
        @GridLoggerResource
        private GridLogger log;

        /** */
        private String cacheName;

        /**
         * @param cacheName Cache name.
         */
        private AffinityJob(@Nullable String cacheName) {
            this.cacheName = cacheName;
        }

        /**
         *
         */
        public AffinityJob() {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override public GridTuple4<GridAffinityMessage, GridAffinityMessage, Integer, GridException> call()
            throws Exception {
            assert grid != null;
            assert log != null;

            GridKernal kernal = ((GridKernal)grid);

            GridCache cache = kernal.cachex(cacheName);

            assert cache != null;

            GridKernalContext ctx = kernal.context();

            GridTuple4<GridAffinityMessage, GridAffinityMessage, Integer, GridException> res =
                new GridTuple4<>();

            try {
                res.set1(affinityMessage(ctx, cache.configuration().getAffinityMapper()));
                res.set2(affinityMessage(ctx, cache.configuration().getAffinity()));
                res.set3(cache.configuration().getBackups());
            }
            catch (GridException e) {
                res.set4(e);

                U.error(log, "Failed to transfer affinity.", e);
            }

            return res;
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeString(out, cacheName);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            cacheName = U.readString(in);
        }
    }
}
