/**
 * Copyright (c) 2021-2022 by the respective copyright holders.
 * All rights reserved.
 * <p>
 * This file is part of Parrot Home Automation Hub Z/IP Gateway Extension.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.parrotha.integration.zipgw.zwaveip;

import com.parrotha.integration.zipgw.ByteUtils;
import com.parrotha.integration.zipgw.ZWaveIPException;
import com.parrotha.integration.zipgw.zwaveip.net.PSKDtlsClient;
import com.parrotha.internal.utils.HexUtils;
import com.parrotha.zwave.Command;
import com.parrotha.zwave.ZWaveCommandEnum;
import com.parrotha.zwave.commands.zipv4.ZipKeepAlive;
import com.parrotha.zwave.commands.zipv4.ZipPacket;
import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public class ZWaveIPClient implements RawDataChannel {
    private static final Logger logger = LoggerFactory.getLogger(ZWaveIPClient.class);

    private PSKDtlsClient pskDtlsClient;

    private boolean isConnected;

    private static final int port = 41230;

    private InetAddress remoteAddress;
    private String pskIdentity;
    private String pskPassword;
    private AddressEndpointContext addressEndpoint;

    private byte sequenceNumber;
    private Object sequenceNumberSyncObject;

    private Object receiveMessageSyncObject;

    private HashMap<Byte, AckRegistration> messagesWaitingForAck;
    private List<ResponseRegistration> pendingCommandResponses;

    private ExecutorService executor = Executors.newCachedThreadPool();
    private final List<ZIPTransactionListener> transactionListeners = new ArrayList<ZIPTransactionListener>();

    /*** INIT AND DE-INIT FUNCTIONS ***/

    public ZWaveIPClient(InetAddress address, String pskIdentity, String pskPassword) {
        // store our connection parameters
        this.remoteAddress = address;
        this.pskIdentity = pskIdentity;
        this.pskPassword = pskPassword;
        this.addressEndpoint = new AddressEndpointContext(new InetSocketAddress(address, this.port));
        // create a variable to hold our DTLS session reference (once we send a message)

        pskDtlsClient = new PSKDtlsClient(this, remoteAddress, pskPassword);
        // set isConnected to false (so we know to connect when the first message is sent)
        this.isConnected = false;
        // randomize the initial sequenceNumber
        this.sequenceNumber = (byte) (Math.random() * 256);
        // create a hash to hold a record of all outstanding messages which are waiting for an acknowledgment
        this.messagesWaitingForAck = new HashMap<Byte, AckRegistration>();

        // initialize our synchronization (lock) objects
        this.sequenceNumberSyncObject = new Object();
        this.receiveMessageSyncObject = new Object();
    }

    public void close() throws IOException {
        if (pskDtlsClient.isConnected() == true) {
            this.pskDtlsClient.stop();
        }
    }

    // based on zsmart systems example in AshFrameHandler:
    public void addTransactionListener(ZIPTransactionListener listener) {
        synchronized (transactionListeners) {
            if (transactionListeners.contains(listener)) {
                return;
            }

            transactionListeners.add(listener);
        }
    }

    public void removeTransactionListener(ZIPTransactionListener listener) {
        synchronized (transactionListeners) {
            transactionListeners.remove(listener);
        }
    }

    /**
     * Notify any transaction listeners when we receive a response.
     *
     * @param response the response data received
     * @return true if the response was processed
     */
    private boolean notifyTransactionComplete(final byte[] response) {
        boolean processed = false;

        synchronized (transactionListeners) {
            for (ZIPTransactionListener listener : transactionListeners) {
                if (listener.transactionEvent(response)) {
                    processed = true;
                }
            }
        }

        return processed;
    }

    public interface ZIPTransactionListener {
        boolean transactionEvent(byte[] zWaveIPResponse);

        void transactionComplete();
    }

    public Future<byte[]> sendMessageAsync(final ZIPTransaction zWaveIPTransaction) {
        class TransactionWaiter implements Callable<byte[]>, ZIPTransactionListener {
            private boolean complete = false;

            @Override
            public byte[] call() {
                // Register a listener
                addTransactionListener(this);

                // Send the transaction
                try {
                    if (zWaveIPTransaction.isRawMessage()) {
                        sendRawMessage(zWaveIPTransaction.getRequest());
                    } else {
                        sendMessage(zWaveIPTransaction.getRequest());
                    }
                } catch (ZWaveIPException | IOException e) {
                    logger.warn("Exception", e);
                }

                // Wait for the transaction to complete
                synchronized (this) {
                    while (!complete) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            complete = true;
                        }
                    }
                }

                // Remove the listener
                removeTransactionListener(this);

                return zWaveIPTransaction.getResponse();
            }

            @Override
            public boolean transactionEvent(byte[] zwaveIPResponse) {
                // Check if this response completes our transaction
                if (!zWaveIPTransaction.isMatch(zwaveIPResponse)) {
                    return false;
                }

                transactionComplete();

                return true;
            }

            @Override
            public void transactionComplete() {
                synchronized (this) {
                    complete = true;
                    notify();
                }
            }
        }

        return executor.submit(new TransactionWaiter());
    }

    public byte[] sendZIPTransaction(ZIPTransaction zipTransaction) {
        if (logger.isDebugEnabled()) {
            logger.debug("TX request: {}", zipTransaction.getRequest());
        }

        Future<byte[]> futureResponse = sendMessageAsync(zipTransaction);
        if (futureResponse == null) {
            logger.debug("Error sending transaction: Future is null");
            return null;
        }

        // connection will stay open for 60 seconds by default, so only send a keep alive if the transaction timeout
        // is longer than 55 seconds just to be safe.
        if (zipTransaction.getTimeout() > 55) {
            // start a timer to keep the connection alive
            TimerTask task = new TimerTask() {
                public void run() {
                    sendKeepAlive();
                }
            };
            keepAliveTimer = new Timer("ZWaveNodeRemoveTimer");
            keepAliveTimer.schedule(task, 30 * 1000, 30 * 1000);
        }
        try {
            futureResponse.get(zipTransaction.getTimeout(), TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            futureResponse.cancel(true);
        }

        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }

        return zipTransaction.getResponse();
    }

    private Timer keepAliveTimer;

    private void sendKeepAlive() {
        try {
            sendRawMessage(new ZipKeepAlive().format());
        } catch (IOException ioException) {
            logger.warn("Exception", ioException);
        }
    }

    /*** MESSAGE TRANSMISSION FUNCTIONS ***/

    // NOTE: this function sends a Z-Wave command and waits for an ACK
    public void sendMessage(String zWaveCommand) throws ZWaveIPException, IOException {
        // if the caller passed in a null data array, change our reference to a zero-element data set
        if (zWaveCommand == null) {
            return;
        }

        int timeout;

        /* register our outgoing message (so that we are listening for an ACK) and obtain a sequence
         * number.  If all sequence numbers are in use, we will get a result of null and will need to
         * wait until a sequence number becomes available. In the future, we may want to consider
         * putting an upper bound on the amount of time we are willing to wait for a sequence number. */
        AckRegistration ackRegistration = null;
        while (ackRegistration == null) {
            timeout = INTERNAL_ZWAVE_ACK_TIMEOUT_MS;
            ackRegistration = registerMessageForAckAndReserveSequenceNumber(timeout);

            if (ackRegistration == null) {
                // wait some milliseconds before attempting to request another sequence number
                try {
                    Thread.sleep(INTERNAL_WAIT_MS_BETWEEN_SEQUENCE_NUMBER_ACQUISITION_ATTEMPTS);
                } catch (InterruptedException ex) {
                    // ignore this exception, as we do not support thread interruption
                }
            }
        }

        // create z/ip packet
        ZipPacket zipPacket = new ZipPacket();
        zipPacket.setAckRequest(true);
        zipPacket.setzWaveCmdIncluded(true);
        zipPacket.setSecureOrigin(true);
        zipPacket.setSeqNo((short) sequenceNumber);

        // check for secure message
        if (zWaveCommand.startsWith("9881")) {
            // S0 message, add encapsulation format header
            zipPacket.setHeaderExtension(HexUtils.hexStringToShortList("0584028000"));
            zipPacket.setzWaveCommand(HexUtils.hexStringToShortList(zWaveCommand.substring("988100".length())));
        } else {
            zipPacket.setzWaveCommand(HexUtils.hexStringToShortList(zWaveCommand));
        }

        byte[] packet = HexUtils.hexStringToByteArray(zipPacket.format());

        // save a copy of our message in the "waiting for ACK response" record in case we need to automatically resend it
        PacketData packetData = new PacketData(packet, 0, packet.length);
        ackRegistration.packetData = packetData;

        if (pskDtlsClient.isConnected() == false) {
            // update our ack registration timeout (to remove any timespan introduced by DTLS session init)
            // set the timeout to be an extended value to allow time to negotiate the connection
            updateAckRegistrationTimeout(ackRegistration, INTERNAL_ZWAVE_RESPONSE_TIMEOUT_MS);
        }

        synchronized (receiveMessageSyncObject) {
            // send our message
            RawData rawData = RawData.outbound(packet, this.addressEndpoint, null, false);
            pskDtlsClient.send(rawData);
        }


        // listen for ACK/NAK/timeout
        int currentTimeoutInMillis = calculateRemainingMillisWithMinimumOfZero(ackRegistration.timeoutTimestamp);
        do {
            //TODO: wait on a semaphore instead
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                logger.warn("Exception", e);
            }

            // Check out our message's ACK status; if our status has
            // not changed, then we must loop around again and continue to wait
            switch (ackRegistration.state) {
                case ACK:
                    // message was acknowledged
                    return;
                case NAK:
                    // message was NAK'd by the Z-Wave protocol
                    throw new NakException();
            }

            // update our current timeout in milliseconds (0 == timed out)
            currentTimeoutInMillis = calculateRemainingMillisWithMinimumOfZero(ackRegistration.timeoutTimestamp);
        } while (currentTimeoutInMillis > 0);

        // if our message has timed out, throw an AckTimeoutException.
        throw new AckTimeoutException();
    }

    /*** NODE ID TO IP ADDRESS TRANSLATION FUNCTIONS ***/

    public class IpAddressAndHomeId {
        public byte[] homeId;
        public InetAddress ipv6InetAddress;
        public InetAddress ipv4InetAddress;

        public IpAddressAndHomeId(byte[] homeId, InetAddress ipv6InetAddress, InetAddress ipv4InetAddress) {
            this.homeId = homeId.clone();
            this.ipv6InetAddress = ipv6InetAddress;
            this.ipv4InetAddress = ipv4InetAddress;
        }
    }

    // NOTE: this function sends a raw Z-Wave command without wrapping it inside a Z/IP frame and without waiting for an acknowledgment
    void sendRawMessage(String data) throws IOException {
        if (data == null) {
            return;
        }

        byte[] packet = HexUtils.hexStringToByteArray(data);
        synchronized (receiveMessageSyncObject) {
            // send our message
            pskDtlsClient.send(RawData.outbound(packet, this.addressEndpoint, null, false));
        }

    }

    @Override
    public void receiveData(RawData raw) {
        if (logger.isInfoEnabled()) {
            logger.info("Received response: {} {}", HexUtils.byteArrayToHexString(raw.getBytes()));
        }

        processReceivedPacket(raw.getBytes());
    }

    /*** INCOMING MESSAGE RECEPTION FUNCTIONS ***/

    private void processReceivedPacket(byte[] data) {
        if (data == null) {
            throw new NullPointerException();
        }

        Command command = parseCommand(data);

        if (command == null) {
            logger.warn("Unable to parse command: {}", HexUtils.byteArrayToHexString(data));
            return;
        }

        if (command instanceof ZipPacket) {
            // if this message is an ACK response, trigger any pending ACK
            if (((ZipPacket) command).getAckResponse()) {
                AckRegistration ackRegistration = this.messagesWaitingForAck.get(sequenceNumber);
                if (ackRegistration != null) {
                    // if the ACK registration still exists, set its state to "acknowledged".
                    ackRegistration.state = AckRegistrationState.ACK;
                    // and remove the registration from the queue (as this is a final state)
                    this.messagesWaitingForAck.remove(sequenceNumber);
                }
            } else if (((ZipPacket) command).getNackResponse()) {
                if (((ZipPacket) command).getNackQueueFull()) {
                    // queue full: retry sending message in INTERNAL_ZWAVE_NACK_QUEUE_FULL_RETRY_MS seconds
                    int nackRetryTimeoutInMilliseconds = INTERNAL_ZWAVE_NACK_QUEUE_FULL_RETRY_MS + INTERNAL_ZWAVE_RESPONSE_TIMEOUT_MS;

                    AckRegistration ackRegistration = this.messagesWaitingForAck.get(sequenceNumber);

                    Timer nackRetryTimer = new Timer();
                    class NackRetryTimerTask extends TimerTask {
                        PSKDtlsClient pskDtlsClient;
                        AckRegistration ackRegistration;
                        AddressEndpointContext addressEndpoint;

                        NackRetryTimerTask(PSKDtlsClient pskDtlsClient, AddressEndpointContext addressEndpoint, AckRegistration ackRegistration) {
                            this.addressEndpoint = addressEndpoint;
                            this.pskDtlsClient = pskDtlsClient;
                            this.ackRegistration = ackRegistration;
                        }

                        public void run() {
                            if (this.ackRegistration != null && this.ackRegistration.state == AckRegistrationState.WAITING) {
                                // re-send our message
                                RawData rawData = RawData.outbound(ackRegistration.packetData.msg, addressEndpoint, null, false);
                                pskDtlsClient.send(rawData);
                            }
                        }
                    }

                    // update our ackRegistration's timeout
                    updateAckRegistrationTimeout(ackRegistration, nackRetryTimeoutInMilliseconds);
                    // schedule the "nack queue full" retry timer
                    nackRetryTimer.schedule(new NackRetryTimerTask(this.pskDtlsClient, this.addressEndpoint, ackRegistration),
                            nackRetryTimeoutInMilliseconds);
                } else if (((ZipPacket) command).getNackWaiting()) {
                    AckRegistration ackRegistration = this.messagesWaitingForAck.get(sequenceNumber);
                    if (ackRegistration != null) {
                        // add significant additional "wait time" to the ACK timeout timer
                        updateAckRegistrationTimeout(ackRegistration, INTERNAL_ZWAVE_NACK_WAITING_EXTENSION_MS);
                    }
                } else {
                    // message was rejected
                    AckRegistration ackRegistration = this.messagesWaitingForAck.get(sequenceNumber);
                    if (ackRegistration != null) {
                        // if the ACK registration still exists, set its state to "acknowledged".
                        ackRegistration.state = AckRegistrationState.NAK;
                        // and remove the registration from the queue (as this is a final state)
                        this.messagesWaitingForAck.remove(sequenceNumber);
                    }
                }
            }
        }
        if (command instanceof ZipPacket && ((ZipPacket) command).getzWaveCmdIncluded()) {
            notifyTransactionComplete(ByteUtils.shortListToByteArray(((ZipPacket) command).getzWaveCommand()));
        } else {
            notifyTransactionComplete(HexUtils.hexStringToByteArray(command.format()));
        }

        // NOTE: if there was no callback registered for this command, simply discard this packet.
    }

    private Command parseCommand(byte[] data) {
        if (data == null) {
            return null;
        }

        String commandClassAndCommandString = HexUtils.byteArrayToHexString(Arrays.copyOfRange(data, 0, 2));
        String payloadString = null;
        if (data.length > 2) {
            payloadString = HexUtils.byteArrayToHexString(Arrays.copyOfRange(data, 2, data.length));
        }
        ZWaveCommandEnum zWaveCommand = ZWaveCommandEnum.getZWaveClass(commandClassAndCommandString);
        //zWaveCommand.getMaxVersion()
        if (zWaveCommand != null) {
            try {
                int version = zWaveCommand.getMaxVersion();
                Class<? extends Command> commandClazz = Class.forName(
                                "com.parrotha.zwave.commands." + zWaveCommand.getPackageName() + "v" + version + "." + zWaveCommand.getClassName())
                        .asSubclass(Command.class);
                Command cmd = commandClazz.getDeclaredConstructor().newInstance();
                if (payloadString != null) {
                    cmd.setPayload(HexUtils.hexStringToShortList(payloadString));
                }
                return cmd;
            } catch (ClassNotFoundException | InstantiationException | InvocationTargetException |
                    NoSuchMethodException | IllegalAccessException e) {
                logger.warn("Exception", e);
            }
        }
        return null;
    }

    // NOTE: due to potential multi-processor and out-of-order-execution glitches in various Java
    //       implementations, we calculate elapsed time in special functions which can take additional
    //       factors into account such as ensuring that the elapsed time is >= 0.
    // NOTE: we use system.Time() to measure time because it is the Java timing primitive which is not
    //       tied to "date/time"--which means that adjustments to the system clock will not affect its
    //       readings.
    private int calculateRemainingMillisWithMinimumOfZero(long timeout) {
        return (int) (Math.max(timeout - System.nanoTime(), 0) / NANOSECONDS_PER_MILLISECOND);
    }


    /*** OUTGOING MESSAGE HELPER FUNCTIONS ***/

    private void incrementSequenceNumber() {
        /* NOTE: for details on sequence numbers, see section 3.58.1 (Z/IP Packet Command) of the Z-Wave command class document
         *       (the sequence number can be shared globally, is 8-bit, and we initialized it to a random value) */
        this.sequenceNumber = (byte) ((this.sequenceNumber + 1) % 256);
    }

    private AckRegistration registerMessageForAckAndReserveSequenceNumber(int timeoutInMilliseconds) {
        long timeoutTimestamp = createTimestampByOffset(timeoutInMilliseconds);

        // this operation is sync'd for thread safety (as it relies on control over the sequence number)
        synchronized (this.sequenceNumberSyncObject) {
            // try to generate a unique sequence number up to 256 times
            for (int i = 0; i < 256; i++) {
                this.incrementSequenceNumber();

                AckRegistration ackRegistration = this.messagesWaitingForAck.get(this.sequenceNumber);
                if (ackRegistration != null) {
                    if (checkTimeoutHasOccured(ackRegistration.timeoutTimestamp)) {
                        /* this function is responsible for clearing out expired AckRegistration
                         * records (whereas the "receive message" function is responsible for clearing
                         * them out once an ACK/NAK is received */
                        this.messagesWaitingForAck.remove(this.sequenceNumber);
                    } else {
                        // this sequence number is still active; try another slot.
                        continue;
                    }
                }

                ackRegistration = new AckRegistration(this.sequenceNumber, timeoutTimestamp);
                this.messagesWaitingForAck.put(this.sequenceNumber, ackRegistration);
                return ackRegistration;
            }
        }

        // if no message slots (sequence numbers) are available, return null.
        return null;
    }

    void updateAckRegistrationTimeout(AckRegistration ackRegistration, int timeoutInMilliseconds) {
        ackRegistration.timeoutTimestamp = createTimestampByOffset(timeoutInMilliseconds);
    }


    /*** SHARED TIMEKEEPING FUNCTIONS ***/

    boolean checkTimeoutHasOccured(long timestamp) {
        long currentTime = System.nanoTime();
        if (timestamp < currentTime) {
            return true;
        } else {
            return false;
        }
    }

    long addTime(long baseTime, int millisecondsToAdd) {
        return baseTime + (millisecondsToAdd * NANOSECONDS_PER_MILLISECOND);
    }


    long createTimestampByOffset(int millisecondsToAdd) {
        long currentTime = System.nanoTime();
        return addTime(currentTime, millisecondsToAdd);
    }


    /*** CONSTANTS AND ENUMERATIONS ***/

    /* timekeeping constants */
    private final int NANOSECONDS_PER_MILLISECOND = 1000000;

    /* time to wait between attempts to acquire a sequence number */
    private final int INTERNAL_WAIT_MS_BETWEEN_SEQUENCE_NUMBER_ACQUISITION_ATTEMPTS = 100;

    /* ZIP constants for internal use */
    private final int INTERNAL_ZWAVE_ACK_TIMEOUT_MS = 300;
    private final int INTERNAL_ZWAVE_RESPONSE_TIMEOUT_MS = 2000;
    private final int INTERNAL_ZWAVE_NACK_QUEUE_FULL_RETRY_MS = 10000;
    private final int INTERNAL_ZWAVE_NACK_WAITING_EXTENSION_MS = 90000;

    /* ZIP enumerations for internal use */
    /* AckRegistration */
    private enum AckRegistrationState {
        WAITING,
        ACK,
        NAK;
    }

    //
    private class AckRegistration {
        byte sequenceNumber;
        long timeoutTimestamp;
        PacketData packetData;
        AckRegistrationState state;
        /* NOTE: if we add a dedicated receive thread option in the future, add an "ackReceived"
         * flag here (which will trigger sendMessage(...) thread to unblock). */

        AckRegistration(byte sequenceNumber, long timeoutTimestamp) {
            this.sequenceNumber = sequenceNumber;
            this.timeoutTimestamp = timeoutTimestamp;
            //
            this.packetData = null;
            this.state = AckRegistrationState.WAITING;
        }
    }

    /* ResponseRegistration */
    private enum ResponseRegistrationState {
        WAITING,
        RECEIVED;
    }

    private class ResponseRegistration {
        Byte commandClass;
        Byte command;
        Byte matchIdentifier;
        long timeoutTimestamp;
        boolean isWrappedInZipFrame;
        private byte[] data;
        ResponseRegistrationState state;
        /* NOTE: if we add a dedicated receive thread option in the future, add a "responseReceived"
         * flag here (which will trigger sendMessageAndWaitForResponse(...) thread to unblock). */

        ResponseRegistration(Byte commandClass, Byte command, Byte matchIdentifier, long timeoutTimestamp, boolean isWrappedInZipFrame) {
            this.commandClass = commandClass;
            this.command = command;
            this.matchIdentifier = matchIdentifier;
            this.timeoutTimestamp = timeoutTimestamp;
            this.isWrappedInZipFrame = isWrappedInZipFrame; // true for most responses (wrapped in Z/IP); false for special responses (like Z/IP Node Advertisement)
            this.state = ResponseRegistrationState.WAITING;
        }

        public void setData(byte[] data) {
            this.data = data;
        }

        public byte[] getData() {
            return this.data.clone();
        }
    }

    private class PacketData {
        public byte[] msg;
        public int offset;
        public int length;

        public PacketData(byte[] msg, int offset, int length) {
            this.msg = msg;
            this.offset = offset;
            this.length = length;
        }
    }
}
