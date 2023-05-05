/**
 * Copyright (c) 2021-2023 by the respective copyright holders.
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
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.scandium.AlertHandler;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.AlertMessage;
import org.eclipse.californium.scandium.dtls.SingleNodeConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedSinglePskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class PSKDtlsClient implements AlertHandler, ZIPGatewayClient {
    private static final Logger logger = LoggerFactory.getLogger(PSKDtlsClient.class);

    private final String psk;
    private final InetAddress address;
    private boolean connected = false;
    private final RawDataChannel messageHandler;

    private DTLSConnector dtlsConnector;

    public PSKDtlsClient(RawDataChannel messageHandler, InetAddress address, String psk) {
        this.address = address;
        this.psk = psk;
        this.messageHandler = messageHandler;
        DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
        builder.setAdvancedPskStore(new AdvancedSinglePskStore("Client_identity", HexUtils.hexStringToByteArray(this.psk)));
        builder.setConnectionIdGenerator(new SingleNodeConnectionIdGenerator(0));
        builder.setConnectionThreadCount(1);
        dtlsConnector = new DTLSConnector(builder.build());
        dtlsConnector.setRawDataReceiver(messageHandler);
        dtlsConnector.setAlertHandler(this);

    }

    @Override
    public void onAlert(InetSocketAddress peer, AlertMessage alert) {
        logger.warn("Alert message: {}", alert.toString());
        if (alert.getDescription() == AlertMessage.AlertDescription.CLOSE_NOTIFY) {
            stop();
        }
    }

    @Override
    public boolean isConnected() {
        return this.connected;
    }

    public void send(RawData message) {
        if (!dtlsConnector.isRunning()) {
            start();
        }
        dtlsConnector.send(message);
    }

    //TODO: synchronize start and stop methods so that they can't be run at the same time.
    public void start() {
        try {
            dtlsConnector.setRawDataReceiver(messageHandler);
            dtlsConnector.start();
            connected = true;
        } catch (IOException e) {
            logger.error("Cannot start connector", e);
        }
    }

    public void stop() {
        this.connected = false;
        dtlsConnector.destroy();
    }
}
