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

package org.gridgain.grid.kernal.processors.job;

import org.gridgain.grid.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.collision.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.managers.deployment.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.jobmetrics.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.marshaller.*;
import org.gridgain.grid.spi.collision.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import static java.util.concurrent.TimeUnit.*;
import static org.gridgain.grid.GridSystemProperties.*;
import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.GridTopic.*;
import static org.gridgain.grid.kernal.managers.communication.GridIoPolicy.*;
import static org.gridgain.grid.util.ConcurrentLinkedHashMap.QueuePolicy.*;

/**
 * Responsible for all grid job execution and communication.
 */
public class GridJobProcessor extends GridProcessorAdapter {
    /** */
    private static final int FINISHED_JOBS_COUNT = Integer.getInteger(GG_JOBS_HISTORY_SIZE, 10240);

    /** */
    private final GridMarshaller marsh;

    /** */
    private final boolean jobAlwaysActivate;

    /** */
    private final ConcurrentMap<GridUuid, GridJobWorker> activeJobs;

    /** */
    private final ConcurrentMap<GridUuid, GridJobWorker> passiveJobs;

    /** */
    private final ConcurrentMap<GridUuid, GridJobWorker> cancelledJobs =
        new ConcurrentHashMap8<>();

    /** */
    private final Collection<GridUuid> heldJobs = new GridConcurrentHashSet<>();

    /** If value is {@code true}, job was cancelled from future. */
    private final GridBoundedConcurrentLinkedHashMap<GridUuid, Boolean> cancelReqs =
        new GridBoundedConcurrentLinkedHashMap<>(FINISHED_JOBS_COUNT,
            FINISHED_JOBS_COUNT < 128 ? FINISHED_JOBS_COUNT : 128,
            0.75f, 16);

    /** */
    private final GridBoundedConcurrentLinkedHashSet<GridUuid> finishedJobs =
        new GridBoundedConcurrentLinkedHashSet<>(FINISHED_JOBS_COUNT,
            FINISHED_JOBS_COUNT < 128 ? FINISHED_JOBS_COUNT : 128,
            0.75f, 256, PER_SEGMENT_Q);

    /** */
    private final GridJobEventListener evtLsnr;

    /** */
    private final GridMessageListener cancelLsnr;

    /** */
    private final GridMessageListener jobExecLsnr;

    /** */
    private final GridLocalEventListener discoLsnr;

    /** Needed for statistics. */
    private final LongAdder canceledJobsCnt = new LongAdder();

    /** Needed for statistics. */
    private final LongAdder finishedJobsCnt = new LongAdder();

    /** Needed for statistics. */
    private final LongAdder startedJobsCnt = new LongAdder();

    /** Needed for statistics. */
    private final LongAdder rejectedJobsCnt = new LongAdder();

    /** Total job execution time (unaccounted for in metrics). */
    private final LongAdder finishedJobsTime = new LongAdder();

    /** Maximum job execution time for finished jobs. */
    private final GridAtomicLong maxFinishedJobsTime = new GridAtomicLong();

    /** */
    private final AtomicLong metricsLastUpdateTstamp = new AtomicLong();

    /** */
    private boolean stopping;

    /** */
    private boolean cancelOnStop;

    /** */
    private final long metricsUpdateFreq;

    /** */
    private final GridSpinReadWriteLock rwLock = new GridSpinReadWriteLock();

    /** Topic ID generator. */
    private final AtomicLong topicIdGen = new AtomicLong();

    /** */
    private final GridJobHoldListener holdLsnr = new JobHoldListener();

    /** */
    private final ThreadLocal<Boolean> handlingCollision = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return false;
        }
    };

    /** Internal task flag. */
    private final GridThreadLocal<Boolean> internal = new GridThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return false;
        }
    };

    /**
     * @param ctx Kernal context.
     */
    public GridJobProcessor(GridKernalContext ctx) {
        super(ctx);

        marsh = ctx.config().getMarshaller();

        // Collision manager is already started and is fully functional.
        jobAlwaysActivate = !ctx.collision().enabled();

        metricsUpdateFreq = ctx.config().getMetricsUpdateFrequency();

        activeJobs = jobAlwaysActivate ? new ConcurrentHashMap8<GridUuid, GridJobWorker>() :
            new JobsMap(1024, 0.75f, 256);

        passiveJobs = jobAlwaysActivate ? null : new JobsMap(1024, 0.75f, 256);

        evtLsnr = new JobEventListener();
        cancelLsnr = new JobCancelListener();
        jobExecLsnr = new JobExecutionListener();
        discoLsnr = new JobDiscoveryListener();
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        if (metricsUpdateFreq < -1)
            throw new GridException("Invalid value for 'metricsUpdateFrequency' configuration property " +
                "(should be greater than or equals to -1): " + metricsUpdateFreq);

        if (metricsUpdateFreq == -1)
            U.warn(log, "Job metrics are disabled (use with caution).");

        if (!jobAlwaysActivate)
            ctx.collision().setCollisionExternalListener(new CollisionExternalListener());

        GridIoManager ioMgr = ctx.io();

        ioMgr.addMessageListener(TOPIC_JOB_CANCEL, cancelLsnr);
        ioMgr.addMessageListener(TOPIC_JOB, jobExecLsnr);

        ctx.event().addLocalEventListener(discoLsnr, EVT_NODE_FAILED, EVT_NODE_LEFT, EVT_NODE_METRICS_UPDATED);

        if (log.isDebugEnabled())
            log.debug("Job processor started.");
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) {
        // Clear collections.
        activeJobs.clear();
        cancelledJobs.clear();
        cancelReqs.clear();

        if (log.isDebugEnabled())
            log.debug("Job processor stopped.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        // Stop receiving new requests and sending responses.
        GridIoManager commMgr = ctx.io();

        commMgr.removeMessageListener(TOPIC_JOB, jobExecLsnr);
        commMgr.removeMessageListener(TOPIC_JOB_CANCEL, cancelLsnr);

        if (!jobAlwaysActivate)
            // Ignore external collision events.
            ctx.collision().unsetCollisionExternalListener();

        rwLock.writeLock();

        try {
            stopping = true;

            cancelOnStop = cancel;
        }
        finally {
            rwLock.writeUnlock();
        }

        // Rejected jobs.
        if (!jobAlwaysActivate) {
            for (GridJobWorker job : passiveJobs.values())
                if (passiveJobs.remove(job.getJobId(), job))
                    rejectJob(job, false);
        }

        // Cancel only if we force grid to stop
        if (cancel) {
            for (GridJobWorker job : activeJobs.values()) {
                job.onStopping();

                cancelJob(job, false);
            }
        }

        U.join(activeJobs.values(), log);
        U.join(cancelledJobs.values(), log);

        // Ignore topology changes.
        ctx.event().removeLocalEventListener(discoLsnr);

        if (log.isDebugEnabled())
            log.debug("Finished executing job processor onKernalStop() callback.");
    }

    /**
     * Gets active job.
     *
     * @param jobId Job ID.
     * @return Active job.
     */
    @Nullable public GridJobWorker activeJob(GridUuid jobId) {
        assert jobId != null;

        return activeJobs.get(jobId);
    }

    /**
     * @return {@code True} if running internal task.
     */
    public boolean internal() {
        return internal.get();
    }

    /**
     * Sets internal task flag.
     *
     * @param internal {@code True} if running internal task.
     */
    void internal(boolean internal) {
        this.internal.set(internal);
    }

    /**
     * @param job Rejected job.
     * @param sndReply {@code True} to send reply.
     */
    private void rejectJob(GridJobWorker job, boolean sndReply) {
        GridException e = new GridComputeExecutionRejectedException("Job was cancelled before execution [taskSesId=" +
            job.getSession().getId() + ", jobId=" + job.getJobId() + ", job=" + job.getJob() + ']');

        job.finishJob(null, e, sndReply);
    }

    /**
     * @param job Canceled job.
     * @param sysCancel {@code True} if job has been cancelled from system and no response needed.
     */
    private void cancelJob(GridJobWorker job, boolean sysCancel) {
        boolean isCancelled = job.isCancelled();

        // We don't increment number of cancelled jobs if it
        // was already cancelled.
        if (!job.isInternal() && !isCancelled)
            canceledJobsCnt.increment();

        job.cancel(sysCancel);
    }

    /**
     * @param dep Deployment to release.
     */
    private void release(GridDeployment dep) {
        dep.release();

        if (dep.obsolete())
            ctx.resource().onUndeployed(dep);
    }

    /**
     * @param ses Session.
     * @param attrs Attributes.
     * @throws GridException If failed.
     */
    public void setAttributes(GridJobSessionImpl ses, Map<?, ?> attrs) throws GridException {
        assert ses.isFullSupport();

        long timeout = ses.getEndTime() - U.currentTimeMillis();

        if (timeout <= 0) {
            U.warn(log, "Task execution timed out (remote session attributes won't be set): " + ses);

            return;
        }

        if (log.isDebugEnabled())
            log.debug("Setting session attribute(s) from job: " + ses);

        GridNode taskNode = ctx.discovery().node(ses.getTaskNodeId());

        if (taskNode == null)
            throw new GridException("Node that originated task execution has left grid: " +
                ses.getTaskNodeId());

        boolean loc = ctx.localNodeId().equals(taskNode.id()) && !ctx.config().isMarshalLocalJobs();

        GridTaskSessionRequest req = new GridTaskSessionRequest(ses.getId(), ses.getJobId(),
            loc ? null : marsh.marshal(attrs), attrs);

        Object topic = TOPIC_TASK.topic(ses.getJobId(), ctx.discovery().localNode().id());

        // Always go through communication to preserve order.
        ctx.io().sendOrderedMessage(
            taskNode,
            topic, // Job topic.
            ctx.io().nextMessageId(topic, taskNode.id()),
            req,
            SYSTEM_POOL,
            timeout,
            false);
    }

    /**
     * @param ses Session.
     * @return Siblings.
     * @throws GridException If failed.
     */
    public Collection<GridComputeJobSibling> requestJobSiblings(final GridComputeTaskSession ses) throws GridException {
        assert ses != null;

        final UUID taskNodeId = ses.getTaskNodeId();

        GridNode taskNode = ctx.discovery().node(taskNodeId);

        if (taskNode == null)
            throw new GridException("Node that originated task execution has left grid: " + taskNodeId);

        // Tuple: error message-response.
        final GridBiTuple<String, GridJobSiblingsResponse> t = F.t2();

        final Lock lock = new ReentrantLock();
        final Condition cond = lock.newCondition();

        GridMessageListener msgLsnr = new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg) {
                String err = null;
                GridJobSiblingsResponse res = null;

                if (!(msg instanceof GridJobSiblingsResponse))
                    err = "Received unexpected message: " + msg;
                else if (!nodeId.equals(taskNodeId))
                    err = "Received job siblings response from unexpected node [taskNodeId=" + taskNodeId +
                        ", nodeId=" + nodeId + ']';
                else
                    // Sender and message type are fine.
                    res = (GridJobSiblingsResponse)msg;

                if (res.jobSiblings() == null) {
                    try {
                        res.unmarshalSiblings(marsh);
                    }
                    catch (GridException e) {
                        U.error(log, "Failed to unmarshal job siblings.", e);

                        err = e.getMessage();
                    }
                }

                lock.lock();

                try {
                    if (t.isEmpty()) {
                        t.set(err, res);

                        cond.signalAll();
                    }
                }
                finally {
                    lock.unlock();
                }
            }
        };

        GridLocalEventListener discoLsnr = new GridLocalEventListener() {
            @Override public void onEvent(GridEvent evt) {
                assert evt instanceof GridDiscoveryEvent &&
                    (evt.type() == EVT_NODE_FAILED || evt.type() == EVT_NODE_LEFT) : "Unexpected event: " + evt;

                GridDiscoveryEvent discoEvt = (GridDiscoveryEvent)evt;

                if (taskNodeId.equals(discoEvt.eventNodeId())) {
                    lock.lock();

                    try {
                        if (t.isEmpty()) {
                            t.set("Node that originated task execution has left grid: " + taskNodeId, null);

                            cond.signalAll();
                        }
                    }
                    finally {
                        lock.unlock();
                    }
                }
            }
        };

        boolean loc = ctx.localNodeId().equals(taskNodeId);

        // 1. Create unique topic name.
        Object topic = TOPIC_JOB_SIBLINGS.topic(ses.getId(), topicIdGen.getAndIncrement());

        try {
            // 2. Register listener.
            ctx.io().addMessageListener(topic, msgLsnr);

            // 3. Send message.
            ctx.io().send(taskNode, TOPIC_JOB_SIBLINGS,
                new GridJobSiblingsRequest(ses.getId(),
                    loc ? topic : null,
                    loc ? null : marsh.marshal(topic)),
                SYSTEM_POOL);

            // 4. Listen to discovery events.
            ctx.event().addLocalEventListener(discoLsnr, EVT_NODE_FAILED, EVT_NODE_LEFT);

            // 5. Check whether node has left before disco listener has been installed.
            taskNode = ctx.discovery().node(taskNodeId);

            if (taskNode == null)
                throw new GridException("Node that originated task execution has left grid: " + taskNodeId);

            // 6. Wait for result.
            lock.lock();

            try {
                long netTimeout = ctx.config().getNetworkTimeout();

                if (t.isEmpty())
                    cond.await(netTimeout, MILLISECONDS);

                if (t.isEmpty())
                    throw new GridException("Timed out waiting for job siblings (consider increasing" +
                        "'networkTimeout' configuration property) [ses=" + ses + ", netTimeout=" + netTimeout + ']');

                // Error is set?
                if (t.get1() != null)
                    throw new GridException(t.get1());
                else
                    // Return result
                    return t.get2().jobSiblings();
            }
            catch (InterruptedException e) {
                throw new GridException("Interrupted while waiting for job siblings response: " + ses, e);
            }
            finally {
                lock.unlock();
            }
        }
        finally {
            ctx.io().removeMessageListener(topic, msgLsnr);
            ctx.event().removeLocalEventListener(discoLsnr);
        }
    }

    /**
     * Notify processor that master leave aware handler must be invoked on all jobs with the given session ID.
     *
     * @param sesId Session ID.
     */
    public void masterLeaveLocal(GridUuid sesId) {
        assert sesId != null;

        for (GridJobWorker job : activeJobs.values())
            if (job.getSession().getId().equals(sesId))
                job.onMasterNodeLeft();
    }

    /**
     * @param sesId Session ID.
     * @param jobId Job ID.
     * @param sys System flag.
     */
    public void cancelJob(@Nullable final GridUuid sesId, @Nullable final GridUuid jobId, final boolean sys) {
        assert sesId != null || jobId != null;

        rwLock.readLock();

        try {
            if (stopping && cancelOnStop) {
                if (log.isDebugEnabled())
                    log.debug("Received job cancellation request while stopping grid with cancellation " +
                        "(will ignore) [sesId=" + sesId + ", jobId=" + jobId + ", sys=" + sys + ']');

                return;
            }

            // Put either job ID or session ID (they are unique).
            cancelReqs.putIfAbsent(jobId != null ? jobId : sesId, sys);

            GridPredicate<GridJobWorker> idsMatch = new P1<GridJobWorker>() {
                @Override public boolean apply(GridJobWorker e) {
                    return sesId != null ?
                        jobId != null ?
                            e.getSession().getId().equals(sesId) && e.getJobId().equals(jobId) :
                            e.getSession().getId().equals(sesId) :
                        e.getJobId().equals(jobId);
                }
            };

            // If we don't have jobId then we have to iterate
            if (jobId == null) {
                if (!jobAlwaysActivate) {
                    // If job gets removed from passive jobs it never gets activated.
                    F.forEach(passiveJobs.values(), new CI1<GridJobWorker>() {
                        @Override public void apply(GridJobWorker job) {
                            cancelPassiveJob(job);
                        }
                    }, idsMatch);
                }

                F.forEach(activeJobs.values(), new CI1<GridJobWorker>() {
                    @Override public void apply(GridJobWorker job) {
                        cancelActiveJob(job, sys);
                    }
                }, idsMatch);
            }
            else {
                if (!jobAlwaysActivate) {
                    GridJobWorker passiveJob = passiveJobs.get(jobId);

                    if (passiveJob != null && idsMatch.apply(passiveJob) && cancelPassiveJob(passiveJob))
                        return;
                }

                GridJobWorker activeJob = activeJobs.get(jobId);

                if (activeJob != null && idsMatch.apply(activeJob))
                    cancelActiveJob(activeJob,  sys);
            }
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * Tries to cancel passive job. No-op if job is not in 'passive' state.
     *
     * @param job Job to cancel.
     * @return {@code True} if succeeded.
     */
    private boolean cancelPassiveJob(GridJobWorker job) {
        assert !jobAlwaysActivate;

        if (passiveJobs.remove(job.getJobId(), job)) {
            if (log.isDebugEnabled())
                log.debug("Job has been cancelled before activation: " + job);

            canceledJobsCnt.increment();

            return true;
        }

        return false;
    }

    /**
     * Tries to cancel active job. No-op if job is not in 'active' state.
     *
     * @param job Job to cancel.
     * @param sys Flag indicating whether this is a system cancel.
     */
    private void cancelActiveJob(GridJobWorker job, boolean sys) {
        if (activeJobs.remove(job.getJobId(), job)) {
            cancelledJobs.put(job.getJobId(), job);

            if (finishedJobs.contains(job.getJobId()))
                // Job has finished concurrently.
                cancelledJobs.remove(job.getJobId(), job);
            else
                // No reply, since it is not cancel from collision.
                cancelJob(job, sys);
        }
    }

    /**
     * Handles collisions.
     * <p>
     * In most cases this method should be called from main read lock
     * to avoid jobs activation after node stop has started.
     */
    private void handleCollisions() {
        assert !jobAlwaysActivate;

        if (handlingCollision.get()) {
            if (log.isDebugEnabled())
                log.debug("Skipping recursive collision handling.");

            return;
        }

        handlingCollision.set(Boolean.TRUE);

        try {
            if (log.isDebugEnabled())
                log.debug("Before handling collisions.");

            // Invoke collision SPI.
            ctx.collision().onCollision(
                // Passive jobs view.
                new AbstractCollection<GridCollisionJobContext>() {
                    @NotNull @Override public Iterator<GridCollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = passiveJobs.values().iterator();

                        return new Iterator<GridCollisionJobContext>() {
                            @Override public boolean hasNext() {
                                return iter.hasNext();
                            }

                            @Override public GridCollisionJobContext next() {
                                return new CollisionJobContext(iter.next(), true);
                            }

                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override public int size() {
                        return passiveJobs.size();
                    }
                },

                // Active jobs view.
                new AbstractCollection<GridCollisionJobContext>() {
                    @NotNull @Override public Iterator<GridCollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = activeJobs.values().iterator();

                        return new Iterator<GridCollisionJobContext>() {
                            private GridJobWorker w;

                            {
                                advance();
                            }

                            /**
                             *
                             */
                            void advance() {
                                assert w == null;

                                while(iter.hasNext()) {
                                    GridJobWorker w0 = iter.next();

                                    assert !w0.isInternal();

                                    if (!w0.held()) {
                                        w = w0;

                                        break;
                                    }
                                }
                            }

                            @Override public boolean hasNext() {
                                return w != null;
                            }

                            @Override public GridCollisionJobContext next() {
                                if (w == null)
                                    throw new NoSuchElementException();

                                GridCollisionJobContext ret = new CollisionJobContext(w, false);

                                w = null;

                                advance();

                                return ret;
                            }

                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override public int size() {
                        int ret = activeJobs.size() - heldJobs.size();

                        return ret > 0 ? ret : 0;
                    }
                },

                // Held jobs view.
                new AbstractCollection<GridCollisionJobContext>() {
                    @NotNull @Override public Iterator<GridCollisionJobContext> iterator() {
                        final Iterator<GridJobWorker> iter = activeJobs.values().iterator();

                        return new Iterator<GridCollisionJobContext>() {
                            private GridJobWorker w;

                            {
                                advance();
                            }

                            /**
                             *
                             */
                            void advance() {
                                assert w == null;

                                while(iter.hasNext()) {
                                    GridJobWorker w0 = iter.next();

                                    assert !w0.isInternal();

                                    if (w0.held()) {
                                        w = w0;

                                        break;
                                    }
                                }
                            }

                            @Override public boolean hasNext() {
                                return w != null;
                            }

                            @Override public GridCollisionJobContext next() {
                                if (w == null)
                                    throw new NoSuchElementException();

                                GridCollisionJobContext ret = new CollisionJobContext(w, false);

                                w = null;

                                advance();

                                return ret;
                            }

                            @Override public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override public int size() {
                        return heldJobs.size();
                    }
                });

            if (metricsUpdateFreq > -1L)
                updateJobMetrics();
        }
        finally {
            handlingCollision.set(Boolean.FALSE);
        }
    }

    /**
     *
     */
    private void updateJobMetrics() {
        assert metricsUpdateFreq > -1L;

        if (metricsUpdateFreq == 0L)
            updateJobMetrics0();
        else {
            long now = U.currentTimeMillis();
            long lastUpdate = metricsLastUpdateTstamp.get();

            if (now - lastUpdate > metricsUpdateFreq && metricsLastUpdateTstamp.compareAndSet(lastUpdate, now))
                updateJobMetrics0();
        }
    }

    /**
     *
     */
    private void updateJobMetrics0() {
        assert metricsUpdateFreq > -1L;

        GridJobMetricsSnapshot m = new GridJobMetricsSnapshot();

        m.setRejectJobs((int)rejectedJobsCnt.sumThenReset());
        m.setStartedJobs((int)startedJobsCnt.sumThenReset());

        // Iterate over active jobs to determine max execution time.
        int cnt = 0;

        for (GridJobWorker jobWorker : activeJobs.values()) {
            assert !jobWorker.isInternal();

            cnt++;

            if (!jobWorker.held()) {
                long execTime = jobWorker.getExecuteTime();

                if (execTime > m.getMaximumExecutionTime())
                    m.setMaximumExecutionTime(execTime);
            }
        }

        m.setActiveJobs(cnt);

        cnt = 0;

        // Do this only if collision SPI is used. Otherwise, 0 is correct value
        // for passive jobs count and max wait time.
        if (!jobAlwaysActivate) {
            // Iterate over passive jobs to determine max queued time.
            for (GridJobWorker jobWorker : passiveJobs.values()) {
                // We don't expect that there are any passive internal jobs.
                assert !jobWorker.isInternal();

                cnt++;

                long queuedTime = jobWorker.getQueuedTime();

                if (queuedTime > m.getMaximumWaitTime())
                    m.setMaximumWaitTime(queuedTime);

                m.setWaitTime(m.getWaitTime() + jobWorker.getQueuedTime());
            }

            m.setPassiveJobs(cnt);
        }

        m.setFinishedJobs((int)finishedJobsCnt.sumThenReset());
        m.setExecutionTime(finishedJobsTime.sumThenReset());
        m.setCancelJobs((int)canceledJobsCnt.sumThenReset());

        long maxFinishedTime = maxFinishedJobsTime.getAndSet(0);

        if (maxFinishedTime > m.getMaximumExecutionTime())
            m.setMaximumExecutionTime(maxFinishedTime);

        // CPU load.
        m.setCpuLoad(ctx.discovery().metrics().getCurrentCpuLoad());

        ctx.jobMetric().addSnapshot(m);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings("TooBroadScope")
    public void processJobExecuteRequest(UUID nodeId, final GridJobExecuteRequest req) {
        if (log.isDebugEnabled())
            log.debug("Received job request message [req=" + req + ", nodeId=" + nodeId + ']');

        GridJobWorker job = null;

        rwLock.readLock();

        try {
            if (stopping) {
                if (log.isDebugEnabled())
                    log.debug("Received job execution request while stopping this node (will ignore): " + req);

                return;
            }

            long endTime = req.getCreateTime() + req.getTimeout();

            // Account for overflow.
            if (endTime < 0)
                endTime = Long.MAX_VALUE;

            GridDeployment tmpDep = req.isForceLocalDeployment() ?
                ctx.deploy().getLocalDeployment(req.getTaskClassName()) :
                ctx.deploy().getGlobalDeployment(
                    req.getDeploymentMode(),
                    req.getTaskName(),
                    req.getTaskClassName(),
                    req.getUserVersion(),
                    nodeId,
                    req.getClassLoaderId(),
                    req.getLoaderParticipants(),
                    null);

            if (tmpDep == null) {
                if (log.isDebugEnabled())
                    log.debug("Checking local tasks...");

                // Check local tasks.
                for (Map.Entry<String, GridDeployment> d : ctx.task().getUsedDeploymentMap().entrySet()) {
                    if (d.getValue().classLoaderId().equals(req.getClassLoaderId())) {
                        assert d.getValue().local();

                        tmpDep = d.getValue();

                        break;
                    }
                }
            }

            final GridDeployment dep = tmpDep;

            if (log.isDebugEnabled())
                log.debug("Deployment: " + dep);

            boolean releaseDep = true;

            try {
                if (dep != null && dep.acquire()) {
                    GridJobSessionImpl jobSes;
                    GridJobContextImpl jobCtx;

                    try {
                        List<GridComputeJobSibling> siblings = null;

                        if (!req.isDynamicSiblings()) {
                            Collection<GridComputeJobSibling> siblings0 = req.getSiblings();

                            if (siblings0 == null) {
                                assert req.getSiblingsBytes() != null;

                                siblings0 = marsh.unmarshal(req.getSiblingsBytes(), null);
                            }

                            siblings = new ArrayList<>(siblings0);
                        }

                        Map<Object, Object> sesAttrs = null;

                        if (req.isSessionFullSupport()) {
                            sesAttrs = req.getSessionAttributes();

                            if (sesAttrs == null)
                                sesAttrs = marsh.unmarshal(req.getSessionAttributesBytes(),
                                    dep.classLoader());
                        }

                        // Note that we unmarshal session/job attributes here with proper class loader.
                        GridTaskSessionImpl taskSes = ctx.session().createTaskSession(
                            req.getSessionId(),
                            nodeId,
                            req.getTaskName(),
                            dep,
                            req.getTaskClassName(),
                            req.topology(),
                            req.getStartTaskTime(),
                            endTime,
                            siblings,
                            sesAttrs,
                            req.isSessionFullSupport());

                        taskSes.setCheckpointSpi(req.getCheckpointSpi());
                        taskSes.setClassLoader(dep.classLoader());

                        jobSes = new GridJobSessionImpl(ctx, taskSes, req.getJobId());

                        Map<? extends Serializable, ? extends Serializable> jobAttrs = req.getJobAttributes();

                        if (jobAttrs == null)
                            jobAttrs = marsh.unmarshal(req.getJobAttributesBytes(), dep.classLoader());

                        jobCtx = new GridJobContextImpl(ctx, req.getJobId(), jobAttrs);
                    }
                    catch (GridException e) {
                        GridException ex = new GridException("Failed to deserialize task attributes [taskName=" +
                            req.getTaskName() + ", taskClsName=" + req.getTaskClassName() + ", codeVer=" +
                            req.getUserVersion() + ", taskClsLdr=" + dep.classLoader() + ']');

                        U.error(log, ex.getMessage(), e);

                        handleException(nodeId, req, ex, endTime);

                        return;
                    }

                    job = new GridJobWorker(
                        ctx,
                        dep,
                        req.getCreateTime(),
                        jobSes,
                        jobCtx,
                        req.getJobBytes(),
                        req.getJob(),
                        nodeId,
                        req.isInternal(),
                        evtLsnr,
                        holdLsnr);

                    jobCtx.job(job);

                    // If exception occurs on job initialization, deployment is released in job listener.
                    releaseDep = false;

                    if (job.initialize(dep, dep.deployedClass(req.getTaskClassName()))) {
                        // Internal jobs will always be executed synchronously.
                        if (job.isInternal()) {
                            // This is an internal job and can be executed inside busy lock
                            // since job is expected to be short.
                            // This is essential for proper stop without races.
                            job.run();

                            // No execution outside lock.
                            job = null;
                        }
                        else if (jobAlwaysActivate) {
                            if (onBeforeActivateJob(job)) {
                                if (ctx.localNodeId().equals(nodeId)) {
                                    // Always execute in another thread for local node.
                                    executeAsync(job);

                                    // No sync execution.
                                    job = null;
                                }
                                else if (metricsUpdateFreq > -1L)
                                    // Job will be executed synchronously.
                                    startedJobsCnt.increment();
                            }
                            else
                                // Job has been cancelled.
                                // Set to null, to avoid sync execution.
                                job = null;
                        }
                        else {
                            GridJobWorker old = passiveJobs.putIfAbsent(job.getJobId(), job);

                            if (old == null)
                                handleCollisions();
                            else
                                U.error(log, "Received computation request with duplicate job ID (could be " +
                                    "network malfunction, source node may hang if task timeout was not set) " +
                                    "[srcNode=" + nodeId +
                                    ", jobId=" + req.getJobId() + ", sesId=" + req.getSessionId() +
                                    ", locNodeId=" + ctx.localNodeId() + ']');

                            // No sync execution.
                            job = null;
                        }
                    }
                    else
                        // Job was not initialized, no execution.
                        job = null;
                }
                else {
                    // Deployment is null.
                    GridException ex = new GridDeploymentException("Task was not deployed or was redeployed since " +
                        "task execution [taskName=" + req.getTaskName() + ", taskClsName=" + req.getTaskClassName() +
                        ", codeVer=" + req.getUserVersion() + ", clsLdrId=" + req.getClassLoaderId() +
                        ", seqNum=" + req.getClassLoaderId().localId() + ", depMode=" + req.getDeploymentMode() +
                        ", dep=" + dep + ']');

                    U.error(log, ex.getMessage(), ex);

                    handleException(nodeId, req, ex, endTime);
                }
            }
            finally {
                if (dep != null && releaseDep)
                    release(dep);
            }
        }
        finally {
            rwLock.readUnlock();
        }

        if (job != null)
            job.run();
    }

    /**
     * @param jobWorker Worker.
     * @return {@code True} if job has not been cancelled and should be activated.
     */
    private boolean onBeforeActivateJob(GridJobWorker jobWorker) {
        assert jobWorker != null;

        activeJobs.put(jobWorker.getJobId(), jobWorker);

        // Check if job has been concurrently cancelled.
        Boolean sysCancelled = cancelReqs.get(jobWorker.getSession().getId());

        if (sysCancelled == null)
            sysCancelled = cancelReqs.get(jobWorker.getJobId());

        if (sysCancelled != null) {
            // Job has been concurrently cancelled.
            // Remove from active jobs.
            activeJobs.remove(jobWorker.getJobId(), jobWorker);

            // Even if job has been removed from another thread, we need to reject it
            // here since job has never been executed.
            GridException e2 = new GridComputeExecutionRejectedException(
                "Job was cancelled before execution [jobSes=" + jobWorker.
                    getSession() + ", job=" + jobWorker.getJob() + ']');

            jobWorker.finishJob(null, e2, !sysCancelled);

            return false;
        }

        // Job has not been cancelled and should be activated.
        // However we need to check if master is alive before job will get
        // its runner thread for proper master leave handling.
        if (ctx.discovery().node(jobWorker.getTaskNodeId()) == null &&
            activeJobs.remove(jobWorker.getJobId(), jobWorker)) {
            // Add to cancelled jobs.
            cancelledJobs.put(jobWorker.getJobId(), jobWorker);

            if (!jobWorker.onMasterNodeLeft()) {
                U.warn(log, "Job is being cancelled because master task node left grid " +
                    "(as there is no one waiting for results, job will not be failed over): " +
                    jobWorker.getJobId());

                cancelJob(jobWorker, true);
            }
        }

        return true;
    }

    /**
     * @param jobWorker Job worker.
     * @return {@code True} if job has been submitted to pool.
     */
    private boolean executeAsync(GridJobWorker jobWorker) {
        try {
            ctx.config().getExecutorService().execute(jobWorker);

            if (metricsUpdateFreq > -1L)
                startedJobsCnt.increment();

            return true;
        }
        catch (RejectedExecutionException e) {
            // Remove from active jobs.
            activeJobs.remove(jobWorker.getJobId(), jobWorker);

            // Even if job was removed from another thread, we need to reject it
            // here since job has never been executed.
            GridException e2 = new GridComputeExecutionRejectedException("Job has been rejected " +
                "[jobSes=" + jobWorker.getSession() + ", job=" + jobWorker.getJob() + ']', e);

            if (metricsUpdateFreq > -1L)
                rejectedJobsCnt.increment();

            jobWorker.finishJob(null, e2, true);
        }

        return false;
    }

    /**
     * Handles errors that happened prior to job creation.
     *
     * @param nodeId Sender ID.
     * @param req Job execution request.
     * @param ex Exception that happened.
     * @param endTime Job end time.
     */
    private void handleException(UUID nodeId, GridJobExecuteRequest req, GridException ex, long endTime) {
        UUID locNodeId = ctx.localNodeId();

        GridNode sndNode = ctx.discovery().node(nodeId);

        if (sndNode == null) {
            U.warn(log, "Failed to reply to sender node because it left grid [nodeId=" + nodeId +
                ", jobId=" + req.getJobId() + ']');

            if (ctx.event().isRecordable(EVT_JOB_FAILED)) {
                GridJobEvent evt = new GridJobEvent();

                evt.jobId(req.getJobId());
                evt.message("Job reply failed (original task node left grid): " + req.getJobId());
                evt.nodeId(locNodeId);
                evt.taskName(req.getTaskName());
                evt.taskClassName(req.getTaskClassName());
                evt.taskSessionId(req.getSessionId());
                evt.type(EVT_JOB_FAILED);
                evt.taskNodeId(nodeId);

                // Record job reply failure.
                ctx.event().record(evt);
            }

            return;
        }

        try {
            boolean loc = ctx.localNodeId().equals(sndNode.id()) && !ctx.config().isMarshalLocalJobs();

            GridJobExecuteResponse jobRes = new GridJobExecuteResponse(
                locNodeId,
                req.getSessionId(),
                req.getJobId(),
                loc ? null : marsh.marshal(ex),
                ex,
                loc ? null : marsh.marshal(null),
                null,
                loc ? null : marsh.marshal(null),
                null,
                false);

            if (req.isSessionFullSupport()) {
                // Send response to designated job topic.
                // Always go through communication to preserve order,
                // if attributes are enabled.
                // Job response topic.
                Object topic = TOPIC_TASK.topic(req.getJobId(), locNodeId);

                long timeout = endTime - U.currentTimeMillis();

                if (timeout <= 0)
                    // Ignore the actual timeout and send response anyway.
                    timeout = 1;

                // Send response to designated job topic.
                // Always go through communication to preserve order.
                long msgId = ctx.io().nextMessageId(topic, sndNode.id());

                ctx.io().removeMessageId(topic);

                ctx.io().sendOrderedMessage(
                    sndNode,
                    topic,
                    msgId,
                    jobRes,
                    SYSTEM_POOL,
                    timeout,
                    false);
            }
            else if (ctx.localNodeId().equals(sndNode.id()))
                ctx.task().processJobExecuteResponse(ctx.localNodeId(), jobRes);
            else
                // Send response to common topic as unordered message.
                ctx.io().send(sndNode, TOPIC_TASK, jobRes, SYSTEM_POOL);
        }
        catch (GridException e) {
            // The only option here is to log, as we must assume that resending will fail too.
            if (isDeadNode(nodeId))
                // Avoid stack trace for left nodes.
                U.error(log, "Failed to reply to sender node because it left grid [nodeId=" + nodeId +
                    ", jobId=" + req.getJobId() + ']');
            else {
                assert sndNode != null;

                U.error(log, "Error sending reply for job [nodeId=" + sndNode.id() + ", jobId=" +
                    req.getJobId() + ']', e);
            }

            if (ctx.event().isRecordable(EVT_JOB_FAILED)) {
                GridJobEvent evt = new GridJobEvent();

                evt.jobId(req.getJobId());
                evt.message("Failed to send reply for job: " + req.getJobId());
                evt.nodeId(locNodeId);
                evt.taskName(req.getTaskName());
                evt.taskClassName(req.getTaskClassName());
                evt.taskSessionId(req.getSessionId());
                evt.type(EVT_JOB_FAILED);
                evt.taskNodeId(nodeId);

                // Record job reply failure.
                ctx.event().record(evt);
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "RedundantCast"})
    private void processTaskSessionRequest(UUID nodeId, GridTaskSessionRequest req) {
        rwLock.readLock();

        try {
            if (stopping) {
                if (log.isDebugEnabled())
                    log.debug("Received job session request while stopping grid (will ignore): " + req);

                return;
            }

            GridTaskSessionImpl ses = ctx.session().getSession(req.getSessionId());

            if (ses == null) {
                if (log.isDebugEnabled())
                    log.debug("Received job session request for non-existing session: " + req);

                return;
            }

            boolean loc = ctx.localNodeId().equals(nodeId) && !ctx.config().isMarshalLocalJobs();

            Map<?, ?> attrs = loc ? req.getAttributes() :
                (Map<?, ?>)marsh.unmarshal(req.getAttributesBytes(), ses.getClassLoader());

            if (ctx.event().isRecordable(EVT_TASK_SESSION_ATTR_SET)) {
                GridTaskEvent evt = new GridTaskEvent();

                evt.message("Changed attributes: " + attrs);
                evt.nodeId(ctx.discovery().localNode().id());
                evt.taskName(ses.getTaskName());
                evt.taskClassName(ses.getTaskClassName());
                evt.taskSessionId(ses.getId());
                evt.type(EVT_TASK_SESSION_ATTR_SET);

                ctx.event().record(evt);
            }

            synchronized (ses) {
                ses.setInternal(attrs);
            }
        }
        catch (GridException e) {
            U.error(log, "Failed to deserialize session attributes.", e);
        }
        finally {
            rwLock.readUnlock();
        }
    }

    /**
     * Checks whether node is alive or dead.
     *
     * @param uid UID of node to check.
     * @return {@code true} if node is dead, {@code false} is node is alive.
     */
    private boolean isDeadNode(UUID uid) {
        return ctx.discovery().node(uid) == null || !ctx.discovery().pingNode(uid);
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> Job processor memory stats [grid=" + ctx.gridName() + ']');
        X.println(">>>   activeJobsSize: " + activeJobs.size());
        X.println(">>>   passiveJobsSize: " + (jobAlwaysActivate ? "n/a" : passiveJobs.size()));
        X.println(">>>   cancelledJobsSize: " + cancelledJobs.size());
        X.println(">>>   cancelReqsSize: " + cancelReqs.sizex());
        X.println(">>>   finishedJobsSize: " + finishedJobs.sizex());
    }

    /**
     *
     */
    private class CollisionJobContext extends GridCollisionJobContextAdapter {
        /** */
        private final boolean passive;

        /**
         * @param jobWorker Job Worker.
         * @param passive {@code True} if job is on waiting list on creation time.
         */
        CollisionJobContext(GridJobWorker jobWorker, boolean passive) {
            super(jobWorker);

            assert !jobWorker.isInternal();
            assert !jobAlwaysActivate;

            this.passive = passive;
        }

        /** {@inheritDoc} */
        @Override public boolean activate() {
            GridJobWorker jobWorker = getJobWorker();

            return passiveJobs.remove(jobWorker.getJobId(), jobWorker) &&
                onBeforeActivateJob(jobWorker) &&
                executeAsync(jobWorker);
        }

        /** {@inheritDoc} */
        @Override public boolean cancel() {
            GridJobWorker jobWorker = getJobWorker();

            cancelReqs.putIfAbsent(jobWorker.getJobId(), false);

            boolean ret = false;

            if (passive) {
                // If waiting job being rejected.
                if (passiveJobs.remove(jobWorker.getJobId(), jobWorker)) {
                    rejectJob(jobWorker, true);

                    if (metricsUpdateFreq > -1L)
                        rejectedJobsCnt.increment();

                    ret = true;
                }
            }
            // If active job being cancelled.
            else if (activeJobs.remove(jobWorker.getJobId(), jobWorker)) {
                cancelledJobs.put(jobWorker.getJobId(), jobWorker);

                if (finishedJobs.contains(jobWorker.getJobId()))
                    // Job has finished concurrently.
                    cancelledJobs.remove(jobWorker.getJobId(), jobWorker);
                else
                    // We do apply cancel as many times as user cancel job.
                    cancelJob(jobWorker, false);

                ret = true;
            }

            return ret;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(CollisionJobContext.class, this);
        }
    }

    /**
     *
     */
    private class CollisionExternalListener implements GridCollisionExternalListener {
        /** {@inheritDoc} */
        @Override public void onExternalCollision() {
            assert !jobAlwaysActivate;

            if (log.isDebugEnabled())
                log.debug("Received external collision event.");

            rwLock.readLock();

            try {
                if (stopping) {
                    if (log.isDebugEnabled())
                        log.debug("Received external collision notification while stopping grid (will ignore).");

                    return;
                }

                handleCollisions();
            }
            finally {
                rwLock.readUnlock();
            }
        }
    }

    /**
     * Handles job state changes.
     */
    private class JobEventListener implements GridJobEventListener {
        /** */
        private final GridMessageListener sesLsnr = new JobSessionListener();

        /** {@inheritDoc} */
        @Override public void onJobStarted(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onJobStarted() callback: " + worker);

            if (metricsUpdateFreq > -1L)
                updateJobMetrics();

            // Register for timeout notifications.
            if (worker.endTime() < Long.MAX_VALUE)
                ctx.timeout().addTimeoutObject(worker);

            if (worker.getSession().isFullSupport())
                // Register session request listener for this job.
                ctx.io().addMessageListener(worker.getJobTopic(), sesLsnr);
        }

        /** {@inheritDoc} */
        @Override public void onBeforeJobResponseSent(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onBeforeJobResponseSent() callback: " + worker);

            assert jobAlwaysActivate || !passiveJobs.containsKey(worker.getJobId());

            if (worker.getSession().isFullSupport()) {
                // Unregister session request listener for this jobs.
                ctx.io().removeMessageListener(worker.getJobTopic());

                // Unregister message IDs used for sending.
                ctx.io().removeMessageId(worker.getTaskTopic());
            }
        }

        /** {@inheritDoc} */
        @Override public void onJobFinished(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onJobFinished() callback: " + worker);

            GridJobSessionImpl ses = worker.getSession();

            // If last job for the task on this node.
            if (ses.isFullSupport() && ctx.session().removeSession(ses.getId())) {
                ses.onClosed();

                // Unregister checkpoints.
                ctx.checkpoint().onSessionEnd(ses, true);
            }

            // Unregister from timeout notifications.
            if (worker.endTime() < Long.MAX_VALUE)
                ctx.timeout().removeTimeoutObject(worker);

            release(worker.getDeployment());

            finishedJobs.add(worker.getJobId());

            if (!worker.isInternal()) {
                // Increment job execution counter. This counter gets
                // reset once this job will be accounted for in metrics.
                finishedJobsCnt.increment();

                // Increment job execution time. This counter gets
                // reset once this job will be accounted for in metrics.
                long execTime = worker.getExecuteTime();

                finishedJobsTime.add(execTime);

                maxFinishedJobsTime.setIfGreater(execTime);

                if (jobAlwaysActivate) {
                    if (metricsUpdateFreq > -1L)
                        updateJobMetrics();
                }
                else {
                    rwLock.readLock();

                    try {
                        if (stopping) {
                            if (log.isDebugEnabled())
                                log.debug("Skipping collision handling on job finish (node is stopping).");

                            return;
                        }

                        handleCollisions();
                    }
                    finally {
                        rwLock.readUnlock();
                    }
                }

                if (!activeJobs.remove(worker.getJobId(), worker))
                    cancelledJobs.remove(worker.getJobId(), worker);

                heldJobs.remove(worker.getJobId());
            }
        }
    }

    /**
     *
     */
    private class JobHoldListener implements GridJobHoldListener {
        /** {@inheritDoc} */
        @Override public void onHold(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onHold() callback [worker=" + worker + ']');

            if (activeJobs.containsKey(worker.getJobId())) {
                heldJobs.add(worker.getJobId());

                if (!activeJobs.containsKey(worker.getJobId()))
                    heldJobs.remove(worker.getJobId());
            }
        }

        /** {@inheritDoc} */
        @Override public void onUnhold(GridJobWorker worker) {
            if (log.isDebugEnabled())
                log.debug("Received onUnhold() callback [worker=" + worker + ", active=" + activeJobs +
                    ", held=" + heldJobs + ']');

            heldJobs.remove(worker.getJobId());
        }
    }

    /**
     *
     */
    private class JobSessionListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            assert nodeId != null;
            assert msg != null;

            if (log.isDebugEnabled())
                log.debug("Received session attribute request message [msg=" + msg + ", nodeId=" + nodeId + ']');

            processTaskSessionRequest(nodeId, (GridTaskSessionRequest)msg);
        }
    }

    /**
     * Handles task and job cancellations.
     */
    private class JobCancelListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            assert nodeId != null;
            assert msg != null;

            GridJobCancelRequest cancelMsg = (GridJobCancelRequest)msg;

            if (log.isDebugEnabled())
                log.debug("Received job cancel request [cancelMsg=" + cancelMsg + ", nodeId=" + nodeId + ']');

            cancelJob(cancelMsg.sessionId(), cancelMsg.jobId(), cancelMsg.system());
        }
    }

    /**
     * Handles job execution requests.
     */
    private class JobExecutionListener implements GridMessageListener {
        /** {@inheritDoc} */
        @Override public void onMessage(UUID nodeId, Object msg) {
            assert nodeId != null;
            assert msg != null;

            if (!ctx.discovery().alive(nodeId)) {
                U.warn(log, "Received job request message from unknown node (ignoring) " +
                    "[msg=" + msg + ", nodeId=" + nodeId + ']');

                return;
            }

            processJobExecuteRequest(nodeId, (GridJobExecuteRequest)msg);
        }
    }

    /**
     * Listener to node discovery events.
     */
    private class JobDiscoveryListener implements GridLocalEventListener {
        /**
         * Counter used to determine whether all nodes updated metrics or not.
         * This counter is reset every time collisions are handled.
         */
        private int metricsUpdateCntr;

        /** {@inheritDoc} */
        @SuppressWarnings("fallthrough")
        @Override public void onEvent(GridEvent evt) {
            assert evt instanceof GridDiscoveryEvent;

            boolean handleCollisions = false;

            UUID nodeId = ((GridDiscoveryEvent)evt).eventNodeId();

            // We should always process discovery events (even on stop,
            // since we wait for jobs to complete if processor is stopped
            // without cancellation).
            switch (evt.type()) {
                case EVT_NODE_LEFT:
                case EVT_NODE_FAILED:
                    if (!jobAlwaysActivate) {
                        for (GridJobWorker job : passiveJobs.values()) {
                            if (job.getTaskNodeId().equals(nodeId)) {
                                if (passiveJobs.remove(job.getJobId(), job))
                                    U.warn(log, "Task node left grid (job will not be activated) " +
                                        "[nodeId=" + nodeId + ", jobSes=" + job.getSession() + ", job=" + job + ']');
                            }
                        }
                    }

                    for (GridJobWorker job : activeJobs.values()) {
                        if (job.getTaskNodeId().equals(nodeId) && !job.isFinishing() &&
                            activeJobs.remove(job.getJobId(), job)) {
                            // Add to cancelled jobs.
                            cancelledJobs.put(job.getJobId(), job);

                            if (finishedJobs.contains(job.getJobId()))
                                // Job has finished concurrently.
                                cancelledJobs.remove(job.getJobId(), job);
                            else if (!job.onMasterNodeLeft()) {
                                U.warn(log, "Job is being cancelled because master task node left grid " +
                                    "(as there is no one waiting for results, job will not be failed over): " +
                                    job.getJobId());

                                cancelJob(job, true);
                            }
                        }
                    }

                    handleCollisions = true;

                    break;

                case EVT_NODE_METRICS_UPDATED:
                    // Check for less-than-equal rather than just equal
                    // in guard against topology changes.
                    if (ctx.discovery().allNodes().size() <= ++metricsUpdateCntr) {
                        metricsUpdateCntr = 0;

                        handleCollisions = true;
                    }

                    break;

                default:
                    assert false;
            }

            if (handleCollisions) {
                rwLock.readLock();

                try {
                    if (stopping) {
                        if (log.isDebugEnabled())
                            log.debug("Skipped collision handling on discovery event (node is stopping): " + evt);

                        return;
                    }

                    if (!jobAlwaysActivate)
                        handleCollisions();
                    else if (metricsUpdateFreq > -1L)
                        updateJobMetrics();
                }
                finally {
                    rwLock.readUnlock();
                }
            }
        }
    }

    /**
     *
     */
    private class JobsMap extends ConcurrentLinkedHashMap<GridUuid, GridJobWorker> {
        /**
         * @param initCap Initial capacity.
         * @param loadFactor Load factor.
         * @param concurLvl Concurrency level.
         */
        private JobsMap(int initCap, float loadFactor, int concurLvl) {
            super(initCap, loadFactor, concurLvl);
        }

        /** {@inheritDoc} */
        @Override public GridJobWorker put(GridUuid key, GridJobWorker val) {
            assert !val.isInternal();

            GridJobWorker old = super.put(key, val);

            if (old != null)
                U.warn(log, "Jobs map already contains mapping for key [key=" + key + ", val=" + val +
                    ", old=" + old + ']');

            return old;
        }

        /** {@inheritDoc} */
        @Override public GridJobWorker putIfAbsent(GridUuid key, GridJobWorker val) {
            assert !val.isInternal();

            GridJobWorker old = super.putIfAbsent(key, val);

            if (old != null)
                U.warn(log, "Jobs map already contains mapping for key [key=" + key + ", val=" + val +
                    ", old=" + old + ']');

            return old;
        }

        /**
         * @return Constant-time {@code size()}.
         */
        @Override public int size() {
            return sizex();
        }
    }
}
