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

import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.SingleNodeConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedMultiPskStore;
import com.parrotha.helper.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;

public class PSKDtlsServer {
    private static final Logger logger = LoggerFactory.getLogger(PSKDtlsServer.class);

    private DTLSConnector dtlsConnector;

    public PSKDtlsServer(RawDataChannel messageHandler, int port, String pskString) {
        AdvancedMultiPskStore pskStore = new AdvancedMultiPskStore();
        // put in the PSK store the default identity/psk for tinydtls tests
        pskStore.setKey("Client_identity", HexUtils.hexStringToByteArray(pskString));

        DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder();
        builder.setRecommendedCipherSuitesOnly(false);
        builder.setAddress(new InetSocketAddress(port));
        builder.setAdvancedPskStore(pskStore);
        builder.setConnectionIdGenerator(new SingleNodeConnectionIdGenerator(6));

        dtlsConnector = new DTLSConnector(builder.build());
        dtlsConnector.setRawDataReceiver(messageHandler);
    }

    public void send(RawData message) {
        dtlsConnector.send(message);
    }

    public void start() {
        try {
            dtlsConnector.start();
            logger.info("Z/IP Gateway PSK DTLS Server started");
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unexpected error starting the DTLS UDP server", e);
        }
    }

    public void stop() {
    }
}
