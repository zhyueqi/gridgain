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

package org.gridgain.grid.kernal.processors.rest;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.rest.client.message.*;
import org.gridgain.grid.kernal.processors.rest.handlers.cache.*;
import org.gridgain.grid.kernal.processors.rest.handlers.log.*;
import org.gridgain.grid.kernal.processors.rest.handlers.task.*;
import org.gridgain.grid.kernal.processors.rest.handlers.top.*;
import org.gridgain.grid.kernal.processors.rest.handlers.version.*;
import org.gridgain.grid.kernal.processors.rest.protocols.http.jetty.*;
import org.gridgain.grid.kernal.processors.rest.protocols.tcp.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.future.*;

import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.kernal.processors.rest.GridRestResponse.*;
import static org.gridgain.grid.spi.GridSecuritySubjectType.*;

/**
 * Rest processor implementation.
 */
public class GridRestProcessor extends GridProcessorAdapter {
    /** */
    private static final byte[] EMPTY_ID = new byte[0];

    /** Array of parameter names which values should pass interception. */
    private static final String[] INTERCEPTED_PARAMS = {"key", "val", "val1", "val2", "p1"};

    /** Ant-augmented string version number. */
    private static final String VER_STR = /*@java.version*/"5.0.0";

    /** Static version as numeric array (generated from {@link #VER_STR}). */
    public static final byte[] VER_BYTES = U.intToBytes(VER_STR.hashCode());

    /** Protocols. */
    private final Collection<GridRestProtocol> protos = new ArrayList<>();

    /**  Command handlers. */
    private final Collection<GridRestCommandHandler> handlers = new ArrayList<>();

    /** */
    private final CountDownLatch startLatch = new CountDownLatch(1);

    /** Protocol handler. */
    private final GridRestProtocolHandler protoHnd = new GridRestProtocolHandler() {
        @Override public GridRestResponse handle(GridRestRequest req) throws GridException {
            return handleAsync(req).get();
        }

        @Override public GridFuture<GridRestResponse> handleAsync(final GridRestRequest req) {
            if (startLatch.getCount() > 0) {
                try {
                    startLatch.await();
                }
                catch (InterruptedException e) {
                    return new GridFinishedFuture<>(ctx, new GridException("Failed to handle request " +
                        "(protocol handler was interrupted when awaiting grid start).", e));
                }
            }

            if (log.isDebugEnabled())
                log.debug("Received request from client: " + req);

            try {
                authenticate(req);
            }
            catch (GridException e) {
                return new GridFinishedFuture<>(ctx, new GridRestResponse(STATUS_AUTH_FAILED,
                    e.getMessage()));
            }

            interceptRequest(req);

            GridFuture<GridRestResponse> res = null;

            for (GridRestCommandHandler handler : handlers) {
                if (handler.supported(req.getCommand())) {
                    res = handler.handleAsync(req);

                    break;
                }
            }

            if (res == null)
                return new GridFinishedFuture<>(ctx,
                    new GridException("Failed to find registered handler for command: " + req.getCommand()));

            return res.chain(new C1<GridFuture<GridRestResponse>, GridRestResponse>() {
                @Override public GridRestResponse apply(GridFuture<GridRestResponse> f) {
                    GridRestResponse res;

                    try {
                        res = f.get();
                    }
                    catch (Exception e) {
                        LT.error(log, e, "Failed to handle request: " + req.getCommand());

                        if (log.isDebugEnabled())
                            log.debug("Failed to handle request [req=" + req + ", e=" + e + "]");

                        res = new GridRestResponse(STATUS_FAILED, e.getMessage());
                    }

                    assert res != null;

                    if (ctx.isEnterprise()) {
                        try {
                            res.sessionTokenBytes(updateSessionToken(req));
                        }
                        catch (GridException e) {
                            U.warn(log, "Cannot update response session token: " + e.getMessage());
                        }
                    }

                    interceptResponse(res, req);

                    return res;
                }
            });
        }
    };

    /**
     * Applies {@link GridClientMessageInterceptor} from {@link GridConfiguration#getClientMessageInterceptor()}
     * to all user parameters in the request.
     *
     * @param req Client request.
     */
    private void interceptRequest(GridRestRequest req) {
        GridClientMessageInterceptor interceptor = ctx.config().getClientMessageInterceptor();

        if (interceptor == null)
            return;

        for (String param : INTERCEPTED_PARAMS) {
            Object oldVal = req.parameter(param);

            if (oldVal != null) {
                Object newVal = interceptor.onReceive(oldVal);

                req.parameter(param, newVal);
            }
        }

        int i = 1;

        while (true) {
            Object k = req.parameter("k" + i);
            Object v = req.parameter("v" + i);

            if (k == null && v == null)
                break;

            if (k != null)
                req.parameter("k" + i, interceptor.onReceive(k));

            if (v != null)
                req.parameter("v" + i, interceptor.onReceive(v));

            i++;
        }
    }

    /**
     * Applies {@link GridClientMessageInterceptor} from {@link GridConfiguration#getClientMessageInterceptor()}
     * to all user objects in the response.
     *
     * @param res Response.
     * @param req Request.
     */
    private void interceptResponse(GridRestResponse res, GridRestRequest req) {
        GridClientMessageInterceptor interceptor = ctx.config().getClientMessageInterceptor();

        if (interceptor != null && res.getResponse() != null) {
            switch (req.getCommand()) {
                case CACHE_GET:
                case CACHE_GET_ALL:
                case CACHE_PUT:
                case CACHE_ADD:
                case CACHE_PUT_ALL:
                case CACHE_REMOVE:
                case CACHE_REMOVE_ALL:
                case CACHE_REPLACE:
                case CACHE_INCREMENT:
                case CACHE_DECREMENT:
                case CACHE_CAS:
                case CACHE_APPEND:
                case CACHE_PREPEND:
                    res.setResponse(interceptSendObject(res.getResponse(), interceptor));

                    break;

                case EXE:
                    if (res.getResponse() instanceof GridClientTaskResultBean) {
                        GridClientTaskResultBean taskRes = (GridClientTaskResultBean) res.getResponse();

                        taskRes.setResult(interceptor.onSend(taskRes.getResult()));
                    }

                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Applies interceptor to a response object.
     * Specially handler {@link Map} and {@link Collection} responses.
     *
     * @param obj Response object.
     * @param interceptor Interceptor to apply.
     * @return Intercepted object.
     */
    private static Object interceptSendObject(Object obj, GridClientMessageInterceptor interceptor) {
        if (obj instanceof Map) {
            Map<Object, Object> original = (Map<Object, Object>)obj;

            Map<Object, Object> m = new HashMap<>();

            for (Map.Entry e : original.entrySet())
                m.put(interceptor.onSend(e.getKey()), interceptor.onSend(e.getValue()));

            return m;
        }
        else if (obj instanceof Collection) {
            Collection<Object> original = (Collection<Object>)obj;

            Collection<Object> c = new ArrayList<>(original.size());

            for (Object e : original)
                c.add(interceptor.onSend(e));

            return c;
        }
        else
            return interceptor.onSend(obj);
    }

    /**
     * @param ctx Context.
     */
    public GridRestProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /**
     * Authenticates remote client.
     *
     * @param req Request to authenticate.
     * @throws GridException If authentication failed.
     */
    private void authenticate(GridRestRequest req) throws GridException {
        UUID clientId = req.getClientId();

        byte[] clientIdBytes = clientId != null ? U.uuidToBytes(clientId) : EMPTY_ID;

        byte[] sesTok = req.getSessionToken();

        // Validate session.
        if (sesTok != null && ctx.secureSession().validate(REMOTE_CLIENT, clientIdBytes, sesTok, null) != null)
            // Session is still valid.
            return;

        // Authenticate client if invalid session.
        if (!ctx.auth().authenticate(REMOTE_CLIENT, clientIdBytes, req.getCredentials()))
            if (req.getCredentials() == null)
                throw new GridException("Failed to authenticate remote client (secure session SPI not set?): " + req);
            else
                throw new GridException("Failed to authenticate remote client (invalid credentials?): " + req);
    }

    /**
     * Update session token to actual state.
     *
     * @param req Grid est request.
     * @return Valid session token.
     * @throws GridException If session token update process failed.
     */
    private byte[] updateSessionToken(GridRestRequest req) throws GridException {
        byte[] subjId = U.uuidToBytes(req.getClientId());

        byte[] sesTok = req.getSessionToken();

        // Update token from request to actual state.
        if (sesTok != null)
            sesTok = ctx.secureSession().validate(REMOTE_CLIENT, subjId, req.getSessionToken(), null);

        // Create new session token, if request doesn't valid session token.
        if (sesTok == null)
            sesTok = ctx.secureSession().validate(REMOTE_CLIENT, subjId, null, null);

        // Validate token has been created.
        if (sesTok == null)
            throw new GridException("Cannot create session token (is secure session SPI set?).");

        return sesTok;
    }

    /**
     *
     * @return Whether or not REST is enabled.
     */
    private boolean isRestEnabled() {
        return !ctx.config().isDaemon() && ctx.config().isRestEnabled();
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        if (isRestEnabled()) {
            // Register handlers.
            addHandler(new GridCacheCommandHandler(ctx));
            addHandler(new GridTaskCommandHandler(ctx));
            addHandler(new GridTopologyCommandHandler(ctx));
            addHandler(new GridVersionCommandHandler(ctx));
            addHandler(new GridLogCommandHandler(ctx));

            // Start protocol.
            startProtocol(new GridJettyRestProtocol(ctx));
            startProtocol(new GridTcpRestProtocol(ctx));
        }
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        if (isRestEnabled()) {
            startLatch.countDown();

            if (log.isDebugEnabled())
                log.debug("REST processor started.");
        }
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        if (isRestEnabled()) {
            for (GridRestProtocol proto : protos)
                proto.stop();

            // Safety.
            startLatch.countDown();

            if (log.isDebugEnabled())
                log.debug("REST processor stopped.");
        }
    }

    /**
     * @param hnd Command handler.
     * @return {@code True} if class for handler was found.
     */
    private boolean addHandler(GridRestCommandHandler hnd) {
        assert !handlers.contains(hnd);

        if (log.isDebugEnabled())
            log.debug("Added REST command handler: " + hnd);

        return handlers.add(hnd);
    }

    /**
     * @param proto Protocol.
     * @throws GridException If protocol initialization failed.
     */
    private void startProtocol(GridRestProtocol proto) throws GridException {
        assert !protos.contains(proto);

        protos.add(proto);

        proto.start(protoHnd);

        if (log.isDebugEnabled())
            log.debug("Added REST protocol: " + proto);
    }

    /** {@inheritDoc} */
    @Override public void addAttributes(Map<String, Object> attrs)  throws GridException {
        for (GridRestProtocol proto : protos) {
            for (GridBiTuple<String, Object> p : proto.getProperties()) {
                String key = p.getKey();

                if (key == null)
                    continue;

                if (attrs.containsKey(key))
                    throw new GridException(
                        "Node attribute collision for attribute [processor=GridRestProcessor, attr=" + key + ']');

                attrs.put(key, p.getValue());
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void printMemoryStats() {
        X.println(">>>");
        X.println(">>> REST processor memory stats [grid=" + ctx.gridName() + ']');
        X.println(">>>   protosSize: " + protos.size());
        X.println(">>>   handlersSize: " + handlers.size());
    }
}
