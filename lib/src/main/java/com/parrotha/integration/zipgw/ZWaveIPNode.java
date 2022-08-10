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
package com.parrotha.integration.zipgw;

import com.parrotha.integration.zipgw.zwaveip.ZIPSingleResponseTransaction;
import com.parrotha.integration.zipgw.zwaveip.ZIPTransaction;
import com.parrotha.integration.zipgw.zwaveip.ZWaveIPClient;
import com.parrotha.internal.utils.HexUtils;
import com.parrotha.zwave.Command;
import com.parrotha.zwave.ZWaveCommandEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.util.List;

/**
 * Manages communication with a Z-Wave node connected to Z/IP Gateway
 */
public class ZWaveIPNode implements ZWaveIPClient.ZIPTransactionListener {
    private static final Logger logger = LoggerFactory.getLogger(ZWaveIPNode.class);

    private int nodeId;
    private InetAddress address;
    private String psk;
    ZIPGWHandler zipgwHandler;
    private ZWaveIPClient zWaveIPClient;

    public ZWaveIPNode(ZIPGWHandler zipgwHandler, Integer nodeId, InetAddress address, String psk) {
        this.zipgwHandler = zipgwHandler;
        this.nodeId = nodeId;
        this.address = address;
        this.psk = psk;
    }

    private ZWaveIPClient getzWaveIPClient() {
        if (zWaveIPClient == null) {
            zWaveIPClient = new ZWaveIPClient(address, "ParrotHub_Client" + HexUtils.integerToHexString(nodeId, 1), psk);
            zWaveIPClient.addTransactionListener(this);
        }

        return zWaveIPClient;
    }

    public void sendZWaveCommand(String cmd) {
        try {
            getzWaveIPClient().sendMessage(cmd);
        } catch (ZWaveIPException | IOException e) {
            logger.warn("Exception", e);
        }
    }

    @Override
    public boolean transactionEvent(byte[] zWaveIPMessage) {
        // filter out zip frames, TODO: what other messages should be filtered?
        if (zWaveIPMessage.length > 1 && zWaveIPMessage[0] != 0x23 && zWaveIPMessage[1] != 0x02) {
            zipgwHandler.sendDeviceMessage(nodeId, zWaveIPMessage);
        }
        return true;
    }

    @Override
    public void transactionComplete() {

    }

    public Command sendZWaveCommandAndGetResponse(Command command, String responseType) {
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransaction(command.format(), responseType);
        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            ZWaveCommandEnum zWaveCommand = ZWaveCommandEnum.getZWaveClass(responseType);

            try {
                int version = zWaveCommand.getMaxVersion();
                Class<? extends Command> commandClazz = Class.forName(
                                "com.parrotha.zwave.commands." + zWaveCommand.getPackageName() + "v" + version + "." + zWaveCommand.getClassName())
                        .asSubclass(Command.class);
                Command cmd = commandClazz.getDeclaredConstructor().newInstance();
                cmd.setPayload(payload);
                return cmd;
            } catch (ClassNotFoundException | InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
                logger.warn("Exception", e);
            }
        }
        return null;
    }
}
