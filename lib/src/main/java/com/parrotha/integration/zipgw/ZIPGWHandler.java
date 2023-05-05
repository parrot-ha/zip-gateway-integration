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
package com.parrotha.integration.zipgw;

import com.parrotha.integration.device.DeviceAddedEvent;
import com.parrotha.integration.device.DeviceMessageEvent;
import com.parrotha.integration.zipgw.zwaveip.ZWaveIPException;
import com.parrotha.integration.zipgw.zwaveip.net.DatagramServer;
import com.parrotha.integration.zipgw.zwaveip.net.PSKDtlsServer;
import com.parrotha.integration.zipgw.zwaveip.net.ZIPDataChannel;
import com.parrotha.integration.zipgw.zwaveip.net.ZIPGatewayListener;
import com.parrotha.integration.zipgw.zwaveip.net.ZIPRawData;
import com.parrotha.internal.utils.HexUtils;
import com.parrotha.zwave.Command;
import com.parrotha.zwave.ZWaveCommandEnum;
import com.parrotha.zwave.commands.associationv3.AssociationSet;
import com.parrotha.zwave.commands.manufacturerspecificv2.ManufacturerSpecificGet;
import com.parrotha.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeAddStatus;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeRemoveStatus;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeInfoCachedReport;
import com.parrotha.zwave.commands.networkmanagementproxyv3.NodeListReport;
import com.parrotha.zwave.commands.versionv3.VersionGet;
import com.parrotha.zwave.commands.versionv3.VersionReport;
import com.parrotha.zwave.commands.zipv4.ZipPacket;
import groovy.json.JsonBuilder;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class ZIPGWHandler implements RawDataChannel, ZIPDataChannel {
    private final Logger logger = LoggerFactory.getLogger(ZIPGWHandler.class);

    private final String zipgwAddress;
    private final String psk;
    private ZWaveIPGateway zWaveIPGateway;
    private final ZIPGWIntegration zipgwIntegration;
    private ZIPGatewayListener zipGWListener;
    private final boolean useDtls;
    private Short controllerId;

    private Map<Integer, ZWaveIPNode> zWaveIPNodeList = new HashMap<>();
    private Map<String, Integer> zIPAddressToNodeIdMap = new HashMap<>();

    public ZIPGWHandler(ZIPGWIntegration zipgwIntegration, String zipgwAddress, String psk, boolean useDtls) {
        this.zipgwIntegration = zipgwIntegration;
        this.zipgwAddress = zipgwAddress;
        this.useDtls = useDtls;
        if (this.useDtls) {
            this.psk = psk;
        } else {
            this.psk = null;
        }
    }

    public Map<Integer, ZWaveIPNode> getzWaveIPNodeList() {
        return zWaveIPNodeList;
    }

    @Override
    public void receiveData(ZIPRawData raw) {
        if (logger.isInfoEnabled()) {
            logger.info("Received request: {}", HexUtils.byteArrayToHexString(raw.getBytes()));
        }

        byte[] rawBytes = raw.getBytes();
        if (rawBytes.length > 1) {
            if (rawBytes[0] == 0x23 && rawBytes[1] == 0x02) {
                // ZIP packet
                ZipPacket zipPacket = new ZipPacket();
                zipPacket.setPayload(ByteUtils.byteArrayToShortList(rawBytes, 2));
                if (zipPacket.getAckRequest()) {
                    //send ack response
                    logger.warn("TODO: Need to send ack!");
                }

                if (zipPacket.getzWaveCmdIncluded() && zipPacket.getzWaveCommand() != null) {
                    Integer nodeId = zIPAddressToNodeIdMap.get(raw.getAddress().getHostName());
                    if (nodeId != null) {
                        sendDeviceMessage(nodeId, ByteUtils.shortListToByteArray(zipPacket.getzWaveCommand()));
                    }
                }
            }
        }
    }

    @Override
    public void receiveData(final RawData raw) {
        if (logger.isInfoEnabled()) {
            logger.info("Received request: {}", HexUtils.byteArrayToHexString(raw.getBytes()));
        }

        byte[] rawBytes = raw.getBytes();
        if (rawBytes.length > 1) {
            if (rawBytes[0] == 0x23 && rawBytes[1] == 0x02) {
                // ZIP packet
                ZipPacket zipPacket = new ZipPacket();
                zipPacket.setPayload(ByteUtils.byteArrayToShortList(rawBytes, 2));
                if (zipPacket.getAckRequest()) {
                    //send ack response
                    logger.warn("TODO: Need to send ack!");
                }

                if (zipPacket.getzWaveCmdIncluded() && zipPacket.getzWaveCommand() != null) {
                    Integer nodeId = zIPAddressToNodeIdMap.get(raw.getInetSocketAddress().getHostString());
                    if (nodeId != null) {
                        sendDeviceMessage(nodeId, ByteUtils.shortListToByteArray(zipPacket.getzWaveCommand()));
                    }
                }
            }
        }
    }

    public void start() throws UnknownHostException {
        InetAddress inetAddress = Inet6Address.getByName(zipgwAddress);
        zWaveIPGateway = new ZWaveIPGateway(inetAddress, psk);

        int listeningPort;
        if (useDtls) {
            listeningPort = 41230;
        } else {
            listeningPort = 4123;
        }

        InetAddress localAddress = null;
        try {
            DatagramSocket soc = new DatagramSocket();
            soc.connect(inetAddress, listeningPort);
            localAddress = soc.getLocalAddress();
        } catch (IOException ioException) {
            logger.warn("Exception while connecting datagram socket", ioException);
            localAddress = null;
        }

        if (useDtls) {
            zipGWListener = new PSKDtlsServer(this, listeningPort, psk);
        } else {
            zipGWListener = new DatagramServer(this, listeningPort);
        }
        zipGWListener.start();

        // set unsolicited listener
        try {
            zWaveIPGateway.setUnsolicitedDestination(localAddress, listeningPort);
        } catch (ZWaveIPException zWaveIPException) {
            // try again
            try {
                zWaveIPGateway.setUnsolicitedDestination(localAddress, listeningPort);
            } catch (ZWaveIPException zWaveIPException1) {
                logger.warn("Unable to set unsolicited desination id address", zWaveIPException1);
            }
        }

        //get list of nodes
        NodeListReport nodeListReport = zWaveIPGateway.getNodeListReport();
        if (nodeListReport != null) {
            this.controllerId = nodeListReport.getNodeListControllerId();
            buildNodeList(nodeListReport.getNodeList(), nodeListReport.getNodeListControllerId());
        }
    }

    private void buildNodeList(List<Short> shortNodeList, int nodeListControllerId) {
        for (int byteIndex = 0; byteIndex < shortNodeList.size(); byteIndex++) {
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                int bit = (shortNodeList.get(byteIndex) >> bitIndex) & 1;
                if (bit > 0) {
                    int nodeId = (bitIndex + 1) + (byteIndex * 8);
                    if (nodeId != nodeListControllerId) {
                        InetAddress ipv6Address = null;
                        try {
                            ipv6Address = zWaveIPGateway.getNodeIpv6Address(nodeId);
                        } catch (UnknownHostException e) {
                            logger.warn("Unknown host when attempting to get ip address of node", e);
                        }
                        if (ipv6Address != null) {
                            // get Node Info
                            NodeInfoCachedReport nodeInfoCachedReport = zWaveIPGateway.getNodeInfoCachedReport(nodeId);

                            zWaveIPNodeList.put(nodeId, new ZWaveIPNode(this, nodeId, ipv6Address, psk, nodeInfoCachedReport));
                            zIPAddressToNodeIdMap.put(ipv6Address.getHostAddress(), nodeId);
                        }
                    }
                }
            }
        }
    }

    public void stop() {
        if (zipGWListener != null) {
            zipGWListener.stop();
        }
        if (zWaveIPGateway != null) {
            zWaveIPGateway.shutdown();
        }
    }

    public void sendDeviceMessage(int nodeId, byte[] message) {
        // send message to user code
        String deviceNetworkId = HexUtils.integerToHexString(nodeId, 1);
        String command = HexUtils.byteArrayToHexString(Arrays.copyOfRange(message, 0, 2));
        String payload = "";
        if (message.length > 2) {
            payload = HexUtils.byteArrayToHexString(Arrays.copyOfRange(message, 2, message.length), true);
        }

        String zWaveMessage = String.format("zw device: %s, command: %s, payload: %s", deviceNetworkId, command, payload);
        if (logger.isDebugEnabled()) {
            logger.debug("ZWave message from " + zWaveMessage);
        }

        zipgwIntegration.sendEvent(new DeviceMessageEvent(HexUtils.integerToHexString(nodeId, 1), zWaveMessage));
    }

    public boolean reset() {
        return zWaveIPGateway.reset();
    }

    public boolean startNodeAdd() {
        if (zWaveIPGateway != null) {
            // stop node add after 60 seconds if no response
            TimerTask task = new TimerTask() {
                public void run() {
                    stopNodeAdd();
                }
            };
            Timer timer = new Timer("ZWaveNodeAddTimer");
            timer.schedule(task, 60 * 1000);

            // start node add then wait for response
            new Thread(() -> {
                NodeAddStatus nodeAddStatus = zWaveIPGateway.startNodeAdd();
                zipgwIntegration.processNodeAdd(nodeAddStatus);
                timer.cancel();

                if (Objects.equals(nodeAddStatus.getStatus(), NodeAddStatus.ADD_NODE_STATUS_DONE)) {
                    int newNodeId = nodeAddStatus.getNewNodeId();
                    InetAddress ipv6Address = null;
                    try {
                        ipv6Address = zWaveIPGateway.getNodeIpv6Address(nodeAddStatus.getNewNodeId());
                    } catch (UnknownHostException e) {
                        logger.warn("Unknown host when attempting to get ip address of node", e);
                    }

                    // get Node Info
                    NodeInfoCachedReport nodeInfoCachedReport = zWaveIPGateway.getNodeInfoCachedReport(nodeAddStatus.getNewNodeId());

                    if (ipv6Address != null) {
                        zWaveIPNodeList.put((int) nodeAddStatus.getNewNodeId(),
                                new ZWaveIPNode(this, (int) nodeAddStatus.getNewNodeId(), ipv6Address, psk, nodeInfoCachedReport));
                        zIPAddressToNodeIdMap.put(ipv6Address.getHostAddress(), (int) nodeAddStatus.getNewNodeId());
                    }


                    ZWaveIPNode zWaveIPNode = getZWaveIPNode(newNodeId);
                    if (zWaveIPNode != null) {
                        //get Manufacturer specifc report
                        ManufacturerSpecificReport manufacturerSpecificReport = null;
                        Command zWaveCommand = zWaveIPNode.sendZWaveCommandAndGetResponse(new ManufacturerSpecificGet(),
                                ZWaveCommandEnum.ManufacturerSpecificReport.getKey());
                        if (zWaveCommand instanceof ManufacturerSpecificReport) {
                            manufacturerSpecificReport = (ManufacturerSpecificReport) zWaveCommand;
                        }
                        //TODO: if manufacturer specific report is null, try again.

                        // get version report
                        VersionReport versionReport = null;
                        zWaveCommand = zWaveIPNode.sendZWaveCommandAndGetResponse(new VersionGet(), ZWaveCommandEnum.VersionReport.getKey());
                        if (zWaveCommand instanceof VersionReport) {
                            versionReport = (VersionReport) zWaveCommand;
                        }
                        //TODO: if version report is null, try again.

                        //set association group 1
                        AssociationSet associationSet = new AssociationSet();
                        associationSet.setGroupingIdentifier((short) 0x01);
                        //TODO: send controller node id, don't assume it is 1
                        associationSet.setNodeId(List.of((short) 0x01));
                        zWaveIPNode.sendZWaveCommand(associationSet.format());

                        // raw description from ST:
                        //zw:Ss type:2101 mfr:0086 prod:0102 model:0064 ver:1.04 zwv:4.05 lib:03 cc:5E,86,72,98,84 ccOut:5A sec:59,85,73,71,80,30,31,70,7A role:06 ff:8C07 ui:8C07

                        Map<String, String> rawDescription = new HashMap<>();

                        String zw = (nodeInfoCachedReport.getListening() ? "L" : ((nodeInfoCachedReport.getSecurity() & 0x10) == 0x10 ? "F" : "S"));
                        if ((nodeInfoCachedReport.getGrantedKeys() & 0x04) == 0x04) {
                            zw = zw + "s2ac";
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x02) == 0x02) {
                            zw = zw + "s2a";
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x01) == 0x01) {
                            zw = zw + "s2u";
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x80) == 0x80) {
                            zw = zw + "s";
                        }
                        rawDescription.put("zw", zw);

                        rawDescription.put("type", HexUtils.integerToHexString(nodeInfoCachedReport.getGenericDeviceClass(), 1) +
                                HexUtils.integerToHexString(nodeInfoCachedReport.getSpecificDeviceClass(), 1));
                        if (manufacturerSpecificReport != null) {
                            rawDescription.put("mfr", HexUtils.integerToHexString(manufacturerSpecificReport.getManufacturerId(), 2));
                            rawDescription.put("prod", HexUtils.integerToHexString(manufacturerSpecificReport.getProductId(), 2));
                            rawDescription.put("model", HexUtils.integerToHexString(manufacturerSpecificReport.getProductTypeId(), 2));
                        }

                        if (versionReport != null) {
                            rawDescription.put("ver",
                                    String.format("%d.%02d", versionReport.getFirmware0Version(), versionReport.getFirmware0SubVersion()));
                            rawDescription.put("zwv",
                                    String.format("%d.%02d", versionReport.getZWaveProtocolVersion(), versionReport.getZWaveProtocolSubVersion()));
                            rawDescription.put("lib", HexUtils.integerToHexString(versionReport.getZWaveLibraryType(), 1));
                        }

                        Map<String, List<Short>> commandClassMap = getCommandClasses(nodeInfoCachedReport.getCommandClass());
                        for (String key : commandClassMap.keySet()) {
                            rawDescription.put(key, HexUtils.shortListToHexString(commandClassMap.get(key), true));
                        }

                        //TODO: handle role: This indicates the Z-Wave Plus Role Type.
                        //TODO: handle ff: This stands for “form factor” and corresponds to the Z-Wave+ Installer Icon type (An offset of 0x8000 is added for implementation reasons).
                        //TODO: handle ui: This corresponds to the Z-Wave+ User Icon type.

                        Map<String, Object> deviceData = new HashMap<>();
                        // ZWAVE_S0_DOWNGRADE ?
                        // ZWAVE_S0_FAILED ?
                        // ZWAVE_S2_FAILED ?
                        if ((nodeInfoCachedReport.getGrantedKeys() & 0x04) == 0x04) {
                            deviceData.put("networkSecurityLevel", "ZWAVE_S2_ACCESS_CONTROL");
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x02) == 0x02) {
                            deviceData.put("networkSecurityLevel", "ZWAVE_S2_AUTHENTICATED");
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x01) == 0x01) {
                            deviceData.put("networkSecurityLevel", "ZWAVE_S2_UNAUTHENTICATED");
                        } else if ((nodeInfoCachedReport.getGrantedKeys() & 0x80) == 0x80) {
                            deviceData.put("networkSecurityLevel", "ZWAVE_S0_LEGACY");
                        } else {
                            deviceData.put("networkSecurityLevel", "ZWAVE_LEGACY_NON_SECURE");
                        }
                        Map<String, String> additionalIntegrationParameters = new HashMap<>();
                        additionalIntegrationParameters.put("zwaveInfo", new JsonBuilder(rawDescription).toString());
                        additionalIntegrationParameters.put("zwaveHubNodeId", this.controllerId.toString());
                        zipgwIntegration.sendEvent(new DeviceAddedEvent(HexUtils.integerToHexString(newNodeId, 1), true, rawDescription, deviceData,
                                additionalIntegrationParameters));
                    }
                } else if (Objects.equals(nodeAddStatus.getStatus(), NodeAddStatus.ADD_NODE_STATUS_FAILED) ||
                        Objects.equals(nodeAddStatus.getStatus(), NodeAddStatus.ADD_NODE_STATUS_SECURITY_FAILED)) {
                    logger.debug("Failed to add device");
                }
            }).start();

            return true;
        }
        return false;
    }

    private Map<String, List<Short>> getCommandClasses(List<Short> fullCCList) {
        Map<String, List<Short>> commandClasses = new HashMap<>();

        if (fullCCList != null && fullCCList.size() > 0) {
            List<Short> nonSecureCCs = null;
            List<Short> secureCCs = null;
            int securitySchemeMarkIndex = fullCCList.indexOf((short) 0xF1);

            if (securitySchemeMarkIndex >= 0 && securitySchemeMarkIndex + 1 <= fullCCList.size() &&
                    fullCCList.get(securitySchemeMarkIndex + 1) == 0x00) {
                if (securitySchemeMarkIndex > 0) {
                    nonSecureCCs = fullCCList.subList(0, securitySchemeMarkIndex);
                }
                if (fullCCList.size() > securitySchemeMarkIndex + 1) {
                    secureCCs = fullCCList.subList(securitySchemeMarkIndex + 2, fullCCList.size());
                }
            } else {
                nonSecureCCs = fullCCList;
            }

            List<Short> cc = null;
            List<Short> ccOut = null;
            if (nonSecureCCs != null) {
                int nonSecureSupportControlMarkIndex = nonSecureCCs.indexOf((short) 0xEF);
                if (nonSecureSupportControlMarkIndex > 0) {
                    cc = nonSecureCCs.subList(0, nonSecureSupportControlMarkIndex);
                }
                if (nonSecureSupportControlMarkIndex >= 0 && nonSecureSupportControlMarkIndex < nonSecureCCs.size()) {
                    ccOut = nonSecureCCs.subList(nonSecureSupportControlMarkIndex + 1, nonSecureCCs.size());
                }
            }

            List<Short> sec = null;
            List<Short> secOut = null;
            if (secureCCs != null) {
                int secureSupportControlMarkIndex = secureCCs.indexOf((short) 0xEF);
                if (secureSupportControlMarkIndex > 0) {
                    sec = secureCCs.subList(0, secureSupportControlMarkIndex);
                }
                if (secureSupportControlMarkIndex >= 0 && secureSupportControlMarkIndex < secureCCs.size()) {
                    secOut = secureCCs.subList(secureSupportControlMarkIndex + 1, secureCCs.size());
                }
            }

            if (cc != null) {
                commandClasses.put("cc", cc);
            }
            if (ccOut != null) {
                commandClasses.put("ccOut", ccOut);
            }
            if (sec != null) {
                commandClasses.put("sec", sec);
            }
            if (secOut != null) {
                commandClasses.put("secOut", secOut);
            }
        }

        return commandClasses;
    }

    public boolean stopNodeAdd() {
        if (zWaveIPGateway != null) {
            return zWaveIPGateway.stopNodeAdd();
        }
        return false;
    }

    public boolean startNodeRemove() {
        if (zWaveIPGateway != null) {
            // stop node remove after 30 seconds if no response
            TimerTask task = new TimerTask() {
                public void run() {
                    stopNodeRemove();
                }
            };
            Timer timer = new Timer("ZWaveNodeRemoveTimer");
            timer.schedule(task, 30 * 1000);

            // start node remove then wait for response
            new Thread(() -> {
                NodeRemoveStatus nodeRemoveStatus = zWaveIPGateway.startNodeRemove();
                zipgwIntegration.processExcludeStatus(nodeRemoveStatus);
                timer.cancel();
            }).start();

            return true;
        }
        return false;
    }

    public boolean stopNodeRemove() {
        if (zWaveIPGateway != null) {
            return zWaveIPGateway.stopNodeRemove();
        }
        return false;
    }


    private ZWaveIPNode getZWaveIPNode(int nodeId) {
        if (!zWaveIPNodeList.containsKey(nodeId)) {
            return null;
            //zWaveIPNodeList.put(nodeId, new ZWaveIPNode());
        }
        return zWaveIPNodeList.get(nodeId);
    }

    public void sendZWaveCommand(int nodeId, String cmd) {
        ZWaveIPNode zWaveIPNode = getZWaveIPNode(nodeId);
        if (zWaveIPNode != null) {
            zWaveIPNode.sendZWaveCommand(cmd);
        }
    }
}
