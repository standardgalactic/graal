/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
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
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TimerTask;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.printer.GraalDebugHandlersFactory;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;

import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.AnalysisError.ParsingError;
import com.oracle.graal.pointsto.util.GraalAccess;
import com.oracle.graal.pointsto.util.ParallelExecutionException;
import com.oracle.graal.pointsto.util.Timer;
import com.oracle.graal.pointsto.util.Timer.StopTimer;
import com.oracle.graal.pointsto.util.TimerCollection;
import com.oracle.svm.core.FallbackExecutor;
import com.oracle.svm.core.JavaMainWrapper;
import com.oracle.svm.core.JavaMainWrapper.JavaMainSupport;
import com.oracle.svm.core.OS;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.option.SubstrateOptionsParser;
import com.oracle.svm.core.util.InterruptImageBuilding;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.UserError.UserException;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.analysis.NativeImagePointsToAnalysis;
import com.oracle.svm.hosted.code.CEntryPointData;
import com.oracle.svm.hosted.image.AbstractImage.NativeImageKind;
import com.oracle.svm.hosted.jdk.NativeImageClassLoaderSupportJDK11OrLater;
import com.oracle.svm.hosted.option.HostedOptionParser;
import com.oracle.svm.util.ClassUtil;
import com.oracle.svm.util.ModuleSupport;
import com.oracle.svm.util.ReflectionUtil;
import com.oracle.svm.util.ReflectionUtil.ReflectionUtilError;

import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.runtime.JVMCI;

public class NativeImageGeneratorRunner {

    private volatile NativeImageGenerator generator;
    public static final String IMAGE_BUILDER_ARG_FILE_OPTION = "--image-args-file=";

    public static void main(String[] args) {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        arguments = extractDriverArguments(arguments);
        final String[] classPath = extractImagePathEntries(arguments, SubstrateOptions.IMAGE_CLASSPATH_PREFIX);
        final String[] modulePath = extractImagePathEntries(arguments, SubstrateOptions.IMAGE_MODULEPATH_PREFIX);
        int watchPID = extractWatchPID(arguments);
        TimerTask timerTask = null;
        if (watchPID >= 0) {
            VMError.guarantee(OS.getCurrent().hasProcFS, SubstrateOptions.WATCHPID_PREFIX + " <pid> requires system with /proc");
            timerTask = new TimerTask() {
                int cmdlineHashCode = 0;

                @Override
                public void run() {
                    try {
                        int currentCmdlineHashCode = Arrays.hashCode(Files.readAllBytes(Paths.get("/proc/" + watchPID + "/cmdline")));
                        if (cmdlineHashCode == 0) {
                            cmdlineHashCode = currentCmdlineHashCode;
                        } else if (currentCmdlineHashCode != cmdlineHashCode) {
                            System.exit(1);
                        }
                    } catch (IOException e) {
                        System.exit(1);
                    }
                }
            };
            java.util.Timer timer = new java.util.Timer("native-image pid watcher");
            timer.scheduleAtFixedRate(timerTask, 0, 1000);
        }
        int exitStatus;
        ClassLoader applicationClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            ImageClassLoader imageClassLoader = installNativeImageClassLoader(classPath, modulePath, arguments);
            List<String> remainingArguments = imageClassLoader.classLoaderSupport.getRemainingArguments();
            if (!remainingArguments.isEmpty()) {
                throw UserError.abort("Unknown options: %s", String.join(" ", remainingArguments));
            }
            exitStatus = new NativeImageGeneratorRunner().build(imageClassLoader);
        } catch (UserException e) {
            reportUserError(e.getMessage());
            exitStatus = 1;
        } catch (InterruptImageBuilding e) {
            if (e.getReason().isPresent()) {
                if (!e.getReason().get().isEmpty()) {
                    NativeImageGeneratorRunner.info(e.getReason().get());
                }
                exitStatus = 0;
            } else {
                /* InterruptImageBuilding without explicit reason is exit code 3 */
                exitStatus = 3;
            }
        } catch (Throwable err) {
            reportFatalError(err);
            exitStatus = 1;
        } finally {
            uninstallNativeImageClassLoader();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            if (timerTask != null) {
                timerTask.cancel();
            }
        }
        System.exit(exitStatus);
    }

    public static void uninstallNativeImageClassLoader() {
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        if (loader instanceof NativeImageSystemClassLoader) {
            ((NativeImageSystemClassLoader) loader).setNativeImageClassLoader(null);
        }
    }

    /**
     * Installs a class loader hierarchy that resolves classes and resources available in
     * {@code classpath} and {@code modulepath}. The parent for the installed {@code ClassLoader} is
     * the default system class loader (jdk.internal.loader.ClassLoaders.AppClassLoader and
     * sun.misc.Launcher.AppClassLoader for JDK11, 8 respectively).
     *
     * We use a custom system class loader {@link NativeImageSystemClassLoader} that delegates to
     * the {@code ClassLoader} that {@link NativeImageClassLoaderSupport} creates, thus allowing the
     * resolution of classes in {@code classpath} and {@code modulepath} via system class loader.
     *
     * @param classpath for the application and image should be built for.
     * @param modulepath for the application and image should be built for (only for Java >= 11).
     * @param arguments
     * @return NativeImageClassLoaderSupport that exposes the {@code ClassLoader} for image building
     *         via {@link NativeImageClassLoaderSupport#getClassLoader()}.
     */
    public static ImageClassLoader installNativeImageClassLoader(String[] classpath, String[] modulepath, List<String> arguments) {
        NativeImageSystemClassLoader nativeImageSystemClassLoader = NativeImageSystemClassLoader.singleton();
        AbstractNativeImageClassLoaderSupport nativeImageClassLoaderSupport = new NativeImageClassLoaderSupportJDK11OrLater(nativeImageSystemClassLoader.defaultSystemClassLoader, classpath,
                        modulepath);
        nativeImageClassLoaderSupport.setupHostedOptionParser(arguments);
        /* Perform additional post-processing with the created nativeImageClassLoaderSupport */
        for (NativeImageClassLoaderPostProcessing postProcessing : ServiceLoader.load(NativeImageClassLoaderPostProcessing.class)) {
            postProcessing.apply(nativeImageClassLoaderSupport);
        }
        ClassLoader nativeImageClassLoader = nativeImageClassLoaderSupport.getClassLoader();
        Thread.currentThread().setContextClassLoader(nativeImageClassLoader);
        /*
         * Make NativeImageSystemClassLoader delegate to the classLoader provided by
         * NativeImageClassLoaderSupport, enabling resolution of classes and resources during image
         * build-time present on the image classpath and modulepath.
         */
        nativeImageSystemClassLoader.setNativeImageClassLoader(nativeImageClassLoader);

        if (!nativeImageClassLoaderSupport.imagecp.isEmpty()) {
            ModuleSupport.openModuleByClass(JavaVersionUtil.class, null);
        }

        /*
         * Iterating all classes can already trigger class initialization: We need annotation
         * information, which triggers class initialization of annotation classes and enum classes
         * referenced by annotations. Therefore, we need to have the system properties that indicate
         * "during image build" set up already at this time.
         */
        NativeImageGenerator.setSystemPropertiesForImageEarly();

        return new ImageClassLoader(NativeImageGenerator.getTargetPlatform(nativeImageClassLoader), nativeImageClassLoaderSupport);
    }

    public static List<String> extractDriverArguments(List<String> args) {
        ArrayList<String> result = args.stream().filter(arg -> !arg.startsWith(IMAGE_BUILDER_ARG_FILE_OPTION)).collect(Collectors.toCollection(ArrayList::new));
        Optional<String> argsFile = args.stream().filter(arg -> arg.startsWith(IMAGE_BUILDER_ARG_FILE_OPTION)).findFirst();

        if (argsFile.isPresent()) {
            String argFilePath = argsFile.get().substring(IMAGE_BUILDER_ARG_FILE_OPTION.length());
            try {
                String options = new String(Files.readAllBytes(Paths.get(argFilePath)));
                result.addAll(Arrays.asList(options.split("\0")));
            } catch (IOException e) {
                throw VMError.shouldNotReachHere("Exception occurred during image builder argument file processing.", e);
            }
        }
        return result;
    }

    public static String[] extractImagePathEntries(List<String> arguments, String pathPrefix) {
        int cpArgIndex = arguments.indexOf(pathPrefix);
        String msgTail = " '" + pathPrefix + " <Path entries separated by File.pathSeparator>' argument.";
        if (cpArgIndex == -1) {
            return new String[0];
        }
        arguments.remove(cpArgIndex);
        try {
            String imageClasspath = arguments.remove(cpArgIndex);
            return imageClasspath.split(File.pathSeparator, Integer.MAX_VALUE);
        } catch (IndexOutOfBoundsException e) {
            throw UserError.abort("Missing path entries for %s", msgTail);
        }
    }

    public static int extractWatchPID(List<String> arguments) {
        int cpIndex = arguments.indexOf(SubstrateOptions.WATCHPID_PREFIX);
        if (cpIndex >= 0) {
            if (cpIndex + 1 >= arguments.size()) {
                throw UserError.abort("ProcessID must be provided after the '%s' argument.", SubstrateOptions.WATCHPID_PREFIX);
            }
            arguments.remove(cpIndex);
            String pidStr = arguments.get(cpIndex);
            arguments.remove(cpIndex);
            return Integer.parseInt(pidStr);
        }
        return -1;
    }

    private static void reportToolUserError(String msg) {
        reportUserError("native-image " + msg);
    }

    private static boolean isValidArchitecture() {
        final Architecture originalTargetArch = GraalAccess.getOriginalTarget().arch;
        return originalTargetArch instanceof AMD64 || originalTargetArch instanceof AArch64;
    }

    private static boolean isValidOperatingSystem() {
        return OS.LINUX.isCurrent() || OS.DARWIN.isCurrent() || OS.WINDOWS.isCurrent();
    }

    @SuppressWarnings("try")
    private int buildImage(ImageClassLoader classLoader) {
        if (!verifyValidJavaVersionAndPlatform()) {
            return 1;
        }

        HostedOptionParser optionParser = classLoader.classLoaderSupport.getHostedOptionParser();
        OptionValues parsedHostedOptions = classLoader.classLoaderSupport.getParsedHostedOptions();

        String imageName = SubstrateOptions.Name.getValue(parsedHostedOptions);
        TimerCollection timerCollection = new TimerCollection(imageName);
        Timer totalTimer = timerCollection.get(TimerCollection.Registry.TOTAL);

        if (NativeImageOptions.ListCPUFeatures.getValue(parsedHostedOptions)) {
            printCPUFeatures(classLoader.platform);
            return 0;
        }

        ForkJoinPool analysisExecutor = null;
        ForkJoinPool compilationExecutor = null;

        ProgressReporter reporter = new ProgressReporter(parsedHostedOptions);
        boolean wasSuccessfulBuild = false;
        try (StopTimer ignored = totalTimer.start()) {
            Timer classlistTimer = timerCollection.get(TimerCollection.Registry.CLASSLIST);
            try (StopTimer ignored1 = classlistTimer.start()) {
                classLoader.initAllClasses();
            }

            DebugContext debug = new DebugContext.Builder(parsedHostedOptions, new GraalDebugHandlersFactory(GraalAccess.getOriginalSnippetReflection())).build();

            if (imageName.length() == 0) {
                throw UserError.abort("No output file name specified. Use '%s'.", SubstrateOptionsParser.commandArgument(SubstrateOptions.Name, "<output-file>"));
            }
            try {

                // print the time here to avoid interactions with flags processing
                classlistTimer.print();

                Map<Method, CEntryPointData> entryPoints = new HashMap<>();
                Pair<Method, CEntryPointData> mainEntryPointData = Pair.empty();
                JavaMainSupport javaMainSupport = null;

                NativeImageKind imageKind;
                boolean isStaticExecutable = SubstrateOptions.StaticExecutable.getValue(parsedHostedOptions);
                boolean isSharedLibrary = SubstrateOptions.SharedLibrary.getValue(parsedHostedOptions);
                if (isStaticExecutable && isSharedLibrary) {
                    throw UserError.abort("Cannot pass both option: %s and %s", SubstrateOptionsParser.commandArgument(SubstrateOptions.SharedLibrary, "+"),
                                    SubstrateOptionsParser.commandArgument(SubstrateOptions.StaticExecutable, "+"));
                } else if (isSharedLibrary) {
                    imageKind = NativeImageKind.SHARED_LIBRARY;
                } else if (isStaticExecutable) {
                    imageKind = NativeImageKind.STATIC_EXECUTABLE;
                } else {
                    imageKind = NativeImageKind.EXECUTABLE;
                }

                String className = SubstrateOptions.Class.getValue(parsedHostedOptions);
                String moduleName = SubstrateOptions.Module.getValue(parsedHostedOptions);
                if (imageKind.isExecutable && moduleName.isEmpty() && className.isEmpty()) {
                    throw UserError.abort("Must specify main entry point class when building %s native image. Use '%s'.", imageKind,
                                    SubstrateOptionsParser.commandArgument(SubstrateOptions.Class, "<fully-qualified-class-name>"));
                }

                reporter.printStart(imageName, imageKind);

                if (!className.isEmpty() || !moduleName.isEmpty()) {
                    Method mainEntryPoint;
                    Class<?> mainClass;
                    try {
                        Object mainModule = null;
                        if (!moduleName.isEmpty()) {
                            mainModule = classLoader.findModule(moduleName)
                                            .orElseThrow(() -> UserError.abort("Module " + moduleName + " for mainclass not found."));
                        }
                        if (className.isEmpty()) {
                            className = classLoader.getMainClassFromModule(mainModule)
                                            .orElseThrow(() -> UserError.abort("module %s does not have a ModuleMainClass attribute, use -m <module>/<main-class>", moduleName));
                        }
                        mainClass = classLoader.forName(className, mainModule);
                        if (mainClass == null) {
                            throw UserError.abort(classLoader.getMainClassNotFoundErrorMessage(className));
                        }
                    } catch (ClassNotFoundException ex) {
                        throw UserError.abort(classLoader.getMainClassNotFoundErrorMessage(className));
                    }
                    String mainEntryPointName = SubstrateOptions.Method.getValue(parsedHostedOptions);
                    if (mainEntryPointName.isEmpty()) {
                        throw UserError.abort("Must specify main entry point method when building %s native image. Use '%s'.", imageKind,
                                        SubstrateOptionsParser.commandArgument(SubstrateOptions.Method, "<method-name>"));
                    }
                    try {
                        /*
                         * First look for an main method with the C-level signature for arguments.
                         */
                        mainEntryPoint = mainClass.getDeclaredMethod(mainEntryPointName, int.class, CCharPointerPointer.class);
                    } catch (NoSuchMethodException ignored2) {
                        Method javaMainMethod;
                        try {
                            /*
                             * If no C-level main method was found, look for a Java-level main
                             * method and use our wrapper to invoke it.
                             */
                            javaMainMethod = ReflectionUtil.lookupMethod(mainClass, mainEntryPointName, String[].class);
                        } catch (ReflectionUtilError ex) {
                            throw UserError.abort(ex.getCause(),
                                            "Method '%s.%s' is declared as the main entry point but it can not be found. " +
                                                            "Make sure that class '%s' is on the classpath and that method '%s(String[])' exists in that class.",
                                            mainClass.getName(),
                                            mainEntryPointName,
                                            mainClass.getName(),
                                            mainEntryPointName);
                        }

                        if (javaMainMethod.getReturnType() != void.class) {
                            throw UserError.abort("Java main method '%s.%s(String[])' does not have the return type 'void'.", mainClass.getName(), mainEntryPointName);
                        }
                        final int mainMethodModifiers = javaMainMethod.getModifiers();
                        if (!Modifier.isStatic(mainMethodModifiers)) {
                            throw UserError.abort("Java main method '%s.%s(String[])' is not static.", mainClass.getName(), mainEntryPointName);
                        }
                        if (!Modifier.isPublic(mainMethodModifiers)) {
                            throw UserError.abort("Java main method '%s.%s(String[])' is not public.", mainClass.getName(), mainEntryPointName);
                        }
                        javaMainSupport = new JavaMainSupport(javaMainMethod);
                        mainEntryPoint = JavaMainWrapper.class.getDeclaredMethod("run", int.class, CCharPointerPointer.class);
                    }
                    CEntryPoint annotation = mainEntryPoint.getAnnotation(CEntryPoint.class);
                    if (annotation == null) {
                        throw UserError.abort("Entry point must have the '@%s' annotation", CEntryPoint.class.getSimpleName());
                    }

                    Class<?>[] pt = mainEntryPoint.getParameterTypes();
                    if (pt.length != 2 || pt[0] != int.class || pt[1] != CCharPointerPointer.class || mainEntryPoint.getReturnType() != int.class) {
                        throw UserError.abort("Main entry point must have signature 'int main(int argc, CCharPointerPointer argv)'.");
                    }
                    mainEntryPointData = Pair.create(mainEntryPoint, CEntryPointData.create(mainEntryPoint, imageKind.mainEntryPointName));
                }

                int maxConcurrentThreads = NativeImageOptions.getMaximumNumberOfConcurrentThreads(parsedHostedOptions);
                analysisExecutor = NativeImagePointsToAnalysis.createExecutor(debug, NativeImageOptions.getMaximumNumberOfAnalysisThreads(parsedHostedOptions));
                compilationExecutor = NativeImagePointsToAnalysis.createExecutor(debug, maxConcurrentThreads);
                generator = new NativeImageGenerator(classLoader, optionParser, mainEntryPointData, reporter);
                generator.run(entryPoints, javaMainSupport, imageName, imageKind, SubstitutionProcessor.IDENTITY,
                                compilationExecutor, analysisExecutor, optionParser.getRuntimeOptionNames(), timerCollection);
                wasSuccessfulBuild = true;
            } finally {
                if (!wasSuccessfulBuild) {
                    reporter.printUnsuccessfulInitializeEnd();
                }
            }
        } catch (InterruptImageBuilding e) {
            if (analysisExecutor != null) {
                analysisExecutor.shutdownNow();
            }
            if (compilationExecutor != null) {
                compilationExecutor.shutdownNow();
            }
            throw e;
        } catch (FallbackFeature.FallbackImageRequest e) {
            if (FallbackExecutor.class.getName().equals(SubstrateOptions.Class.getValue())) {
                NativeImageGeneratorRunner.reportFatalError(e, "FallbackImageRequest while building fallback image.");
                return 1;
            }
            reportUserException(e, parsedHostedOptions, NativeImageGeneratorRunner::warn);
            return 2;
        } catch (ParsingError e) {
            NativeImageGeneratorRunner.reportFatalError(e);
            return 1;
        } catch (UserException | AnalysisError e) {
            reportUserError(e, parsedHostedOptions);
            return 1;
        } catch (ParallelExecutionException pee) {
            boolean hasUserError = false;
            for (Throwable exception : pee.getExceptions()) {
                if (exception instanceof UserException) {
                    reportUserError(exception, parsedHostedOptions);
                    hasUserError = true;
                } else if (exception instanceof AnalysisError && !(exception instanceof ParsingError)) {
                    reportUserError(exception, parsedHostedOptions);
                    hasUserError = true;
                }
            }
            if (hasUserError) {
                return 1;
            }

            if (pee.getExceptions().size() > 1) {
                System.err.println(pee.getExceptions().size() + " fatal errors detected:");
            }
            for (Throwable exception : pee.getExceptions()) {
                NativeImageGeneratorRunner.reportFatalError(exception);
            }
            return 1;
        } catch (Throwable e) {
            NativeImageGeneratorRunner.reportFatalError(e);
            return 1;
        } finally {
            totalTimer.print();
            if (imageName != null && generator != null) {
                reporter.printEpilog(imageName, generator, wasSuccessfulBuild, parsedHostedOptions);
            }
            NativeImageGenerator.clearSystemPropertiesForImage();
            ImageSingletonsSupportImpl.HostedManagement.clear();
        }
        return 0;
    }

    public static boolean verifyValidJavaVersionAndPlatform() {
        if (!isValidArchitecture()) {
            reportToolUserError("runs on AMD64 and AArch64 only. Detected architecture: " + ClassUtil.getUnqualifiedName(GraalAccess.getOriginalTarget().arch.getClass()));
        }
        if (!isValidOperatingSystem()) {
            reportToolUserError("runs on Linux, Mac OS X and Windows only. Detected OS: " + System.getProperty("os.name"));
            return false;
        }

        return true;
    }

    public static void printCPUFeatures(Platform platform) {
        StringBuilder message = new StringBuilder();
        Architecture arch = JVMCI.getRuntime().getHostJVMCIBackend().getTarget().arch;
        if (NativeImageGenerator.includedIn(platform, Platform.AMD64.class)) {
            message.append("All AMD64 CPUFeatures: ").append(Arrays.toString(AMD64.CPUFeature.values()));
            if (arch instanceof AMD64) {
                message.append("\nHost machine AMD64 CPUFeatures: ").append(((AMD64) arch).getFeatures().toString());
            }
        } else {
            assert NativeImageGenerator.includedIn(platform, Platform.AARCH64.class);
            message.append("All AArch64 CPUFeatures: ").append(Arrays.toString(AArch64.CPUFeature.values()));
            if (arch instanceof AArch64) {
                message.append("\nHost machine AArch64 CPUFeatures: ").append(((AArch64) arch).getFeatures().toString());
            }
        }
        System.out.println(message);
    }

    public static String getJavaVersion() {
        return System.getProperty("java.version");
    }

    /**
     * Reports an unexpected error caused by a crash in the SVM image builder.
     *
     * @param e error to be reported.
     */
    protected static void reportFatalError(Throwable e) {
        System.err.print("Fatal error: ");
        e.printStackTrace();
    }

    /**
     * Reports an unexpected error caused by a crash in the SVM image builder.
     *
     * @param e error to be reported.
     * @param msg message to report.
     */
    protected static void reportFatalError(Throwable e, String msg) {
        System.err.print("Fatal error: " + msg);
        e.printStackTrace();
    }

    /**
     * Function for reporting all fatal errors in SVM.
     *
     * @param msg error message that is printed.
     */
    public static void reportUserError(String msg) {
        System.err.println("Error: " + msg);
    }

    /**
     * Function for reporting all fatal errors in SVM.
     *
     * @param e error message that is printed.
     * @param parsedHostedOptions
     */
    public static void reportUserError(Throwable e, OptionValues parsedHostedOptions) {
        reportUserException(e, parsedHostedOptions, NativeImageGeneratorRunner::reportUserError);
    }

    private static void reportUserException(Throwable e, OptionValues parsedHostedOptions, Consumer<String> report) {
        if (e instanceof UserException) {
            UserException ue = (UserException) e;
            for (String message : ue.getMessages()) {
                report.accept(message);
            }
        } else {
            report.accept(e.getMessage());
        }
        if (parsedHostedOptions != null && NativeImageOptions.ReportExceptionStackTraces.getValue(parsedHostedOptions)) {
            e.printStackTrace();
        } else {
            report.accept("Use " + SubstrateOptionsParser.commandArgument(NativeImageOptions.ReportExceptionStackTraces, "+") +
                            " to print stacktrace of underlying exception");
        }
    }

    /**
     * Report an informational message in SVM.
     *
     * @param msg message that is printed.
     */
    private static void info(String msg) {
        System.out.println("Info: " + msg);
    }

    /**
     * Report a warning message in SVM.
     *
     * @param msg warning message that is printed.
     */
    private static void warn(String msg) {
        System.err.println("Warning: " + msg);
    }

    public int build(ImageClassLoader imageClassLoader) {
        return buildImage(imageClassLoader);
    }

    /**
     * Command line entry point when running on JDK9+. This is required to dynamically export Graal
     * to SVM and it requires {@code --add-exports=java.base/jdk.internal.module=ALL-UNNAMED} to be
     * on the VM command line.
     *
     * Note: This is a workaround until GR-16855 is resolved.
     */
    public static class JDK9Plus {

        public static void main(String[] args) {
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("org.graalvm.sdk", false);
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("org.graalvm.truffle", false);
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.ci", false);
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.compiler", false);
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("jdk.internal.vm.compiler.management", true);
            ModuleSupport.exportAndOpenAllPackagesToUnnamed("com.oracle.graal.graal_enterprise", true);
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.loader", false);
            if (JavaVersionUtil.JAVA_SPEC >= 17) {
                ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.misc", false);
            }
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.text.spi", false);
            ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "jdk.internal.org.objectweb.asm", false);
            if (JavaVersionUtil.JAVA_SPEC >= 17) {
                ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.reflect.annotation", false);
                ModuleSupport.exportAndOpenPackageToUnnamed("java.base", "sun.security.jca", false);
                ModuleSupport.exportAndOpenPackageToUnnamed("jdk.jdeps", "com.sun.tools.classfile", false);
            }
            NativeImageGeneratorRunner.main(args);
        }
    }
}
