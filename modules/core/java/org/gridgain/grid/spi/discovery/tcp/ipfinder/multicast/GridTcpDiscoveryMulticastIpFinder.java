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

package org.gridgain.grid.spi.discovery.tcp.ipfinder.multicast;

import org.gridgain.grid.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.marshaller.*;
import org.gridgain.grid.marshaller.jdk.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.tostring.*;

import java.io.*;
import java.net.*;
import java.util.*;

import static org.gridgain.grid.GridSystemProperties.*;

/**
 * Multicast-based IP finder.
 * <p>
 * When TCP discovery starts this finder sends multicast request and waits
 * for some time when others nodes reply to this request with messages containing
 * their addresses (time IP finder waits for response and number of attempts to
 * re-send multicast request in case if no replies are received can be configured,
 * see {@link #setResponseWaitTime(int)} and {@link #setAddressRequestAttempts(int)}).
 * <p>
 * In addition to address received via multicast this finder can work with pre-configured
 * list of addresses specified via {@link #setAddresses(Collection)} method.
 * <h1 class="header">Configuration</h1>
 * <h2 class="header">Mandatory</h2>
 * There are no mandatory configuration parameters.
 * <h2 class="header">Optional</h2>
 * <ul>
 *      <li>Multicast IP address (see {@link #setMulticastGroup(String)}).</li>
 *      <li>Multicast port number (see {@link #setMulticastPort(int)}).</li>
 *      <li>Address response wait time (see {@link #setResponseWaitTime(int)}).</li>
 *      <li>Address request attempts (see {@link #setAddressRequestAttempts(int)}).</li>
 *      <li>Pre-configured addresses (see {@link #setAddresses(Collection)})</li>
 * </ul>
 */
public class GridTcpDiscoveryMulticastIpFinder extends GridTcpDiscoveryVmIpFinder {
    /** Default multicast IP address (value is {@code 228.1.2.4}). */
    public static final String DFLT_MCAST_GROUP = "228.1.2.4";

    /** Default multicast port number (value is {@code 47400}). */
    public static final int DFLT_MCAST_PORT = 47400;

    /** Default time IP finder waits for reply to multicast address request (value is {@code 500}). */
    public static final int DFLT_RES_WAIT_TIME = 500;

    /** Default number of attempts to send multicast address request (value is {@code 2}). */
    public static final int DFLT_ADDR_REQ_ATTEMPTS = 2;

    /** Address request message data. */
    private static final byte[] MSG_ADDR_REQ_DATA = U.GG_HEADER;

    /** */
    private static final GridMarshaller marsh = new GridJdkMarshaller();

    /** Grid logger. */
    @GridLoggerResource
    private GridLogger log;

    /** Grid name. */
    @GridNameResource
    private String gridName;

    /** Multicast IP address as string. */
    private String mcastGrp = DFLT_MCAST_GROUP;

    /** Multicast port number. */
    private int mcastPort = DFLT_MCAST_PORT;

    /** Time IP finder waits for reply to multicast address request. */
    private int resWaitTime = DFLT_RES_WAIT_TIME;

    /** Number of attempts to send multicast address request. */
    private int addrReqAttempts = DFLT_ADDR_REQ_ATTEMPTS;

    /** */
    @GridToStringExclude
    private AddressSender addrSnd;

    /**
     * Constructs new IP finder.
     */
    public GridTcpDiscoveryMulticastIpFinder() {
        setShared(true);
    }

    /**
     * Sets IP address of multicast group.
     * <p>
     * If not provided, default value is {@link #DFLT_MCAST_GROUP}.
     *
     * @param mcastGrp Multicast IP address.
     */
    @GridSpiConfiguration(optional = true)
    public void setMulticastGroup(String mcastGrp) {
        this.mcastGrp = mcastGrp;
    }

    /**
     * Gets IP address of multicast group.
     *
     * @return Multicast IP address.
     */
    public String getMulticastGroup() {
        return mcastGrp;
    }

    /**
     * Sets port number which multicast messages are sent to.
     * <p>
     * If not provided, default value is {@link #DFLT_MCAST_PORT}.
     *
     * @param mcastPort Multicast port number.
     */
    @GridSpiConfiguration(optional = true)
    public void setMulticastPort(int mcastPort) {
        this.mcastPort = mcastPort;
    }

    /**
     * Gets port number which multicast messages are sent to.
     *
     * @return Port number.
     */
    public int getMulticastPort() {
        return mcastPort;
    }

    /**
     * Sets time in milliseconds IP finder waits for reply to
     * multicast address request.
     * <p>
     * If not provided, default value is {@link #DFLT_RES_WAIT_TIME}.
     *
     * @param resWaitTime Time IP finder waits for reply to multicast address request.
     */
    @GridSpiConfiguration(optional = true)
    public void setResponseWaitTime(int resWaitTime) {
        this.resWaitTime = resWaitTime;
    }

    /**
     * Gets time in milliseconds IP finder waits for reply to
     * multicast address request.
     *
     * @return Time IP finder waits for reply to multicast address request.
     */
    public int getResponseWaitTime() {
        return resWaitTime;
    }

    /**
     * Sets number of attempts to send multicast address request. IP finder re-sends
     * request only in case if no reply for previous request is received.
     * <p>
     * If not provided, default value is {@link #DFLT_ADDR_REQ_ATTEMPTS}.
     *
     * @param addrReqAttempts Number of attempts to send multicast address request.
     */
    @GridSpiConfiguration(optional = true)
    public void setAddressRequestAttempts(int addrReqAttempts) {
        this.addrReqAttempts = addrReqAttempts;
    }

    /**
     * Gets number of attempts to send multicast address request. IP finder re-sends
     * request only in case if no reply for previous request is received.
     *
     * @return Number of attempts to send multicast address request.
     */
    public int getAddressRequestAttempts() {
        return addrReqAttempts;
    }

    /** {@inheritDoc} */
    @Override public void initializeLocalAddresses(Collection<InetSocketAddress> addrs) throws GridSpiException {
        // If GRIDGAIN_OVERRIDE_MCAST_GRP system property is set, use its value to override multicast group from
        // configuration. Used for testing purposes.
        String overrideMcastGrp = System.getProperty(GG_OVERRIDE_MCAST_GRP);

        if (overrideMcastGrp != null)
            mcastGrp = overrideMcastGrp;

        if (F.isEmpty(mcastGrp))
            throw new GridSpiException("Multicast IP address is not specified.");

        if (mcastPort < 0 || mcastPort > 65535)
            throw new GridSpiException("Invalid multicast port: " + mcastPort);

        if (resWaitTime <= 0)
            throw new GridSpiException("Invalid wait time, value greater than zero is expected: " + resWaitTime);

        if (addrReqAttempts <= 0)
            throw new GridSpiException("Invalid number of address request attempts, " +
                "value greater than zero is expected: " + addrReqAttempts);

        if (F.isEmpty(getRegisteredAddresses()))
            U.warn(log, "GridTcpDiscoveryMulticastIpFinder has no pre-configured addresses " +
                "(it is recommended in production to specify at least one address in " +
                "GridTcpDiscoveryMulticastIpFinder.getAddresses() configuration property)");

        InetAddress mcastAddr;

        try {
            mcastAddr = InetAddress.getByName(mcastGrp);
        }
        catch (UnknownHostException e) {
            throw new GridSpiException("Unknown multicast group: " + mcastGrp, e);
        }

        if (!mcastAddr.isMulticastAddress())
            throw new GridSpiException("Invalid multicast group address: " + mcastAddr);

        addrSnd = new AddressSender(mcastAddr, addrs);

        addrSnd.start();

        Collection<InetSocketAddress> res = requestAddresses(mcastAddr);

        if (!res.isEmpty())
            registerAddresses(res);
    }

    /**
     * Sends multicast address request message and waits for reply. Response wait time and number
     * of request attempts are configured as properties {@link #setResponseWaitTime} and
     * {@link #setAddressRequestAttempts}.
     *
     * @param mcastAddr Multicast address where to send request.
     * @return Collection of received addresses.
     */
    private Collection<InetSocketAddress> requestAddresses(InetAddress mcastAddr) {
        Collection<InetSocketAddress> rmtAddrs = new HashSet<>();

        try {
            DatagramPacket reqPckt = new DatagramPacket(MSG_ADDR_REQ_DATA, MSG_ADDR_REQ_DATA.length,
                mcastAddr, mcastPort);

            byte[] resData = new byte[AddressResponse.MAX_DATA_LENGTH];

            DatagramPacket resPckt = new DatagramPacket(resData, resData.length);

            boolean sndError = false;

            for (int i = 0; i < addrReqAttempts; i++) {
                MulticastSocket sock = null;

                try {
                    sock = new MulticastSocket(0);

                    // Use 'false' to enable support for more than one node on the same machine.
                    sock.setLoopbackMode(false);

                    sock.setSoTimeout(resWaitTime);

                    reqPckt.setData(MSG_ADDR_REQ_DATA);

                    try {
                        sock.send(reqPckt);
                    }
                    catch (IOException e) {
                        if (!handleNetworkError(e))
                            break;

                        if (i < addrReqAttempts - 1) {
                            if (log.isDebugEnabled())
                                log.debug("Failed to send multicast address request (will retry in 500 ms): " + e);

                            U.sleep(500);
                        }
                        else {
                            if (log.isDebugEnabled())
                                log.debug("Failed to send multicast address request: " + e);
                        }

                        sndError = true;

                        continue;
                    }

                    long rcvEnd = U.currentTimeMillis() + resWaitTime;

                    try {
                        while (U.currentTimeMillis() < rcvEnd) { // Try to receive multiple responses.
                            sock.receive(resPckt);

                            byte[] data = resPckt.getData();

                            if (!U.bytesEqual(U.GG_HEADER, 0, data, 0, U.GG_HEADER.length)) {
                                U.error(log, "Failed to verify message header.");

                                continue;
                            }

                            AddressResponse addrRes;

                            try {
                                addrRes = new AddressResponse(data);
                            }
                            catch (GridException e) {
                                LT.warn(log, e, "Failed to deserialize multicast response.");

                                continue;
                            }

                            rmtAddrs.addAll(addrRes.addresses());
                        }
                    }
                    catch (SocketTimeoutException ignored) {
                        if (log.isDebugEnabled()) // DatagramSocket.receive timeout has expired.
                            log.debug("Address receive timeout.");
                    }
                }
                catch (IOException e) {
                    U.error(log, "Failed to request nodes addresses.", e);
                }
                finally {
                    U.close(sock);
                }

                if (!rmtAddrs.isEmpty())
                    break;

                if (i < addrReqAttempts - 1) // Wait some time before re-sending address request.
                    U.sleep(200);
            }

            if (log.isDebugEnabled())
                log.debug("Received nodes addresses: " + rmtAddrs);

            if (rmtAddrs.isEmpty() && sndError)
                U.quietAndWarn(log, "Failed to send multicast address request. " +
                    "Check multicast is enabled on this node.");

            return rmtAddrs;
        }
        catch (GridInterruptedException ignored) {
            U.warn(log, "Got interrupted while sending address request.");

            Thread.currentThread().interrupt();

            return rmtAddrs;
        }
    }

    /** {@inheritDoc} */
    @Override public void close() {
        U.interrupt(addrSnd);

        U.join(addrSnd, log);
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridTcpDiscoveryMulticastIpFinder.class, this, "super", super.toString());
    }

    /**
     * @param e Network error to handle.
     * @return {@code True} if this error is recoverable and the operation can be retried.
     */
    private boolean handleNetworkError(IOException e) {
        if ("Network is unreachable".equals(e.getMessage()) && U.isMacOs()) {
            U.warn(log, "Multicast does not work on Mac OS JVM  loopback address (configure external IP address " +
                "for 'localHost' configuration property)");

            return false;
        }

        return true;
    }

    /**
     * Response to multicast address request.
     */
    private static class AddressResponse {
        /** Maximum supported multicast message. */
        public static final int MAX_DATA_LENGTH = 64 * 1024;

        /** */
        private byte[] data;

        /** */
        private Collection<InetSocketAddress> addrs;

        /**
         * @param addrs Addresses discovery SPI binds to.
         * @throws GridException If marshalling failed.
         */
        private AddressResponse(Collection<InetSocketAddress> addrs) throws GridException {
            this.addrs = addrs;

            byte[] addrsData = marsh.marshal(addrs);
            data = new byte[U.GG_HEADER.length + addrsData.length];

            if (data.length > MAX_DATA_LENGTH)
                throw new GridException("Too long data packet [size=" + data.length + ", max=" + MAX_DATA_LENGTH + "]");

            System.arraycopy(U.GG_HEADER, 0, data, 0, U.GG_HEADER.length);
            System.arraycopy(addrsData, 0, data, 4, addrsData.length);
        }

        /**
         * @param data Message data.
         * @throws GridException If unmarshalling failed.
         */
        private AddressResponse(byte[] data) throws GridException {
            assert U.bytesEqual(U.GG_HEADER, 0, data, 0, U.GG_HEADER.length);

            this.data = data;

            addrs = marsh.unmarshal(Arrays.copyOfRange(data, U.GG_HEADER.length, data.length), null);
        }

        /**
         * @return Message data.
         */
        byte[] data() {
            return data;
        }

        /**
         * @return IP address discovery SPI binds to.
         */
        public Collection<InetSocketAddress> addresses() {
            return addrs;
        }
    }

    /**
     * Thread listening for multicast address requests and sending response
     * containing socket address this node's discovery SPI listens to.
     */
    private class AddressSender extends GridSpiThread {
        /** */
        @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
        private MulticastSocket sock;

        /** */
        private InetAddress mcastGrp;

        /** */
        private Collection<InetSocketAddress> addrs;

        /**
         * @param mcastGrp Multicast address.
         * @param addrs Local node addresses.
         * @throws GridSpiException If fails to create multicast socket.
         */
        private AddressSender(InetAddress mcastGrp, Collection<InetSocketAddress> addrs)
            throws GridSpiException {
            super(gridName, "tcp-disco-multicast-addr-sender", log);
            this.mcastGrp = mcastGrp;
            this.addrs = addrs;

            try {
                sock = createSocket();
            }
            catch (IOException e) {
                throw new GridSpiException("Failed to create multicast socket [" +
                    "mcastGrp=" + GridTcpDiscoveryMulticastIpFinder.this.mcastGrp + ", mcastGrp=" + mcastGrp +
                    ", mcastPort=" + mcastPort + ']', e);
            }
        }

        /**
         * Creates multicast socket and joins multicast group.
         *
         * @throws IOException If fails to create socket or join multicast group.
         * @return Multicast socket.
         */
        private MulticastSocket createSocket() throws IOException {
            MulticastSocket sock = new MulticastSocket(mcastPort);

            sock.setLoopbackMode(false); // Use 'false' to enable support for more than one node on the same machine.

            if (sock.getLoopbackMode())
                U.warn(log, "Loopback mode is disabled which prevents nodes on the same machine from discovering " +
                    "each other.");

            sock.joinGroup(mcastGrp);

            return sock;
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            AddressResponse res;

            try {
                res = new AddressResponse(addrs);
            }
            catch (GridException e) {
                U.error(log, "Failed to prepare multicast message.", e);

                return;
            }

            byte[] reqData = new byte[MSG_ADDR_REQ_DATA.length];

            DatagramPacket pckt = new DatagramPacket(reqData, reqData.length);

            while (!isInterrupted()) {
                try {
                    MulticastSocket sock;

                    synchronized (this) {
                        if (isInterrupted())
                            return;

                        sock = this.sock;

                        if (sock == null)
                            sock = createSocket();
                    }

                    sock.receive(pckt);

                    if (!U.bytesEqual(U.GG_HEADER, 0, reqData, 0, U.GG_HEADER.length)) {
                        U.error(log, "Failed to verify message header.");

                        continue;
                    }

                    try {
                        sock.send(new DatagramPacket(res.data(), res.data().length, pckt.getAddress(), pckt.getPort()));
                    }
                    catch (IOException e) {
                        if (e.getMessage().contains("Operation not permitted")) {
                            if (log.isDebugEnabled())
                                log.debug("Got 'operation not permitted' error, ignoring: " + e);
                        }
                        else
                            throw e;
                    }
                }
                catch (IOException e) {
                    if (!isInterrupted()) {
                        U.error(log, "Failed to send/receive address message (will try to reconnect).", e);

                        synchronized (this) {
                            U.close(sock);

                            sock = null;
                        }
                    }
                }
            }
        }

        /** {@inheritDoc} */
        @Override public void interrupt() {
            super.interrupt();

            synchronized (this) {
                U.close(sock);

                sock = null;
            }
        }

        /** {@inheritDoc} */
        @Override protected void cleanup() {
            synchronized (this) {
                U.close(sock);

                sock = null;
            }
        }
    }
}
