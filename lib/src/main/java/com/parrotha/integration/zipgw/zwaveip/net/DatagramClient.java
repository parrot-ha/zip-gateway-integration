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
package com.parrotha.integration.zipgw.zwaveip.net;

import com.parrotha.helper.HexUtils;
import org.eclipse.californium.elements.RawData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class DatagramClient implements ZIPGatewayClient {
    private static final Logger logger = LoggerFactory.getLogger(DatagramClient.class);

    private final InetAddress address;
    private boolean connected = false;
    private final ZIPDataChannel messageHandler;
    private long timeout = 0;

    private DatagramSocket datagramSocket;

    public DatagramClient(ZIPDataChannel messageHandler, InetAddress address) {
        this.address = address;
        this.messageHandler = messageHandler;
    }

    public boolean isConnected() {
        return this.connected;
    }

    public void send(RawData message) {
        if (!isConnected()) {
            start();
        }

        DatagramPacket datagramPacket = new DatagramPacket(message.getBytes(), message.getSize(), message.getInetSocketAddress().getAddress(), 4123);
        try {
            if (isConnected()) {
                updateTimeout();
                datagramSocket.send(datagramPacket);
            } else {
                logger.warn("Socket not connected when attempting to send packet");
            }
        } catch (IOException e) {
            logger.warn("Exception during send()", e);
        }
    }

    private void startListener() {
        updateTimeout();

        // timeout listener if no messages
        new Thread(() -> {
            while (System.currentTimeMillis() < this.timeout) {
                try {
                    Thread.sleep(this.timeout - System.currentTimeMillis());
                } catch (InterruptedException e) {
                    logger.warn("Exception in timeout thread", e);
                }
            }
            stop();
        }).start();

        // start listener thread
        new Thread(() -> {
            byte[] buf = new byte[256];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (connected) {
                try {
                    datagramSocket.receive(packet);
                    // we got a message
                    updateTimeout();
                    if (logger.isDebugEnabled()) {
                        logger.debug("received: " + HexUtils.byteArrayToHexString(Arrays.copyOf(packet.getData(), packet.getLength())));
                    }
                    ZIPRawData zipRawData = new ZIPRawData(Arrays.copyOf(packet.getData(), packet.getLength()), address);
                    messageHandler.receiveData(zipRawData);
                } catch (SocketTimeoutException | SocketException ste) {
                    // do nothing, let while handle breaking out of loop
                } catch (IOException e) {
                    logger.warn("Exception in listener", e);
                    break;
                }
            }
        }).start();
    }

    private void updateTimeout() {
        // timeout in 50 seconds
        this.timeout = System.currentTimeMillis() + (50 * 1000);
    }

    //TODO: synchronize start and stop methods so that they can't be run at the same time.
    public void start() {
        try {
            datagramSocket = new DatagramSocket();
            datagramSocket.setSoTimeout(1000);
            startListener();
            connected = true;
        } catch (SocketException e) {
            logger.warn("Exception during start()", e);
        }
    }

    public void stop() {
        this.connected = false;
        this.datagramSocket.close();
    }
}
