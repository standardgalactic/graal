/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle.test.strings;

import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.replacements.ArrayIndexOfNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import jdk.vm.ci.meta.ResolvedJavaMethod;

@RunWith(Parameterized.class)
public class TStringOpsIndexOfAnyTest extends TStringOpsTest<ArrayIndexOfNode> {

    @Parameters(name = "{index}: offset: {1}, length: {2}, stride: {3}, fromIndex: {4}, toIndex: {5}")
    public static List<Object[]> data() {
        ArrayList<Object[]> ret = new ArrayList<>();
        int offset = 20;
        int padding = 20;
        for (int strideA = 0; strideA < 3; strideA++) {
            int contentLength = 256;
            byte[] arrayA = createTestArray(offset, padding, strideA, contentLength);
            for (int strideB = 0; strideB < 3; strideB++) {
                int[] offsets = {
                                offset << strideA,
                                (offset + (contentLength / 2)) << strideA};
                for (int offsetA : offsets) {
                    for (int iFromIndex = 0; iFromIndex < 3; iFromIndex++) {
                        int fromIndexA = contentLength * iFromIndex;
                        for (int lengthA : new int[]{0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 17, 31, 32, 33, 47, 48, 49, 63, 64, 65, 127, 128, 129, 255, 256}) {
                            if (fromIndexA >= lengthA) {
                                continue;
                            }
                            int off = (offsetA >> strideA) + fromIndexA;
                            int v0 = readValue(arrayA, strideA, off);
                            int v1 = readValue(arrayA, strideA, off + 1);
                            int vBefore = readValue(arrayA, strideA, off - 1);
                            int vLast = readValue(arrayA, strideA, off + lengthA - 1);
                            assert lengthA <= 129 || offsetA != offsets[0] || ((byte) vLast) < 0 : vLast;
                            int vAfter = readValue(arrayA, strideA, off + lengthA);
                            for (int v : new int[]{v0, v1, vBefore, vLast, vAfter}) {
                                ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{v}});
                                ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{v, ~0}});
                            }
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{0}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{v0, v1}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vBefore, v0}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vLast, v0}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vLast, v0}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vAfter, v0}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vAfter, vLast, vLast}});
                            ret.add(new Object[]{arrayA, offsetA, lengthA, strideA, fromIndexA, new int[]{vAfter, vLast, v1, v0}});
                        }
                    }
                }
            }
        }
        return ret;
    }

    private static byte[] createTestArray(int offset, int padding, int stride, int contentLength) {
        byte[] array = new byte[(offset + (contentLength * 3) + padding) << stride];
        int[] valueOffset = {0, 0x1000, 0x10_0000};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < contentLength; j++) {
                writeValue(array, stride, offset + j + (contentLength * i), j + valueOffset[i]);
            }
        }
        return array;
    }

    final byte[] arrayA;
    final int offsetA;
    final int lengthA;
    final int strideA;
    final int fromIndexA;
    final int[] values;

    public TStringOpsIndexOfAnyTest(byte[] arrayA, int offsetA, int lengthA, int strideA, int fromIndexA, int[] values) {
        super(ArrayIndexOfNode.class);
        this.arrayA = arrayA;
        this.offsetA = offsetA;
        this.lengthA = lengthA;
        this.strideA = strideA;
        this.fromIndexA = fromIndexA;
        this.values = values;
    }

    @Test
    public void testIndexOfAny() throws ClassNotFoundException {
        if (strideA == 0) {
            ResolvedJavaMethod method = getTStringOpsMethod("indexOfAnyByteIntl",
                            Object.class, int.class, int.class, int.class, byte[].class);
            byte[] valuesB = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                valuesB[i] = (byte) values[i];
            }
            test(method, null, DUMMY_LOCATION, arrayA, offsetA, lengthA, fromIndexA, valuesB);
        }

        if (strideA < 2) {
            ResolvedJavaMethod method = getTStringOpsMethod("indexOfAnyCharIntl",
                            Object.class, int.class, int.class, int.class, int.class, char[].class);
            char[] valuesC = new char[values.length];
            for (int i = 0; i < values.length; i++) {
                valuesC[i] = (char) (strideA == 0 ? values[i] & 0xff : values[i]);
            }
            test(method, null, DUMMY_LOCATION, arrayA, offsetA, lengthA, strideA, fromIndexA, valuesC);
        }

        ResolvedJavaMethod method = getTStringOpsMethod("indexOfAnyIntIntl",
                        Object.class, int.class, int.class, int.class, int.class, int[].class);
        int[] valuesI = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            valuesI[i] = strideA == 0 ? values[i] & 0xff : strideA == 1 ? values[i] & 0xffff : values[i];
        }
        test(method, null, DUMMY_LOCATION, arrayA, offsetA, lengthA, strideA, fromIndexA, valuesI);
    }
}
