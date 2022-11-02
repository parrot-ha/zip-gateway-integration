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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;

public class DatagramServer implements ZIPGatewayListener {
    private static final Logger logger = LoggerFactory.getLogger(DatagramServer.class);

    private DatagramSocket socket;
    private boolean running;
    private byte[] buf = new byte[256];
    private final ZIPDataChannel messageHandler;
    private final int port;

    public DatagramServer(ZIPDataChannel messageHandler, int port) {
        this.messageHandler = messageHandler;
        this.port = port;
        try {
            this.socket = new DatagramSocket(port);
        } catch (SocketException e) {
            logger.warn("Unable to start datagram server", e);
        }
    }

    @Override
    public void send(byte[] message, InetAddress address, int port) {
        DatagramPacket packet = new DatagramPacket(message, message.length);
        try {
            socket.send(packet);
        } catch (IOException e) {
            logger.warn("Exception while sending data to socket", e);
        }
    }

    @Override
    public void start() {
        running = true;
        new Thread(() -> {
            while (running) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                    ZIPRawData zipRawData = new ZIPRawData(Arrays.copyOf(packet.getData(), packet.getLength()), packet.getAddress());
                    messageHandler.receiveData(zipRawData);
                } catch (SocketException se) {
                    if ("Socket closed".equals(se.getMessage())) {
                        // do nothing, this is normal
                    } else {
                        logger.warn("Exception while starting UDP server", se);
                    }
                } catch (IOException e) {
                    logger.warn("Exception while starting UDP server", e);
                }
            }
        }).start();
    }

    @Override
    public void stop() {
        if (socket != null) {
            socket.close();
        }
        socket = null;
        running = false;
    }
}
