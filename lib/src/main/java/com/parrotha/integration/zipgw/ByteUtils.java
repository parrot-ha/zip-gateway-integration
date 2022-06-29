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

import java.util.ArrayList;
import java.util.List;

public class ByteUtils {
    public static List<Short> byteArrayToShortList(byte[] bytes, int offset) {
        List<Short> shortList = new ArrayList<>();
        for (int i = offset; i < bytes.length; i++)
            shortList.add((short) bytes[i]);
        return shortList;
    }

    public static byte[] shortListToByteArray(List<Short> shortList) {
        byte[] bytes = new byte[shortList.size()];
        for (int i = 0; i < shortList.size(); i++)
            bytes[i] = shortList.get(i).byteValue();

        return bytes;
    }
}
