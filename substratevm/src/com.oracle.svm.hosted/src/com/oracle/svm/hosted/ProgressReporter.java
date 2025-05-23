/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted;

import java.io.File;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.oracle.graal.pointsto.util.TimerCollection;
import org.graalvm.compiler.debug.DebugOptions;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.serviceprovider.GraalServices;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.impl.ImageSingletonsSupport;
import org.graalvm.nativeimage.impl.RuntimeReflectionSupport;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.reports.ReportUtils;
import com.oracle.graal.pointsto.util.Timer;
import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.BuildArtifacts.ArtifactType;
import com.oracle.svm.core.OS;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.VM;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.core.reflect.MethodMetadataDecoder;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.c.codegen.CCompilerInvoker;
import com.oracle.svm.hosted.code.CompileQueue.CompileTask;
import com.oracle.svm.hosted.image.AbstractImage.NativeImageKind;
import com.oracle.svm.hosted.image.NativeImageHeap.ObjectInfo;
import com.oracle.svm.util.ImageBuildStatistics;
import com.oracle.svm.util.ReflectionUtil;

public class ProgressReporter {
    private static final int CHARACTERS_PER_LINE;
    private static final boolean IS_CI = System.console() == null || System.getenv("CI") != null;
    private static final boolean IS_DUMB_TERM = isDumbTerm();
    private static final int MAX_NUM_FEATURES = 50;
    private static final int MAX_NUM_BREAKDOWN = 10;
    private static final String CODE_BREAKDOWN_TITLE = String.format("Top %d packages in code area:", MAX_NUM_BREAKDOWN);
    private static final String HEAP_BREAKDOWN_TITLE = String.format("Top %d object types in image heap:", MAX_NUM_BREAKDOWN);
    private static final String STAGE_DOCS_URL = "https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/BuildOutput.md";
    private static final double EXCESSIVE_GC_MIN_THRESHOLD_MILLIS = 15_000;
    private static final double EXCESSIVE_GC_RATIO = 0.5;
    private static final String BREAKDOWN_BYTE_ARRAY_PREFIX = "byte[] for ";

    private final NativeImageSystemIOWrappers builderIO;

    private final boolean isEnabled; // TODO: clean up when deprecating old output (GR-35721).
    private final DirectPrinter linePrinter = new DirectPrinter();
    private final StagePrinter<?> stagePrinter;
    private final ColorStrategy colorStrategy;
    private final LinkStrategy linkStrategy;
    private final boolean usePrefix;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private String outputPrefix = "";
    private long lastGCCheckTimeMillis = System.currentTimeMillis();
    private GCStats lastGCStats = GCStats.getCurrent();
    private long numRuntimeCompiledMethods = -1;
    private long graphEncodingByteLength = 0;
    private int numJNIClasses = -1;
    private int numJNIFields = -1;
    private int numJNIMethods = -1;
    private Timer debugInfoTimer;
    private boolean creationStageEndCompleted = false;
    private boolean reportStringBytes = true;

    private enum BuildStage {
        INITIALIZING("Initializing"),
        ANALYSIS("Performing analysis", true, false),
        UNIVERSE("Building universe"),
        PARSING("Parsing methods", true, true),
        INLINING("Inlining methods", true, false),
        COMPILING("Compiling methods", true, true),
        CREATING("Creating image");

        private static final int NUM_STAGES = values().length;

        private final String message;
        private final boolean hasProgressBar;
        private final boolean hasPeriodicProgress;

        BuildStage(String message) {
            this(message, false, false);
        }

        BuildStage(String message, boolean hasProgressBar, boolean hasPeriodicProgress) {
            this.message = message;
            this.hasProgressBar = hasProgressBar;
            this.hasPeriodicProgress = hasPeriodicProgress;
        }
    }

    static {
        CHARACTERS_PER_LINE = IS_CI ? ProgressReporterCHelper.MAX_CHARACTERS_PER_LINE : ProgressReporterCHelper.getTerminalWindowColumnsClamped();
    }

    private static boolean isDumbTerm() {
        String term = System.getenv("TERM");
        return (term == null || term.equals("") || term.equals("dumb") || term.equals("unknown"));
    }

    public static ProgressReporter singleton() {
        return ImageSingletons.lookup(ProgressReporter.class);
    }

    public ProgressReporter(OptionValues options) {
        builderIO = NativeImageSystemIOWrappers.singleton();

        isEnabled = SubstrateOptions.BuildOutputUseNewStyle.getValue(options);
        if (isEnabled) {
            Timer.disablePrinting();
        }
        usePrefix = SubstrateOptions.BuildOutputPrefix.getValue(options);
        boolean enableColors = !IS_DUMB_TERM && !IS_CI && OS.getCurrent() != OS.WINDOWS &&
                        System.getenv("NO_COLOR") == null /* https://no-color.org/ */;
        if (SubstrateOptions.BuildOutputColorful.hasBeenSet(options)) {
            enableColors = SubstrateOptions.BuildOutputColorful.getValue(options);
        }
        if (enableColors) {
            colorStrategy = new ColorfulStrategy();
            /* Add a shutdown hook to reset the ANSI mode. */
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(ProgressReporter::resetANSIMode));
            } catch (IllegalStateException e) {
                /* If the VM is already shutting down, we do not need to register shutdownHook. */
            }
        } else {
            colorStrategy = new ColorlessStrategy();
        }

        /*
         * When logging is enabled, progress cannot be reported as logging works around
         * NativeImageSystemIOWrappers to access stdio handles.
         */
        boolean loggingEnabled = DebugOptions.Log.getValue(options) != null;
        boolean enableProgress = !IS_DUMB_TERM && !IS_CI && !loggingEnabled;
        if (SubstrateOptions.BuildOutputProgress.hasBeenSet(options)) {
            enableProgress = SubstrateOptions.BuildOutputProgress.getValue(options);
        }
        stagePrinter = enableProgress ? new CharacterwiseStagePrinter() : new LinewiseStagePrinter();
        boolean showLinks = enableColors;
        if (SubstrateOptions.BuildOutputLinks.hasBeenSet(options)) {
            showLinks = SubstrateOptions.BuildOutputLinks.getValue(options);
        }
        linkStrategy = showLinks ? new LinkyStrategy() : new LinklessStrategy();

        if (SubstrateOptions.useEconomyCompilerConfig(options)) {
            l().redBold().a("You enabled -Ob for this image build. This will configure some optimizations to reduce image build time.").println();
            l().redBold().a("This feature should only be used during development and never for deployment.").reset().println();
        }
    }

    public void setNumRuntimeCompiledMethods(int value) {
        numRuntimeCompiledMethods = value;
    }

    public void setGraphEncodingByteLength(int value) {
        graphEncodingByteLength = value;
    }

    public void setJNIInfo(int numClasses, int numFields, int numMethods) {
        numJNIClasses = numClasses;
        numJNIFields = numFields;
        numJNIMethods = numMethods;
    }

    public void disableStringBytesReporting() {
        reportStringBytes = false;
    }

    public void printStart(String imageName, NativeImageKind imageKind) {
        if (usePrefix) {
            // Add the PID to further disambiguate concurrent builds of images with the same name
            outputPrefix = String.format("[%s:%s] ", imageName, GraalServices.getExecutionID());
            stagePrinter.progressBarStart += outputPrefix.length();
        }
        l().printHeadlineSeparator();
        String imageKindName = imageKind.name().toLowerCase().replace('_', ' ');
        l().blueBold().link("GraalVM Native Image", "https://www.graalvm.org/native-image/").reset()
                        .a(": Generating '").bold().a(imageName).reset().a("' (").doclink(imageKindName, "#glossary-imagekind").a(")...").println();
        l().printHeadlineSeparator();
        stagePrinter.start(BuildStage.INITIALIZING);
    }

    public void printUnsuccessfulInitializeEnd() {
        if (stagePrinter.activeBuildStage != null) {
            stagePrinter.end(0);
        }
    }

    public void printInitializeEnd(Collection<String> libraries) {
        stagePrinter.end(getTimer(TimerCollection.Registry.CLASSLIST).getTotalTime() + getTimer(TimerCollection.Registry.SETUP).getTotalTime());
        l().a(" ").doclink("Version info", "#glossary-version-info").a(": '").a(ImageSingletons.lookup(VM.class).version).a("'").println();
        if (ImageSingletons.contains(CCompilerInvoker.class)) {
            l().a(" ").doclink("C compiler", "#glossary-ccompiler").a(": ").a(ImageSingletons.lookup(CCompilerInvoker.class).compilerInfo.getShortDescription()).println();
        }
        l().a(" ").doclink("Garbage collector", "#glossary-gc").a(": ").a(Heap.getHeap().getGC().getName()).println();
        printNativeLibraries(libraries);
    }

    private void printNativeLibraries(Collection<String> libraries) {
        int numLibraries = libraries.size();
        if (numLibraries > 0) {
            if (numLibraries == 1) {
                l().a(" 1 native library: ").a(libraries.iterator().next()).println();
            } else {
                l().a(" ").a(numLibraries).a(" native libraries: ").a(String.join(", ", libraries)).println();
            }
        }
    }

    public void printFeatures(List<String> list) {
        int numUserFeatures = list.size();
        if (numUserFeatures > 0) {
            l().a(" ").a(numUserFeatures).a(" ").doclink("user-provided feature(s)", "#glossary-user-provided-features").println();
            if (numUserFeatures <= MAX_NUM_FEATURES) {
                for (String name : list) {
                    l().a("  - ").a(name).println();
                }
            } else {
                for (int i = 0; i < MAX_NUM_FEATURES; i++) {
                    l().a("  - ").a(list.get(i)).println();
                }
                l().a("  ... ").a(numUserFeatures - MAX_NUM_FEATURES).a(" more").println();
            }
        }
    }

    public ReporterClosable printAnalysis(BigBang bb) {
        Timer timer = getTimer(TimerCollection.Registry.ANALYSIS);
        timer.start();
        stagePrinter.start(BuildStage.ANALYSIS);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
                printAnalysisStatistics(bb.getUniverse());
            }
        };
    }

    private void printAnalysisStatistics(AnalysisUniverse universe) {
        String actualVsTotalFormat = "%,8d (%5.2f%%) of %,6d";
        long reachableClasses = universe.getTypes().stream().filter(t -> t.isReachable()).count();
        long totalClasses = universe.getTypes().size();
        l().a(actualVsTotalFormat, reachableClasses, reachableClasses / (double) totalClasses * 100, totalClasses)
                        .a(" classes ").doclink("reachable", "#glossary-reachability").println();
        Collection<AnalysisField> fields = universe.getFields();
        long reachableFields = fields.stream().filter(f -> f.isAccessed()).count();
        int totalFields = fields.size();
        l().a(actualVsTotalFormat, reachableFields, reachableFields / (double) totalFields * 100, totalFields)
                        .a(" fields ").doclink("reachable", "#glossary-reachability").println();
        Collection<AnalysisMethod> methods = universe.getMethods();
        long reachableMethods = methods.stream().filter(m -> m.isReachable()).count();
        int totalMethods = methods.size();
        l().a(actualVsTotalFormat, reachableMethods, reachableMethods / (double) totalMethods * 100, totalMethods)
                        .a(" methods ").doclink("reachable", "#glossary-reachability").println();
        if (numRuntimeCompiledMethods >= 0) {
            l().a(actualVsTotalFormat, numRuntimeCompiledMethods, numRuntimeCompiledMethods / (double) totalMethods * 100, totalMethods)
                            .a(" methods included for ").doclink("runtime compilation", "#glossary-runtime-methods").println();
        }
        String classesFieldsMethodFormat = "%,8d classes, %,5d fields, and %,5d methods ";
        RuntimeReflectionSupport rs = ImageSingletons.lookup(RuntimeReflectionSupport.class);
        l().a(classesFieldsMethodFormat, rs.getReflectionClassesCount(), rs.getReflectionFieldsCount(), rs.getReflectionMethodsCount())
                        .doclink("registered for reflection", "#glossary-reflection-registrations").println();
        if (numJNIClasses > 0) {
            l().a(classesFieldsMethodFormat, numJNIClasses, numJNIFields, numJNIMethods)
                            .doclink("registered for JNI access", "#glossary-jni-access-registrations").println();
        }
    }

    public ReporterClosable printUniverse() {
        Timer timer = getTimer(TimerCollection.Registry.UNIVERSE);
        timer.start();
        stagePrinter.start(BuildStage.UNIVERSE);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
            }
        };
    }

    public ReporterClosable printParsing() {
        Timer timer = getTimer(TimerCollection.Registry.PARSE);
        timer.start();
        stagePrinter.start(BuildStage.PARSING);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
            }
        };
    }

    public ReporterClosable printInlining() {
        Timer timer = getTimer(TimerCollection.Registry.INLINE);
        timer.start();
        stagePrinter.start(BuildStage.INLINING);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
            }
        };
    }

    public void printInliningSkipped() {
        stagePrinter.skipped(BuildStage.INLINING);
    }

    public ReporterClosable printCompiling() {
        Timer timer = getTimer(TimerCollection.Registry.COMPILE);
        timer.start();
        stagePrinter.start(BuildStage.COMPILING);
        return new ReporterClosable() {
            @Override
            public void closeAction() {
                timer.stop();
                stagePrinter.end(timer);
            }
        };
    }

    // TODO: merge printCreationStart and printCreationEnd at some point (GR-35721).
    public void printCreationStart() {
        stagePrinter.start(BuildStage.CREATING);
    }

    public void setDebugInfoTimer(Timer timer) {
        this.debugInfoTimer = timer;
    }

    public void printCreationEnd(int imageSize, AnalysisUniverse universe, int numHeapObjects, long imageHeapSize, int codeCacheSize,
                    int numCompilations, int debugInfoSize) {
        Timer imageTimer = getTimer(TimerCollection.Registry.IMAGE);
        Timer writeTimer = getTimer(TimerCollection.Registry.WRITE);
        stagePrinter.end(imageTimer.getTotalTime() + writeTimer.getTotalTime());
        creationStageEndCompleted = true;
        String format = "%9s (%5.2f%%) for ";
        l().a(format, Utils.bytesToHuman(codeCacheSize), codeCacheSize / (double) imageSize * 100)
                        .doclink("code area", "#glossary-code-area").a(":%,9d compilation units", numCompilations).println();
        long numInstantiatedClasses = universe.getTypes().stream().filter(t -> t.isInstantiated()).count();
        l().a(format, Utils.bytesToHuman(imageHeapSize), imageHeapSize / (double) imageSize * 100)
                        .doclink("image heap", "#glossary-image-heap").a(":%,8d classes and %,d objects", numInstantiatedClasses, numHeapObjects).println();
        if (debugInfoSize > 0) {
            DirectPrinter l = l().a(format, Utils.bytesToHuman(debugInfoSize), debugInfoSize / (double) imageSize * 100)
                            .doclink("debug info", "#glossary-debug-info");
            if (debugInfoTimer != null) {
                l.a(" generated in %.1fs", Utils.millisToSeconds(debugInfoTimer.getTotalTime()));
            }
            l.println();
        }
        long otherBytes = imageSize - codeCacheSize - imageHeapSize - debugInfoSize;
        l().a(format, Utils.bytesToHuman(otherBytes), otherBytes / (double) imageSize * 100)
                        .doclink("other data", "#glossary-other-data").println();
        l().a("%9s in total", Utils.bytesToHuman(imageSize)).println();
    }

    public void ensureCreationStageEndCompleted() {
        if (!creationStageEndCompleted) {
            println();
        }
    }

    public void printBreakdowns(Collection<CompileTask> compilationTasks, Collection<ObjectInfo> heapObjects) {
        Map<String, Long> codeBreakdown = calculateCodeBreakdown(compilationTasks);
        Map<String, Long> heapBreakdown = calculateHeapBreakdown(heapObjects);
        l().printLineSeparator();
        int numCodeBreakdownItems = codeBreakdown.size();
        int numHeapBreakdownItems = heapBreakdown.size();
        Iterator<Entry<String, Long>> packagesBySize = codeBreakdown.entrySet().stream()
                        .sorted(Entry.comparingByValue(Comparator.reverseOrder())).iterator();
        Iterator<Entry<String, Long>> typesBySizeInHeap = heapBreakdown.entrySet().stream()
                        .sorted(Entry.comparingByValue(Comparator.reverseOrder())).iterator();

        final TwoColumnPrinter p = new TwoColumnPrinter();
        p.l().yellowBold().a(CODE_BREAKDOWN_TITLE).jumpToMiddle().a(HEAP_BREAKDOWN_TITLE).reset().flushln();

        List<Entry<String, Long>> printedCodeSizeEntries = new ArrayList<>();
        List<Entry<String, Long>> printedHeapSizeEntries = new ArrayList<>();
        for (int i = 0; i < MAX_NUM_BREAKDOWN; i++) {
            String codeSizePart = "";
            if (packagesBySize.hasNext()) {
                Entry<String, Long> e = packagesBySize.next();
                String className = Utils.truncateClassOrPackageName(e.getKey());
                codeSizePart = String.format("%9s %s", Utils.bytesToHuman(e.getValue()), className);
                printedCodeSizeEntries.add(e);
            }

            String heapSizePart = "";
            if (typesBySizeInHeap.hasNext()) {
                Entry<String, Long> e = typesBySizeInHeap.next();
                String className = e.getKey();
                // Do not truncate special breakdown items, they can contain links.
                if (!className.startsWith(BREAKDOWN_BYTE_ARRAY_PREFIX)) {
                    className = Utils.truncateClassOrPackageName(className);
                }
                heapSizePart = String.format("%9s %s", Utils.bytesToHuman(e.getValue()), className);
                printedHeapSizeEntries.add(e);
            }
            if (codeSizePart.isEmpty() && heapSizePart.isEmpty()) {
                break;
            }
            p.l().a(codeSizePart).jumpToMiddle().a(heapSizePart).flushln();
        }

        p.l().a("      ... ").a(numCodeBreakdownItems - printedHeapSizeEntries.size()).a(" additional packages")
                        .jumpToMiddle()
                        .a("      ... ").a(numHeapBreakdownItems - printedCodeSizeEntries.size()).a(" additional object types").flushln();

        new CenteredTextPrinter().dim()
                        .a("(use ").link("GraalVM Dashboard", "https://www.graalvm.org/dashboard/?ojr=help%3Btopic%3Dgetting-started.md").a(" to see all)")
                        .reset().flushln();
    }

    private static Map<String, Long> calculateCodeBreakdown(Collection<CompileTask> compilationTasks) {
        Map<String, Long> classNameToCodeSize = new HashMap<>();
        for (CompileTask task : compilationTasks) {
            String classOrPackageName = task.method.format("%H");
            int lastDotIndex = classOrPackageName.lastIndexOf('.');
            if (lastDotIndex > 0) {
                classOrPackageName = classOrPackageName.substring(0, lastDotIndex);
            }
            classNameToCodeSize.merge(classOrPackageName, (long) task.result.getTargetCodeSize(), Long::sum);
        }
        return classNameToCodeSize;
    }

    private Map<String, Long> calculateHeapBreakdown(Collection<ObjectInfo> heapObjects) {
        Map<String, Long> classNameToSize = new HashMap<>();
        long stringByteLength = 0;
        for (ObjectInfo o : heapObjects) {
            classNameToSize.merge(o.getClazz().toJavaName(true), o.getSize(), Long::sum);
            Object javaObject = o.getObject();
            if (reportStringBytes && javaObject instanceof String) {
                stringByteLength += Utils.getInternalByteArrayLength((String) javaObject);
            }
        }

        Long byteArraySize = classNameToSize.remove("byte[]");
        if (byteArraySize != null) {
            long remainingBytes = byteArraySize;
            if (stringByteLength > 0) {
                classNameToSize.put(BREAKDOWN_BYTE_ARRAY_PREFIX + "java.lang.String", stringByteLength);
                remainingBytes -= stringByteLength;
            }
            long codeInfoSize = CodeInfoTable.getImageCodeCache().getTotalByteArraySize();
            if (codeInfoSize > 0) {
                classNameToSize.put(BREAKDOWN_BYTE_ARRAY_PREFIX + linkStrategy.asDocLink("code metadata", "#glossary-code-metadata"), codeInfoSize);
                remainingBytes -= codeInfoSize;
            }
            long metadataByteLength = ImageSingletons.lookup(MethodMetadataDecoder.class).getMetadataByteLength();
            if (metadataByteLength > 0) {
                classNameToSize.put(BREAKDOWN_BYTE_ARRAY_PREFIX + linkStrategy.asDocLink("method metadata", "#glossary-method-metadata"), metadataByteLength);
                remainingBytes -= metadataByteLength;
            }
            if (graphEncodingByteLength > 0) {
                classNameToSize.put(BREAKDOWN_BYTE_ARRAY_PREFIX + linkStrategy.asDocLink("graph encodings", "#glossary-graph-encodings"), graphEncodingByteLength);
                remainingBytes -= graphEncodingByteLength;
            }
            assert remainingBytes >= 0;
            classNameToSize.put(BREAKDOWN_BYTE_ARRAY_PREFIX + linkStrategy.asDocLink("general heap data", "#glossary-general-heap-data"), remainingBytes);
        }
        return classNameToSize;
    }

    public void printEpilog(String imageName, NativeImageGenerator generator, boolean wasSuccessfulBuild, OptionValues parsedHostedOptions) {
        l().printLineSeparator();
        printResourceStatistics();
        l().printLineSeparator();

        l().yellowBold().a("Produced artifacts:").reset().println();
        generator.getBuildArtifacts().forEach((artifactType, paths) -> {
            for (Path p : paths) {
                l().a(" ").link(p).dim().a(" (").a(artifactType.name().toLowerCase()).a(")").reset().println();
            }
        });
        if (generator.getBigbang() != null && ImageBuildStatistics.Options.CollectImageBuildStatistics.getValue(parsedHostedOptions)) {
            l().a(" ").link(reportImageBuildStatistics(imageName, generator.getBigbang())).println();
        }
        l().a(" ").link(reportBuildArtifacts(imageName, generator.getBuildArtifacts())).println();

        l().printHeadlineSeparator();

        double totalSeconds = Utils.millisToSeconds(getTimer(TimerCollection.Registry.TOTAL).getTotalTime());
        String timeStats;
        if (totalSeconds < 60) {
            timeStats = String.format("%.1fs", totalSeconds);
        } else {
            timeStats = String.format("%dm %ds", (int) totalSeconds / 60, (int) totalSeconds % 60);
        }
        l().a(wasSuccessfulBuild ? "Finished" : "Failed").a(" generating '").bold().a(imageName).reset().a("' ")
                        .a(wasSuccessfulBuild ? "in" : "after").a(" ").a(timeStats).a(".").println();
        executor.shutdown();
    }

    private Path reportImageBuildStatistics(String imageName, BigBang bb) {
        Consumer<PrintWriter> statsReporter = ImageSingletons.lookup(ImageBuildStatistics.class).getReporter();
        String description = "image build statistics";
        if (ImageBuildStatistics.Options.ImageBuildStatisticsFile.hasBeenSet(bb.getOptions())) {
            final File file = new File(ImageBuildStatistics.Options.ImageBuildStatisticsFile.getValue(bb.getOptions()));
            return ReportUtils.report(description, file.getAbsoluteFile().toPath(), statsReporter, !isEnabled);
        } else {
            String name = "image_build_statistics_" + ReportUtils.extractImageName(imageName);
            String path = SubstrateOptions.Path.getValue() + File.separatorChar + "reports";
            return ReportUtils.report(description, path, name, "json", statsReporter, !isEnabled);
        }
    }

    private Path reportBuildArtifacts(String imageName, Map<ArtifactType, List<Path>> buildArtifacts) {
        Path buildDir = NativeImageGenerator.generatedFiles(HostedOptionValues.singleton());

        Consumer<PrintWriter> writerConsumer = writer -> buildArtifacts.forEach((artifactType, paths) -> {
            writer.println("[" + artifactType + "]");
            if (artifactType == BuildArtifacts.ArtifactType.JDK_LIB_SHIM) {
                writer.println("# Note that shim JDK libraries depend on this");
                writer.println("# particular native image (including its name)");
                writer.println("# and therefore cannot be used with others.");
            }
            paths.stream().map(Path::toAbsolutePath).map(buildDir::relativize).forEach(writer::println);
            writer.println();
        });
        return ReportUtils.report("build artifacts", buildDir.resolve(imageName + ".build_artifacts.txt"), writerConsumer, !isEnabled);
    }

    private void printResourceStatistics() {
        double totalProcessTimeSeconds = Utils.millisToSeconds(System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime());
        GCStats gcStats = GCStats.getCurrent();
        double gcSeconds = Utils.millisToSeconds(gcStats.totalTimeMillis);
        CenteredTextPrinter p = new CenteredTextPrinter();
        p.a("%.1fs (%.1f%% of total time) in %d ", gcSeconds, gcSeconds / totalProcessTimeSeconds * 100, gcStats.totalCount)
                        .doclink("GCs", "#glossary-garbage-collections");
        long peakRSS = ProgressReporterCHelper.getPeakRSS();
        if (peakRSS >= 0) {
            p.a(" | ").doclink("Peak RSS", "#glossary-peak-rss").a(": ").a("%.2fGB", Utils.bytesToGiB(peakRSS));
        }
        OperatingSystemMXBean osMXBean = ManagementFactory.getOperatingSystemMXBean();
        long processCPUTime = ((com.sun.management.OperatingSystemMXBean) osMXBean).getProcessCpuTime();
        if (processCPUTime > 0) {
            p.a(" | ").doclink("CPU load", "#glossary-cpu-load").a(": ").a("%.2f", Utils.nanosToSeconds(processCPUTime) / totalProcessTimeSeconds);
        }
        p.flushln();
    }

    private void checkForExcessiveGarbageCollection() {
        long current = System.currentTimeMillis();
        long timeDeltaMillis = current - lastGCCheckTimeMillis;
        lastGCCheckTimeMillis = current;
        GCStats currentGCStats = GCStats.getCurrent();
        long gcTimeDeltaMillis = currentGCStats.totalTimeMillis - lastGCStats.totalTimeMillis;
        double ratio = gcTimeDeltaMillis / (double) timeDeltaMillis;
        if (gcTimeDeltaMillis > EXCESSIVE_GC_MIN_THRESHOLD_MILLIS && ratio > EXCESSIVE_GC_RATIO) {
            l().redBold().a("GC warning").reset()
                            .a(": %.1fs spent in %d GCs during the last stage, taking up %.2f%% of the time.",
                                            Utils.millisToSeconds(gcTimeDeltaMillis), currentGCStats.totalCount - lastGCStats.totalCount, ratio * 100)
                            .println();
            l().a("            Please ensure more than %.2fGB of memory is available for Native Image", Utils.bytesToGiB(ProgressReporterCHelper.getPeakRSS())).println();
            l().a("            to reduce GC overhead and improve image build time.").println();
        }
        lastGCStats = currentGCStats;
    }

    /*
     * HELPERS
     */

    private static Timer getTimer(TimerCollection.Registry type) {
        return TimerCollection.singleton().get(type);
    }

    private static void resetANSIMode() {
        NativeImageSystemIOWrappers.singleton().getOut().print(ANSI.RESET);
    }

    private static class Utils {
        private static final double MILLIS_TO_SECONDS = 1000d;
        private static final double NANOS_TO_SECONDS = 1000d * 1000d * 1000d;
        private static final double BYTES_TO_KiB = 1024d;
        private static final double BYTES_TO_MiB = 1024d * 1024d;
        private static final double BYTES_TO_GiB = 1024d * 1024d * 1024d;

        private static final Field STRING_VALUE = ReflectionUtil.lookupField(String.class, "value");

        private static String bytesToHuman(long bytes) {
            return bytesToHuman("%4.2f", bytes);
        }

        private static String bytesToHuman(String format, long bytes) {
            if (bytes < BYTES_TO_KiB) {
                return String.format(format, (double) bytes) + "B";
            } else if (bytes < BYTES_TO_MiB) {
                return String.format(format, bytesToKiB(bytes)) + "KB";
            } else if (bytes < BYTES_TO_GiB) {
                return String.format(format, bytesToMiB(bytes)) + "MB";
            } else {
                return String.format(format, bytesToGiB(bytes)) + "GB";
            }
        }

        private static double bytesToKiB(long bytes) {
            return bytes / BYTES_TO_KiB;
        }

        private static double bytesToGiB(long bytes) {
            return bytes / BYTES_TO_GiB;
        }

        private static double bytesToMiB(long bytes) {
            return bytes / BYTES_TO_MiB;
        }

        private static double millisToSeconds(double millis) {
            return millis / MILLIS_TO_SECONDS;
        }

        private static double nanosToSeconds(double nanos) {
            return nanos / NANOS_TO_SECONDS;
        }

        private static int getInternalByteArrayLength(String string) {
            try {
                return ((byte[]) STRING_VALUE.get(string)).length;
            } catch (ReflectiveOperationException ex) {
                throw VMError.shouldNotReachHere(ex);
            }
        }

        private static double getUsedMemory() {
            return bytesToGiB(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        }

        private static String stringFilledWith(int size, String fill) {
            return new String(new char[size]).replace("\0", fill);
        }

        private static String truncateClassOrPackageName(String classOrPackageName) {
            int classNameLength = classOrPackageName.length();
            int maxLength = CHARACTERS_PER_LINE / 2 - 10;
            if (classNameLength <= maxLength) {
                return classOrPackageName;
            }
            StringBuilder sb = new StringBuilder();
            int currentDot = -1;
            while (true) {
                int nextDot = classOrPackageName.indexOf('.', currentDot + 1);
                if (nextDot < 0) { // Not more dots, handle the rest and return.
                    String rest = classOrPackageName.substring(currentDot + 1);
                    int sbLength = sb.length();
                    int restLength = rest.length();
                    if (sbLength + restLength <= maxLength) {
                        sb.append(rest);
                    } else {
                        int remainingSpaceDivBy2 = (maxLength - sbLength) / 2;
                        sb.append(rest.substring(0, remainingSpaceDivBy2 - 1) + "~" + rest.substring(restLength - remainingSpaceDivBy2, restLength));
                    }
                    break;
                }
                sb.append(classOrPackageName.charAt(currentDot + 1)).append('.');
                if (sb.length() + (classNameLength - nextDot) <= maxLength) {
                    // Rest fits maxLength, append and return.
                    sb.append(classOrPackageName.substring(nextDot + 1));
                    break;
                }
                currentDot = nextDot;
            }
            return sb.toString();
        }
    }

    private static class GCStats {
        private final long totalCount;
        private final long totalTimeMillis;

        private static GCStats getCurrent() {
            long totalCount = 0;
            long totalTime = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long collectionCount = bean.getCollectionCount();
                if (collectionCount > 0) {
                    totalCount += collectionCount;
                }
                long collectionTime = bean.getCollectionTime();
                if (collectionTime > 0) {
                    totalTime += collectionTime;
                }
            }
            return new GCStats(totalCount, totalTime);
        }

        GCStats(long totalCount, long totalTime) {
            this.totalCount = totalCount;
            this.totalTimeMillis = totalTime;
        }
    }

    @AutomaticFeature
    public static class ProgressReporterFeature implements Feature {
        private final ProgressReporter reporter = ProgressReporter.singleton();

        @Override
        public void duringAnalysis(DuringAnalysisAccess access) {
            reporter.reportStageProgress();
        }
    }

    public abstract static class ReporterClosable implements AutoCloseable {
        @Override
        public void close() {
            closeAction();
        }

        abstract void closeAction();
    }

    /*
     * CORE PRINTING
     */

    private void print(char text) {
        if (isEnabled) {
            builderIO.getOut().print(text);
        }
    }

    private void print(String text) {
        if (isEnabled) {
            builderIO.getOut().print(text);
        }
    }

    private void println() {
        if (isEnabled) {
            builderIO.getOut().println();
        }
    }

    /*
     * PRINTERS
     */

    abstract class AbstractPrinter<T extends AbstractPrinter<T>> {
        abstract T getThis();

        abstract T a(String text);

        final T a(String text, Object... args) {
            return a(String.format(text, args));
        }

        final T a(int i) {
            return a(String.valueOf(i));
        }

        final T a(long i) {
            return a(String.valueOf(i));
        }

        final T bold() {
            colorStrategy.bold(this);
            return getThis();
        }

        final T blue() {
            colorStrategy.blue(this);
            return getThis();
        }

        final T blueBold() {
            colorStrategy.blueBold(this);
            return getThis();
        }

        final T redBold() {
            colorStrategy.redBold(this);
            return getThis();
        }

        final T yellowBold() {
            colorStrategy.yellowBold(this);
            return getThis();
        }

        final T dim() {
            colorStrategy.dim(this);
            return getThis();
        }

        final T reset() {
            colorStrategy.reset(this);
            return getThis();
        }

        final T link(String text, String url) {
            linkStrategy.link(this, text, url);
            return getThis();
        }

        final T link(Path path) {
            linkStrategy.link(this, path);
            return getThis();
        }

        final T doclink(String text, String htmlAnchor) {
            linkStrategy.doclink(this, text, htmlAnchor);
            return getThis();
        }
    }

    /** Start printing a new line. */
    private DirectPrinter l() {
        return linePrinter.a(outputPrefix);
    }

    final class DirectPrinter extends AbstractPrinter<DirectPrinter> {
        private final String headlineSeparator = Utils.stringFilledWith(CHARACTERS_PER_LINE, "=");
        private final String lineSeparator = Utils.stringFilledWith(CHARACTERS_PER_LINE, "-");

        @Override
        DirectPrinter getThis() {
            return this;
        }

        @Override
        DirectPrinter a(String text) {
            print(text);
            return this;
        }

        void println() {
            ProgressReporter.this.println();
        }

        void printHeadlineSeparator() {
            dim().a(headlineSeparator).reset().println();
        }

        void printLineSeparator() {
            dim().a(lineSeparator).reset().println();
        }
    }

    abstract class LinePrinter<T extends LinePrinter<T>> extends AbstractPrinter<T> {
        protected final List<String> lineParts = new ArrayList<>();

        @Override
        T a(String value) {
            lineParts.add(value);
            return getThis();
        }

        T l() {
            assert lineParts.isEmpty();
            return getThis();
        }

        final int getCurrentTextLength() {
            int textLength = 0;
            for (String text : lineParts) {
                if (!text.startsWith(ANSI.ESCAPE)) { // Ignore ANSI escape sequences.
                    textLength += text.length();
                }
            }
            return textLength;
        }

        final void printLineParts() {
            lineParts.forEach(ProgressReporter.this::print);
        }

        void flushln() {
            printLineParts();
            lineParts.clear();
            println();
        }
    }

    public void reportStageProgress() {
        stagePrinter.reportProgress();
    }

    public void beforeNextStdioWrite() {
        stagePrinter.beforeNextStdioWrite();
    }

    abstract class StagePrinter<T extends StagePrinter<T>> extends LinePrinter<T> {
        private int progressBarStart = 30;
        private BuildStage activeBuildStage = null;

        private ScheduledFuture<?> periodicPrintingTask;

        T start(BuildStage stage) {
            assert activeBuildStage == null;
            activeBuildStage = stage;
            appendStageStart();
            if (activeBuildStage.hasProgressBar) {
                a(progressBarStartPadding()).dim().a("[");
            }
            if (activeBuildStage.hasPeriodicProgress) {
                startPeriodicProgress();
            }
            return getThis();
        }

        private void startPeriodicProgress() {
            periodicPrintingTask = executor.scheduleAtFixedRate(new Runnable() {
                int countdown;
                int numPrints;

                @Override
                public void run() {
                    if (--countdown < 0) {
                        reportProgress();
                        countdown = ++numPrints > 2 ? numPrints * 2 : numPrints;
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }

        final void skipped(BuildStage stage) {
            assert activeBuildStage == null;
            activeBuildStage = stage;
            appendStageStart();
            a(progressBarStartPadding()).dim().a("(skipped)").reset().flushln();
            activeBuildStage = null;
        }

        private void appendStageStart() {
            a(outputPrefix).blue().a(String.format("[%s/%s] ", 1 + activeBuildStage.ordinal(), BuildStage.NUM_STAGES)).reset()
                            .blueBold().doclink(activeBuildStage.message, "#stage-" + activeBuildStage.name().toLowerCase()).a("...").reset();
        }

        final String progressBarStartPadding() {
            return Utils.stringFilledWith(progressBarStart - getCurrentTextLength(), " ");
        }

        void reportProgress() {
            a("*");
        }

        final void end(Timer timer) {
            end(timer.getTotalTime());
        }

        void end(double totalTime) {
            if (activeBuildStage.hasPeriodicProgress) {
                periodicPrintingTask.cancel(false);
            }
            if (activeBuildStage.hasProgressBar) {
                a("]").reset();
            }

            String suffix = String.format("(%.1fs @ %.2fGB)", Utils.millisToSeconds(totalTime), Utils.getUsedMemory());
            int textLength = getCurrentTextLength();
            // TODO: `assert textLength > 0;` should be used here but tests do not start stages
            // properly (GR-35721)
            String padding = Utils.stringFilledWith(Math.max(0, CHARACTERS_PER_LINE - textLength - suffix.length()), " ");
            a(padding).dim().a(suffix).reset().flushln();

            activeBuildStage = null;

            boolean optionsAvailable = ImageSingletonsSupport.isInstalled() && ImageSingletons.contains(HostedOptionValues.class);
            if (optionsAvailable && SubstrateOptions.BuildOutputGCWarnings.getValue()) {
                checkForExcessiveGarbageCollection();
            }
        }

        abstract void beforeNextStdioWrite();
    }

    /**
     * A {@link StagePrinter} that prints full lines to stdout at the end of each stage. This
     * printer should be used when interactive progress bars are not desired, e.g., when logging is
     * enabled or in dumb terminals.
     */
    final class LinewiseStagePrinter extends StagePrinter<LinewiseStagePrinter> {
        @Override
        LinewiseStagePrinter getThis() {
            return this;
        }

        @Override
        void beforeNextStdioWrite() {
            throw VMError.shouldNotReachHere("LinewiseStagePrinter not allowed to set builderIO.listenForNextStdioWrite");
        }
    }

    /**
     * A {@link StagePrinter} that produces interactive progress bars on the command line. It should
     * only be used in rich terminals with cursor and ANSI support. It is also the only component
     * that interacts with {@link NativeImageSystemIOWrappers#progressReporter}.
     */
    final class CharacterwiseStagePrinter extends StagePrinter<CharacterwiseStagePrinter> {
        @Override
        CharacterwiseStagePrinter getThis() {
            return this;
        }

        /**
         * Print directly and only append to keep track of the current line in case it needs to be
         * re-printed.
         */
        @Override
        CharacterwiseStagePrinter a(String value) {
            print(value);
            return super.a(value);
        }

        @Override
        CharacterwiseStagePrinter start(BuildStage stage) {
            super.start(stage);
            builderIO.progressReporter = ProgressReporter.this;
            return getThis();
        }

        @Override
        void reportProgress() {
            reprintLineIfNecessary();
            // Ensure builderIO is not listening for the next stdio write when printing progress
            // characters to stdout.
            builderIO.progressReporter = null;
            super.reportProgress();
            // Now that progress has been printed and has not been stopped, make sure builderIO
            // listens for the next stdio write again.
            builderIO.progressReporter = ProgressReporter.this;
        }

        @Override
        void end(double totalTime) {
            reprintLineIfNecessary();
            builderIO.progressReporter = null;
            super.end(totalTime);
        }

        void reprintLineIfNecessary() {
            if (builderIO.progressReporter == null) {
                printLineParts();
            }
        }

        @Override
        void flushln() {
            // No need to print lineParts because they are only needed for re-printing.
            lineParts.clear();
            println();
        }

        @Override
        void beforeNextStdioWrite() {
            colorStrategy.reset(); // Ensure color is reset.
            // Clear the current line.
            print('\r');
            int textLength = getCurrentTextLength();
            assert textLength > 0 : "linePrinter expected to hold current line content";
            for (int i = 0; i <= textLength; i++) {
                print(' ');
            }
            print('\r');
        }
    }

    final class TwoColumnPrinter extends LinePrinter<TwoColumnPrinter> {
        @Override
        TwoColumnPrinter getThis() {
            return this;
        }

        @Override
        TwoColumnPrinter a(String value) {
            super.a(value);
            return this;
        }

        TwoColumnPrinter jumpToMiddle() {
            int remaining = (CHARACTERS_PER_LINE / 2) - getCurrentTextLength();
            assert remaining >= 0 : "Column text too wide";
            a(Utils.stringFilledWith(remaining, " "));
            assert !isEnabled || getCurrentTextLength() == CHARACTERS_PER_LINE / 2;
            return this;
        }

        @Override
        void flushln() {
            print(outputPrefix);
            super.flushln();
        }
    }

    final class CenteredTextPrinter extends LinePrinter<CenteredTextPrinter> {
        @Override
        CenteredTextPrinter getThis() {
            return this;
        }

        @Override
        void flushln() {
            print(outputPrefix);
            String padding = Utils.stringFilledWith((Math.max(0, CHARACTERS_PER_LINE - getCurrentTextLength())) / 2, " ");
            print(padding);
            super.flushln();
        }
    }

    @SuppressWarnings("unused")
    private interface ColorStrategy {
        default void bold(AbstractPrinter<?> printer) {
        }

        default void blue(AbstractPrinter<?> printer) {
        }

        default void blueBold(AbstractPrinter<?> printer) {
        }

        default void redBold(AbstractPrinter<?> printer) {
        }

        default void yellowBold(AbstractPrinter<?> printer) {
        }

        default void dim(AbstractPrinter<?> printer) {
        }

        default void reset(AbstractPrinter<?> printer) {
        }

        default void reset() {
        }
    }

    final class ColorlessStrategy implements ColorStrategy {
    }

    final class ColorfulStrategy implements ColorStrategy {
        @Override
        public void bold(AbstractPrinter<?> printer) {
            printer.a(ANSI.BOLD);
        }

        @Override
        public void blue(AbstractPrinter<?> printer) {
            printer.a(ANSI.BLUE);
        }

        @Override
        public void blueBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.BLUE_BOLD);
        }

        @Override
        public void redBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.RED_BOLD);
        }

        @Override
        public void yellowBold(AbstractPrinter<?> printer) {
            printer.a(ANSI.YELLOW_BOLD);
        }

        @Override
        public void dim(AbstractPrinter<?> printer) {
            printer.a(ANSI.DIM);
        }

        @Override
        public void reset(AbstractPrinter<?> printer) {
            printer.a(ANSI.RESET);
        }

        @Override
        public void reset() {
            print(ANSI.RESET);
        }
    }

    private interface LinkStrategy {
        void link(AbstractPrinter<?> printer, String text, String url);

        String asDocLink(String text, String htmlAnchor);

        default void link(AbstractPrinter<?> printer, Path path) {
            Path normalized = path.normalize();
            link(printer, normalized.toString(), normalized.toUri().toString());
        }

        default void doclink(AbstractPrinter<?> printer, String text, String htmlAnchor) {
            link(printer, text, STAGE_DOCS_URL + htmlAnchor);
        }
    }

    final class LinklessStrategy implements LinkStrategy {
        @Override
        public void link(AbstractPrinter<?> printer, String text, String url) {
            printer.a(text);
        }

        @Override
        public String asDocLink(String text, String htmlAnchor) {
            return text;
        }
    }

    final class LinkyStrategy implements LinkStrategy {
        /** Adding link part individually for {@link LinePrinter#getCurrentTextLength()}. */
        @Override
        public void link(AbstractPrinter<?> printer, String text, String url) {
            printer.a(ANSI.LINK_START + url).a(ANSI.LINK_TEXT).a(text).a(ANSI.LINK_END);
        }

        @Override
        public String asDocLink(String text, String htmlAnchor) {
            return String.format(ANSI.LINK_FORMAT, STAGE_DOCS_URL + htmlAnchor, text);
        }
    }

    private static class ANSI {
        static final String ESCAPE = "\033";
        static final String RESET = ESCAPE + "[0m";
        static final String BOLD = ESCAPE + "[1m";
        static final String DIM = ESCAPE + "[2m";

        static final String LINK_START = ESCAPE + "]8;;";
        static final String LINK_TEXT = ESCAPE + "\\";
        static final String LINK_END = LINK_START + LINK_TEXT;
        static final String LINK_FORMAT = LINK_START + "%s" + LINK_TEXT + "%s" + LINK_END;

        static final String BLUE = ESCAPE + "[0;34m";

        static final String RED_BOLD = ESCAPE + "[1;31m";
        static final String YELLOW_BOLD = ESCAPE + "[1;33m";
        static final String BLUE_BOLD = ESCAPE + "[1;34m";
    }
}
