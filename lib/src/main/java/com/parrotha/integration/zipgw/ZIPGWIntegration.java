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

import com.parrotha.device.HubAction;
import com.parrotha.device.HubResponse;
import com.parrotha.device.Protocol;
import com.parrotha.integration.DeviceIntegration;
import com.parrotha.integration.extension.DeviceExcludeIntegrationExtension;
import com.parrotha.integration.extension.DeviceScanIntegrationExtension;
import com.parrotha.integration.extension.ResetIntegrationExtension;
import com.parrotha.internal.utils.HexUtils;
import com.parrotha.ui.PreferencesBuilder;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeAddStatus;
import com.parrotha.zwave.commands.networkmanagementinclusionv3.NodeRemoveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

public class ZIPGWIntegration extends DeviceIntegration implements DeviceExcludeIntegrationExtension, DeviceScanIntegrationExtension,
        ResetIntegrationExtension {
    private static final Logger logger = LoggerFactory.getLogger(ZIPGWIntegration.class);

    private ZIPGWHandler zipgwHandler;

    private static final List<String> tags = List.of("PROTOCOL_ZWAVE");

    @Override
    public void start() {

        boolean disabled = "true".equals(getSettingAsString("disabled"));
        if (disabled) {
            return;
        }

        String address = getSettingAsString("zipGWAddress");
        // default address of z/ip gateway from config file
        if (address == null || address.length() == 0) {
            address = "fd00:aaaa::3";
        }

        String pskPassword = getSettingAsString("pskPassword");
        // default psk of z/ip gateway from config file
        if (pskPassword == null || pskPassword.length() == 0) {
            pskPassword = "123456789012345678901234567890AA";
        }

        // make this true by default unless user specifies false
        boolean useDtls = !"false".equals(getSettingAsString("useDtls"));

        zipgwHandler = new ZIPGWHandler(this, address, pskPassword, useDtls);
        try {
            zipgwHandler.start();
        } catch (UnknownHostException e) {
            logger.warn("Exception", e);
        }
    }

    @Override
    public void stop() {
        if (zipgwHandler != null) {
            zipgwHandler.stop();
        }
    }

    @Override
    public Protocol getProtocol() {
        return Protocol.ZWAVE;
    }

    @Override
    public List<String> getTags() {
        return tags;
    }

    @Override
    public String getName() {
        return "Z-Wave via Z/IP Gateway";
    }

    @Override
    public String getDescription() {
        ResourceBundle messages = ResourceBundle.getBundle("com.parrotha.zipgw.MessageBundle");
        return messages.getString("integration.description");
    }

    @Override
    public Map<String, String> getDisplayInformation() {
        return null;
    }

    @Override
    public boolean removeIntegrationDevice(String deviceNetworkId) {
        //TODO: start exclusion process and trigger a request for user to exclude device
        return true;
    }

    @Override
    public HubResponse processAction(HubAction hubAction) {
        zipgwHandler.sendZWaveCommand(HexUtils.hexStringToInt(hubAction.getDni()), hubAction.getAction());
        return null;
    }

    // the layout for the integration page
    @Override
    public List<Map<String, Object>> getPageLayout() {
        List<Map<String, Object>> sections = new ArrayList<>();
        List<Map<String, Object>> bodyList = new ArrayList<>();
        List<Map<String, Object>> columnList = new ArrayList<>();

        columnList.add(Map.of("name", "nodeId", "title", "Node ID", "data", "id"));
        bodyList.add(Map.of("name", "deviceTable", "title", "Devices", "type", "table", "columns", columnList, "data", "nodes"));
        sections.add(Map.of("name", "deviceList", "title", "Device List", "body", bodyList));

        return sections;
    }

    @Override
    public Map<String, Object> getPageData() {
        Map<String, Object> pageData = new HashMap<>();

        Map<Integer, ZWaveIPNode> nodeList = zipgwHandler.getzWaveIPNodeList();
        List<Map> nodes = new ArrayList<>();
        for (Integer nodeId : nodeList.keySet()) {
            nodes.add(Map.of("id", "0x" + HexUtils.integerToHexString(nodeId, 1)));
        }
        pageData.put("nodes", nodes);
        pageData.put("excludeRunning", false);
        pageData.put("excludeStopped", true);
        return pageData;
    }

    private boolean excludeRunning = false;
    private List<Map<String, String>> excludedDevices;
    private String excludeMessage = null;

    @Override
    public boolean startExclude(Map options) {
        excludeMessage = null;
        excludedDevices = null;
        excludeRunning = zipgwHandler.startNodeRemove();

        return excludeRunning;
    }

    @Override
    public boolean stopExclude(Map options) {
        if (zipgwHandler.stopNodeRemove()) {
            excludeRunning = false;
            return true;
        }
        return false;
    }

    public void processExcludeStatus(NodeRemoveStatus nodeRemoveStatus) {
        excludeRunning = false;
        if (nodeRemoveStatus.getStatus() == NodeRemoveStatus.REMOVE_NODE_STATUS_DONE) {
            excludeMessage = "Successfully excluded device";
            if (excludedDevices == null) {
                excludedDevices = new ArrayList<>();
            }
            if (nodeRemoveStatus.getNodeId() != 0x00) {
                excludedDevices.add(Map.of("deviceNetworkId", HexUtils.integerToHexString(nodeRemoveStatus.getNodeId(), 1)));
                deleteItem(HexUtils.integerToHexString(nodeRemoveStatus.getNodeId(), 1));
            } else {
                excludedDevices.add(Map.of("deviceNetworkId", "Unknown"));
            }

        } else if (nodeRemoveStatus.getStatus() == NodeRemoveStatus.REMOVE_NODE_STATUS_FAILED) {
            excludeMessage = "Failed to excluded device";
        }
    }

    @Override
    public Map getExcludeStatus(Map options) {
        Map<String, Object> excludeStatus = new HashMap<>();
        excludeStatus.put("running", excludeRunning);
        if (excludedDevices != null && excludedDevices.size() > 0) {
            excludeStatus.put("excludedDevices", excludedDevices);
        }
        return excludeStatus;
    }

    private boolean scanRunning = false;
    private List<Map<String, String>> addedDevices;
    private String addMessage = null;

    @Override
    public boolean startScan(Map options) {
        scanRunning = zipgwHandler.startNodeAdd();
        return scanRunning;
    }

    public void processNodeAdd(NodeAddStatus nodeAddStatus) {
        scanRunning = false;
        if (nodeAddStatus.getStatus() == NodeAddStatus.ADD_NODE_STATUS_DONE) {
            logger.debug("New device added: " + nodeAddStatus);
            addMessage = "Successfully added device";
            if (addedDevices == null) {
                addedDevices = new ArrayList<>();
            }
            addedDevices.add(Map.of("deviceNetworkId", HexUtils.integerToHexString(nodeAddStatus.getNewNodeId(), 1)));

            //TODO: process node and add to devices

            // get Node Info
            // get Manufacturer specifc report
            // set association group 1

        } else if (nodeAddStatus.getStatus() == NodeAddStatus.ADD_NODE_STATUS_FAILED ||
                nodeAddStatus.getStatus() == NodeAddStatus.ADD_NODE_STATUS_SECURITY_FAILED) {
            addMessage = "Failed to add device";
        }
    }

    @Override
    public boolean stopScan(Map options) {
        if (zipgwHandler.stopNodeAdd()) {
            scanRunning = false;
            return true;
        }
        return false;
    }

    @Override
    public Map getScanStatus(Map options) {
        Map<String, Object> scanStatus = new HashMap<>();
        scanStatus.put("running", scanRunning);
        if (addedDevices != null && addedDevices.size() > 0) {
            scanStatus.put("foundDevices", addedDevices);
        }
        return scanStatus;
    }

    @Override
    public boolean reset(Map options) {
        stop();
        return zipgwHandler.reset();
    }

    @Override
    public String getResetWarning() {
        ResourceBundle messages = ResourceBundle.getBundle("com.parrotha.zipgw.MessageBundle");
        return messages.getString("reset.warning");
    }

    @Override
    public Map<String, Object> getPreferencesLayout() {
        return new PreferencesBuilder()
                .withBoolInput("disabled",
                        "Disable",
                        "Disable Z/IP Gateway Integration",
                        false,
                        true)
                .withTextInput("zipGWAddress",
                        "Z/IP GW Address",
                        "Z/IP Gateway Address (Leave blank for default)",
                        false,
                        true)
                .withTextInput("pskPassword",
                        "PSK Password",
                        "PSK Password",
                        false,
                        true)
                .withBoolInput("useDtls",
                        "Use DTLS",
                        "Use DTLS with communicating with Z/IP Gateway",
                        false,
                        true)
                .build();
    }

    @Override
    public void settingValueChanged(List<String> keys) {
        if (logger.isDebugEnabled()) {
            logger.debug("values changed " + keys);
        }
        if (keys.contains("disabled")) {
            // restart the integration
            this.stop();
            this.start();
        }
    }

}
