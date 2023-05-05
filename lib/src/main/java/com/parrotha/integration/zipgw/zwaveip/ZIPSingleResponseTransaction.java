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
package com.parrotha.integration.zipgw.zwaveip;

import com.parrotha.helper.HexUtils;

public class ZIPSingleResponseTransaction implements ZIPTransaction {
    private String request;
    private byte[] response;
    private byte requiredResponseCommandClass;
    private byte requiredResponseCommand;
    private boolean rawMessage = false;
    private int timeout = 10;

    public ZIPSingleResponseTransaction(String request, byte requiredResponseCommandClass, byte requiredResponseCommand) {
        this.request = request;
        this.requiredResponseCommandClass = requiredResponseCommandClass;
        this.requiredResponseCommand = requiredResponseCommand;
    }

    public ZIPSingleResponseTransaction(String request, String commandClassAndCommand) {
        this.request = request;
        byte[] bytes = HexUtils.hexStringToByteArray(commandClassAndCommand);
        this.requiredResponseCommandClass = bytes[0];
        this.requiredResponseCommand = bytes[1];
    }

    public ZIPSingleResponseTransaction(String request, String commandClassAndCommand, boolean rawMessage) {
        this.request = request;
        byte[] bytes = HexUtils.hexStringToByteArray(commandClassAndCommand);
        this.requiredResponseCommandClass = bytes[0];
        this.requiredResponseCommand = bytes[1];
        this.rawMessage = rawMessage;
    }

    @Override
    public boolean isMatch(byte[] response) {
        if (response[0] == requiredResponseCommandClass && response[1] == requiredResponseCommand) {
            this.response = response;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isRawMessage() {
        return rawMessage;
    }

    @Override
    public String getRequest() {
        return request;
    }

    @Override
    public byte[] getResponse() {
        return response;
    }

    @Override
    public int getTimeout() {
        return timeout;
    }
}
