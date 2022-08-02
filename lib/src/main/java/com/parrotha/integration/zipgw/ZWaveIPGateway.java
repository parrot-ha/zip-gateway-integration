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
import com.parrotha.integration.zipgw.zwaveip.ZIPSingleResponseTransactionWithSeqNum;
import com.parrotha.integration.zipgw.zwaveip.ZIPTransaction;
import com.parrotha.integration.zipgw.zwaveip.ZWaveIPClient;
import com.parrotha.internal.utils.HexUtils;
import com.parrotha.zwave.ZWaveCommandEnum;
import com.parrotha.zwave.commands.networkmanagementbasicv2.DefaultSet;
import com.parrotha.zwave.commands.networkmanagementbasicv2.DefaultSetComplete;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeAdd;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeAddStatus;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeRemove;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeRemoveStatus;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeInfoCachedGet;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeInfoCachedReport;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeListGet;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeListReport;
import com.parrotha.zwave.commands.zipgatewayv1.UnsolicitedDestinationGet;
import com.parrotha.zwave.commands.zipgatewayv1.UnsolicitedDestinationSet;
import com.parrotha.zwave.commands.zipndv1.ZipInvNodeSolicitation;
import com.parrotha.zwave.commands.zipndv1.ZipNodeAdvertisement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Manages communication with the Z-Wave IP Gateway
 */
public class ZWaveIPGateway {
    private static final Logger logger = LoggerFactory.getLogger(ZWaveIPGateway.class);

    private InetAddress address;
    private String psk;
    private ZWaveIPClient zWaveIPClient;

    private short sequenceNumber;
    private final Object sequenceNumberSyncObject;

    public ZWaveIPGateway(InetAddress address, String psk) {
        this.address = address;
        this.psk = psk;
        this.sequenceNumber = (short) (Math.random() * 256);
        this.sequenceNumberSyncObject = new Object();
    }

    private short incrementAndGetSequenceNumber() {
        synchronized (this.sequenceNumberSyncObject) {
            this.sequenceNumber = (byte) ((this.sequenceNumber + 1) % 256);
        }
        return this.sequenceNumber;
    }

    public void shutdown() {
        try {
            if (zWaveIPClient != null) {
                zWaveIPClient.close();
            }
        } catch (IOException ioException) {
            logger.warn("Exception", ioException);
        }
    }

    private ZWaveIPClient getzWaveIPClient() {
        if (zWaveIPClient == null) {
            zWaveIPClient = new ZWaveIPClient(address, "ParrotHub_Client" + "00", psk);
        }

        return zWaveIPClient;
    }

    public InetAddress getNodeIpv6Address(int nodeId) throws UnknownHostException {
        ZipInvNodeSolicitation zipInvNodeSolicitation = new ZipInvNodeSolicitation();
        zipInvNodeSolicitation.setNodeId((short) nodeId);
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransaction(zipInvNodeSolicitation.format(),
                "5801", true);
        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            ZipNodeAdvertisement nodeAdvertisement = new ZipNodeAdvertisement();
            nodeAdvertisement.setPayload(payload);
            return Inet6Address.getByAddress(ByteUtils.shortListToByteArray(nodeAdvertisement.getIpv6Address()));
        }
        return null;
    }

    public NodeInfoCachedReport getNodeInfoCachedReport(int nodeId) {
        NodeInfoCachedGet nodeInfoCachedGet = new NodeInfoCachedGet();
        nodeInfoCachedGet.setNodeId((short) nodeId);
        nodeInfoCachedGet.setMaxAge((short) 0);
        nodeInfoCachedGet.setSeqNo(incrementAndGetSequenceNumber());

        ZIPTransaction zipTransaction = new ZIPSingleResponseTransactionWithSeqNum(nodeInfoCachedGet.format(),
                (byte) ZWaveCommandEnum.NodeInfoCachedReport.getCommandClass(),
                (byte) ZWaveCommandEnum.NodeInfoCachedReport.getCommand());
        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            NodeInfoCachedReport nodeInfoCachedReport = new NodeInfoCachedReport();
            nodeInfoCachedReport.setPayload(payload);
            return nodeInfoCachedReport;
        }
        return null;
    }

    public void setUnsolicitedDestination(InetAddress localAddress, int port) throws ZWaveIPException {
        UnsolicitedDestinationSet unsolicitedDestinationSet = new UnsolicitedDestinationSet();
        unsolicitedDestinationSet.setDestinationPort(port);
        unsolicitedDestinationSet.setIpv6Destination(HexUtils.hexStringToShortList(HexUtils.byteArrayToHexString(localAddress.getAddress())));
        try {
            getzWaveIPClient().sendMessage(unsolicitedDestinationSet.format());

            UnsolicitedDestinationGet unsolicitedDestinationGet = new UnsolicitedDestinationGet();
            ZIPTransaction zipTransaction = new ZIPSingleResponseTransaction(unsolicitedDestinationGet.format(), (byte) 0x5F, (byte) 0x0A);
            byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        } catch (IOException ioException) {
            logger.warn("Exception", ioException);
        }
    }

    public NodeListReport getNodeListReport() {
        NodeListGet nodeListGet = new NodeListGet();
        nodeListGet.setSeqNo(incrementAndGetSequenceNumber());
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransactionWithSeqNum(nodeListGet.format(),
                (byte) ZWaveCommandEnum.NodeListReport.getCommandClass(),
                (byte) ZWaveCommandEnum.NodeListReport.getCommand());

        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            NodeListReport nodeListReport = new NodeListReport();
            nodeListReport.setPayload(payload);
            return nodeListReport;
        }
        return null;
    }

    public NodeAddStatus startNodeAdd() {
        NodeAdd nodeAdd = new NodeAdd();
        nodeAdd.setMode(NodeAdd.ADD_NODE_ANY);
        nodeAdd.setTxOptions(NodeAdd.TRANSMIT_OPTION_EXPLORE);
        nodeAdd.setSeqNo(incrementAndGetSequenceNumber());
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransactionWithSeqNum(nodeAdd.format(),
                (byte) ZWaveCommandEnum.NodeAddStatus.getCommandClass(),
                (byte) ZWaveCommandEnum.NodeAddStatus.getCommand(),
                75);

        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            NodeAddStatus nodeAddStatus = new NodeAddStatus();
            nodeAddStatus.setPayload(payload);
            return nodeAddStatus;
        }
        return null;
    }

    public boolean stopNodeAdd() {
        NodeAdd nodeAdd = new NodeAdd();
        nodeAdd.setMode(NodeAdd.ADD_NODE_STOP);
        nodeAdd.setSeqNo(incrementAndGetSequenceNumber());

        try {
            getzWaveIPClient().sendMessage(nodeAdd.format());
            return true;
        } catch (ZWaveIPException | IOException e) {
            logger.warn("Exception", e);
        }
        return false;
    }

    public NodeRemoveStatus startNodeRemove() {
        NodeRemove nodeRemove = new NodeRemove();
        nodeRemove.setMode(NodeRemove.REMOVE_NODE_ANY);
        nodeRemove.setSeqNo(incrementAndGetSequenceNumber());
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransactionWithSeqNum(nodeRemove.format(),
                (byte) ZWaveCommandEnum.NodeRemoveStatus.getCommandClass(),
                (byte) ZWaveCommandEnum.NodeRemoveStatus.getCommand(),
                45);

        byte[] commandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (commandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(commandResponse, 2);
            NodeRemoveStatus nodeRemoveStatus = new NodeRemoveStatus();
            nodeRemoveStatus.setPayload(payload);
            return nodeRemoveStatus;
        }
        return null;
    }

    public boolean stopNodeRemove() {
        NodeRemove nodeRemove = new NodeRemove();
        nodeRemove.setMode(NodeRemove.REMOVE_NODE_STOP);
        nodeRemove.setSeqNo(incrementAndGetSequenceNumber());

        try {
            getzWaveIPClient().sendMessage(nodeRemove.format());
            return true;
        } catch (ZWaveIPException | IOException e) {
            logger.warn("Exception", e);
        }
        return false;
    }

    public boolean reset() {
        boolean response = false;
        DefaultSet defaultSet = new DefaultSet();
        defaultSet.setSeqNo(incrementAndGetSequenceNumber());
        ZIPTransaction zipTransaction = new ZIPSingleResponseTransactionWithSeqNum(defaultSet.format(),
                (byte) ZWaveCommandEnum.DefaultSetComplete.getCommandClass(),
                (byte) ZWaveCommandEnum.DefaultSetComplete.getCommand());

        byte[] resetCommandResponse = getzWaveIPClient().sendZIPTransaction(zipTransaction);
        if (resetCommandResponse != null) {
            List<Short> payload = ByteUtils.byteArrayToShortList(resetCommandResponse, 2);
            DefaultSetComplete defaultSetComplete = new DefaultSetComplete();
            defaultSetComplete.setPayload(payload);
            if (defaultSetComplete.getStatus() == DefaultSetComplete.DEFAULT_SET_DONE) {
                response = true;
            }
        }

        return response;
    }
}
