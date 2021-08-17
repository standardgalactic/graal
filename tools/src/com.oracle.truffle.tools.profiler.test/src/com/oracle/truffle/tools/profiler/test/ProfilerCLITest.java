/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.profiler.test;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.instrumentation.test.InstrumentationTestLanguage;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.ProfilerNode;
import com.oracle.truffle.tools.utils.json.JSONArray;
import com.oracle.truffle.tools.utils.json.JSONObject;

public class ProfilerCLITest {

    public static final String SAMPLING_HISTOGRAM = "Sampling Histogram. Recorded [0-9]* samples with period [0-9]*ms.";
    public static final int EXEC_COUUNT = 10;
    public static final String NAME_REGEX = " [a-z]+ +";
    public static final String SEPARATOR_REGEX = "\\|";
    public static final String TIME_REGEX = " *[0-9]*ms ";
    public static final String PERCENT_REGEX = " *[0-9]*\\.[0-9]\\% ";
    public static final String LOCATION_REGEX = " test.*";
    public static final String SELF_TIME = "  Self Time: Time spent on the top of the stack.";
    public static final String TOTAL_TIME = "  Total Time: Time spent somewhere on the stack.";
    public static final String INTERPRETER = "  T0: Percent of time spent in interpreter.";
    public static final String T1 = "  T1: Percent of time spent in code compiled by tier 1 compiler.";
    public static final String T2 = "  T2: Percent of time spent in code compiled by tier 2 compiler.";

    protected Source makeSource(String s) {
        return Source.newBuilder(InstrumentationTestLanguage.ID, s, "test").buildLiteral();
    }

    @Test
    public void testDefaultSampleHistogram() {
        HashMap<String, String> options = new HashMap<>();
        options.put("cpusampler", "true");
        String[] output = runSampler(options);
        // @formatter:off
        // OUTPUT IS:
        // -------------------------------------------------------------------------------
        // Sampling Histogram. Recorded 202 samples with period 10ms.
        Assert.assertTrue(output[1].matches(SAMPLING_HISTOGRAM));
        // Self Time: Time spent on the top of the stack.
        Assert.assertEquals(SELF_TIME, output[2]);
        // Total Time: Time spent somewhere on the stack.
        Assert.assertEquals(TOTAL_TIME, output[3]);
        // -------------------------------------------------------------------------------
        // Thread[main,5,main]
        Assert.assertTrue(output[5].contains("Thread"));
        // Name       |      Total Time    ||       Self Time    || Location
        Assert.assertTrue(output[6].contains("Name       |      Total Time    ||       Self Time    || Location"));
        // -------------------------------------------------------------------------------
        // foo        |             1900ms ||             1900ms || test~1:16-29
        String lineRegex = NAME_REGEX + SEPARATOR_REGEX + TIME_REGEX + SEPARATOR_REGEX + SEPARATOR_REGEX + TIME_REGEX + SEPARATOR_REGEX + SEPARATOR_REGEX + LOCATION_REGEX;
        Assert.assertTrue(output[8].matches(lineRegex));
        // baz        |             1710ms ||                0ms || test~1:98-139
        Assert.assertTrue(output[9].matches(lineRegex));
        // bar        |             1900ms ||                0ms || test~1:43-84
        Assert.assertTrue(output[10].matches(lineRegex));
        //            |             1900ms ||                0ms || test~1:0-161
        // -------------------------------------------------------------------------------
        // @formatter:on
    }

    @Test
    public void testShowTiersTrueHistogram() {
        HashMap<String, String> options = new HashMap<>();
        options.put("cpusampler", "true");
        options.put("cpusampler.ShowTiers", "true");
        String[] output = runSampler(options);
        // @formatter:off
        // OUTPUT IS:
        //-------------------------------------------------------------------------------------------------------------------------------------
        //Sampling Histogram. Recorded 207 samples with period 10ms.
        Assert.assertTrue(output[1].matches(SAMPLING_HISTOGRAM));
        //  Self Time: Time spent on the top of the stack.
        Assert.assertEquals(SELF_TIME, output[2]);
        //  Total Time: Time spent somewhere on the stack.
        Assert.assertEquals(TOTAL_TIME, output[3]);
        //  T0: Percent of time spent in interpreter.
        Assert.assertEquals(INTERPRETER, output[4]);
        //  T1: Percent of time spent in code compiled by tier 1 compiler.
        Assert.assertEquals(T1, output[5]);
        //  T2: Percent of time spent in code compiled by tier 2 compiler.
        Assert.assertEquals(T2, output[6]);
        //-------------------------------------------------------------------------------------------------------------------------------------
        //Thread[main,5,main]
        Assert.assertTrue(output[8].contains("Thread"));
        // Name       |      Total Time    |   T0   |   T1   |   T2   ||       Self Time    |   T0   |   T1   |   T2   || Location
        Assert.assertEquals(" Name       |      Total Time    |   T0   |   T1   |   T2   ||       Self Time    |   T0   |   T1   |   T2   || Location             ", output[9]);
        //-------------------------------------------------------------------------------------------------------------------------------------
        // foo        |             2060ms | 100.0% |   0.0% |   0.0% ||             2060ms | 100.0% |   0.0% |   0.0% || test~1:16-29
        String lineRegex = NAME_REGEX + SEPARATOR_REGEX +
                TIME_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX +
                SEPARATOR_REGEX + SEPARATOR_REGEX +
                TIME_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX +
                SEPARATOR_REGEX + SEPARATOR_REGEX +
                LOCATION_REGEX;
        Assert.assertTrue(output[11].matches(lineRegex));
        // bar        |             2070ms | 100.0% |   0.0% |   0.0% ||               10ms | 100.0% |   0.0% |   0.0% || test~1:43-84
        Assert.assertTrue(output[12].matches(lineRegex));
        // baz        |             1850ms | 100.0% |   0.0% |   0.0% ||                0ms |   0.0% |   0.0% |   0.0% || test~1:98-139
        Assert.assertTrue(output[13].matches(lineRegex));
        //            |             2070ms | 100.0% |   0.0% |   0.0% ||                0ms |   0.0% |   0.0% |   0.0% || test~1:0-161
        //-------------------------------------------------------------------------------------------------------------------------------------
        // @formatter:on
    }

    @Test
    public void testShowTiers20Histogram() {
        HashMap<String, String> options = new HashMap<>();
        options.put("cpusampler", "true");
        options.put("cpusampler.ShowTiers", "2,0");
        String[] output = runSampler(options);
        // @formatter:off
        // OUTPUT IS:
        //-------------------------------------------------------------------------------------------------------------------------------------
        //Sampling Histogram. Recorded 207 samples with period 10ms.
        Assert.assertTrue(output[1].matches(SAMPLING_HISTOGRAM));
        //  Self Time: Time spent on the top of the stack.
        Assert.assertEquals(SELF_TIME, output[2]);
        //  Total Time: Time spent somewhere on the stack.
        Assert.assertEquals(TOTAL_TIME, output[3]);
        //  T2: Percent of time spent in code compiled by tier 2 compiler.
        Assert.assertEquals(T2, output[4]);
        //  T0: Percent of time spent in interpreter.
        Assert.assertEquals(INTERPRETER, output[5]);
        //-------------------------------------------------------------------------------------------------------------------------------------
        //Thread[main,5,main]
        Assert.assertTrue(output[7].contains("Thread"));
        // Name       |      Total Time    |   T2   |   T0   ||       Self Time    |   T2   |   T0   || Location
        Assert.assertEquals(" Name       |      Total Time    |   T2   |   T0   ||       Self Time    |   T2   |   T0   || Location             ", output[8]);
        //-------------------------------------------------------------------------------------------------------------------------------------
        // foo        |             2060ms | 100.0% |   0.0% ||             2060ms | 100.0% |   0.0% || test~1:16-29
        String lineRegex = NAME_REGEX + SEPARATOR_REGEX +
                TIME_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX +
                SEPARATOR_REGEX + SEPARATOR_REGEX +
                TIME_REGEX + SEPARATOR_REGEX + PERCENT_REGEX + SEPARATOR_REGEX + PERCENT_REGEX +
                SEPARATOR_REGEX + SEPARATOR_REGEX +
                LOCATION_REGEX;
        Assert.assertTrue(output[10].matches(lineRegex));
        // bar        |             2070ms | 100.0% |   0.0% ||               10ms | 100.0% |   0.0% || test~1:43-84
        Assert.assertTrue(output[11].matches(lineRegex));
        // baz        |             1850ms | 100.0% |   0.0% ||                0ms |   0.0% |   0.0% || test~1:98-139
        Assert.assertTrue(output[12].matches(lineRegex));
        //            |             2070ms | 100.0% |   0.0% ||                0ms |   0.0% |   0.0% || test~1:0-161
        //-------------------------------------------------------------------------------------------------------------------------------------
        // @formatter:on
    }

    private String[] runSampler(Map<String, String> options) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder().in(System.in).out(out).err(err).options(options).build()) {
            Source source = makeSource("ROOT(" +
                            "DEFINE(foo,ROOT(SLEEP(1)))," +
                            "DEFINE(bar,ROOT(BLOCK(STATEMENT,LOOP(10, CALL(foo)))))," +
                            "DEFINE(baz,ROOT(BLOCK(STATEMENT,LOOP(10, CALL(bar)))))," +
                            "CALL(baz),CALL(bar)" +
                            ")");
            for (int i = 0; i < EXEC_COUUNT; i++) {
                context.eval(source);
            }
        }
        String output = out.toString();
        return output.split(System.lineSeparator());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testSamplerJson() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        Context context = Context.newBuilder().in(System.in).out(out).err(err).option("cpusampler", "true").option("cpusampler.Output", "json").build();
        Source defaultSourceForSampling = makeSource("ROOT(" +
                        "DEFINE(foo,ROOT(SLEEP(1)))," +
                        "DEFINE(bar,ROOT(BLOCK(STATEMENT,LOOP(10, CALL(foo)))))," +
                        "DEFINE(baz,ROOT(BLOCK(STATEMENT,LOOP(10, CALL(bar)))))," +
                        "CALL(baz),CALL(bar)" +
                        ")");
        for (int i = 0; i < 10; i++) {
            context.eval(defaultSourceForSampling);
        }
        CPUSampler sampler = CPUSampler.find(context.getEngine());
        Map<Thread, Collection<ProfilerNode<CPUSampler.Payload>>> threadToNodesMap;
        final long period;
        final long sampleCount;
        final boolean gatherSelfHitTimes;
        synchronized (sampler) {
            threadToNodesMap = sampler.getThreadToNodesMap();
            period = sampler.getPeriod();
            sampleCount = sampler.getSampleCount();
            gatherSelfHitTimes = sampler.isGatherSelfHitTimes();
            context.close();
        }
        JSONObject output = new JSONObject(out.toString());

        Assert.assertEquals("Period wrong in json", period, Long.valueOf((Integer) output.get("period")).longValue());
        Assert.assertEquals("Sample count not correct in json", sampleCount, Long.valueOf((Integer) output.get("sample_count")).longValue());
        Assert.assertEquals("Gather self times not correct in json", gatherSelfHitTimes, output.get("gathered_hit_times"));
        JSONArray profile = (JSONArray) output.get("profile");

        // We are single threaded in this test
        JSONObject perThreadProfile = (JSONObject) profile.get(0);
        JSONArray samples = (JSONArray) perThreadProfile.get("samples");
        Collection<ProfilerNode<CPUSampler.Payload>> profilerNodes = threadToNodesMap.get(Thread.currentThread());
        deepCompare(samples, profilerNodes);
    }

    private void deepCompare(JSONArray samples, Collection<ProfilerNode<CPUSampler.Payload>> nodes) {
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = (JSONObject) samples.get(i);
            ProfilerNode<CPUSampler.Payload> correspondingNode = findCorrespondingNode(sample, nodes);
            if (correspondingNode != null) {
                CPUSampler.Payload payload = correspondingNode.getPayload();
                final int selfCompiledHitCount = payload.getSelfCompiledHitCount();
                Assert.assertEquals("Wrong payload", selfCompiledHitCount, sample.get("self_compiled_hit_count"));
                final int selfInterpretedHitCount = payload.getSelfInterpretedHitCount();
                Assert.assertEquals("Wrong payload", selfInterpretedHitCount, sample.get("self_interpreted_hit_count"));
                final List<Long> selfHitTimes = payload.getSelfHitTimes();
                final JSONArray selfHitTimesJson = (JSONArray) sample.get("self_hit_times");
                Assert.assertEquals("Wrong payload", selfHitTimes.size(), selfHitTimesJson.length());
                for (int j = 0; j < selfHitTimes.size(); j++) {
                    Assert.assertEquals("Wrong payload", selfHitTimes.get(j).longValue(), selfHitTimesJson.getLong(j));
                }
                final int compiledHitCount = payload.getCompiledHitCount();
                Assert.assertEquals("Wrong payload", compiledHitCount, sample.get("compiled_hit_count"));
                final int interpretedHitCount = payload.getInterpretedHitCount();
                Assert.assertEquals("Wrong payload", interpretedHitCount, sample.get("interpreted_hit_count"));

                deepCompare((JSONArray) sample.get("children"), correspondingNode.getChildren());
            } else {
                Assert.fail("No corresponding node for one in JSON.");
            }
        }
    }

    private static ProfilerNode<CPUSampler.Payload> findCorrespondingNode(JSONObject sample, Collection<ProfilerNode<CPUSampler.Payload>> nodes) {
        String root = (String) sample.get("root_name");
        JSONObject sourceSection = (JSONObject) sample.get("source_section");
        String sourceName = (String) sourceSection.get("source_name");
        int startColumn = (int) sourceSection.get("start_column");
        int endColumn = (int) sourceSection.get("end_column");
        int startLine = (int) sourceSection.get("start_line");
        int endLine = (int) sourceSection.get("end_line");
        Iterator<ProfilerNode<CPUSampler.Payload>> iterator = nodes.iterator();
        while (iterator.hasNext()) {
            ProfilerNode<CPUSampler.Payload> next = iterator.next();
            SourceSection nextSourceSection = next.getSourceSection();
            if (next.getRootName().equals(root) && nextSourceSection.getSource().getName().equals(sourceName) && nextSourceSection.getStartColumn() == startColumn &&
                            nextSourceSection.getEndColumn() == endColumn && nextSourceSection.getStartLine() == startLine && nextSourceSection.getEndLine() == endLine) {
                return next;
            }
        }
        return null;
    }
}
