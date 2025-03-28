/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.replacements.amd64;

import static org.graalvm.compiler.nodeinfo.InputType.Memory;
import static org.graalvm.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_512;

import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValueNodeUtil;
import org.graalvm.compiler.nodes.memory.MemoryAccess;
import org.graalvm.compiler.nodes.memory.MemoryKill;
import org.graalvm.compiler.nodes.memory.MultiMemoryKill;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

@NodeInfo(allowedUsageTypes = Memory, size = SIZE_512, cycles = CYCLES_UNKNOWN, cyclesRationale = "depends on length")
public final class AMD64StringUTF16CompressNode extends FixedWithNextNode
                implements LIRLowerable, MultiMemoryKill, MemoryAccess {

    public static final NodeClass<AMD64StringUTF16CompressNode> TYPE = NodeClass.create(AMD64StringUTF16CompressNode.class);

    @Input private ValueNode src;
    @Input private ValueNode dst;
    @Input private ValueNode len;
    final JavaKind readKind;

    @OptionalInput(Memory) private MemoryKill lla; // Last access location registered.

    // java.lang.StringUTF16.compress([CI[BII)I
    //
    // int compress(char[] src, int src_indx, byte[] dst, int dst_indx, int len)
    //
    // Represented as a graph node by:

    public AMD64StringUTF16CompressNode(ValueNode src, ValueNode dst, ValueNode len, JavaKind readKind) {
        super(TYPE, StampFactory.forInteger(32));
        this.src = src;
        this.dst = dst;
        this.len = len;
        this.readKind = readKind;
    }

    @Override
    public LocationIdentity getLocationIdentity() {
        // Model read access via 'src' using:
        return NamedLocationIdentity.getArrayLocation(readKind);
    }

    @Override
    public LocationIdentity[] getKilledLocationIdentities() {
        // Model write access via 'dst' using:
        return new LocationIdentity[]{NamedLocationIdentity.getArrayLocation(JavaKind.Byte)};
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        LIRGeneratorTool lgt = gen.getLIRGeneratorTool();
        Value res = lgt.emitStringUTF16Compress(gen.operand(src), gen.operand(dst), gen.operand(len));
        gen.setResult(this, res);
    }

    @Override
    public MemoryKill getLastLocationAccess() {
        return lla;
    }

    @Override
    public void setLastLocationAccess(MemoryKill newlla) {
        updateUsages(ValueNodeUtil.asNode(lla), ValueNodeUtil.asNode(newlla));
        lla = newlla;
    }

    @NodeIntrinsic
    public static native int compress(Pointer src, Pointer dst, int len, @ConstantNodeParameter JavaKind readKind);
}
