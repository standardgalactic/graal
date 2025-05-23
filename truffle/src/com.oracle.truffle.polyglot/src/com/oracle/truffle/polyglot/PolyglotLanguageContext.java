/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;
import static com.oracle.truffle.polyglot.EngineAccessor.LANGUAGE;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import org.graalvm.collections.UnmodifiableEconomicSet;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.APIAccess;
import org.graalvm.polyglot.proxy.Proxy;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

final class PolyglotLanguageContext implements PolyglotImpl.VMObject {

    private static final TruffleLogger LOG = TruffleLogger.getLogger(PolyglotEngineImpl.OPTION_GROUP_ENGINE, PolyglotLanguageContext.class);

    /*
     * Lazily created when a language context is created.
     */
    final class Lazy {

        final Set<PolyglotThread> activePolyglotThreads;
        final Object polyglotGuestBindings;
        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
        @CompilationFinal PolyglotLanguageInstance languageInstance;
        @CompilationFinal Map<String, LanguageInfo> accessibleInternalLanguages;
        @CompilationFinal Map<String, LanguageInfo> accessiblePublicLanguages;
        final Object internalFileSystemContext;
        final Object publicFileSystemContext;
        final ReentrantLock operationLock;

        private boolean multipleThreadsInitialized;

        Lazy(PolyglotLanguageInstance languageInstance, PolyglotContextConfig config) {
            this.languageInstance = languageInstance;
            this.activePolyglotThreads = new HashSet<>();
            this.polyglotGuestBindings = new PolyglotBindings(PolyglotLanguageContext.this);
            this.uncaughtExceptionHandler = new PolyglotUncaughtExceptionHandler();
            this.computeAccessPermissions(config);
            // file systems are patched after preinitialization internally using a delegate field
            this.publicFileSystemContext = EngineAccessor.LANGUAGE.createFileSystemContext(PolyglotLanguageContext.this, config.fileSystem);
            this.internalFileSystemContext = EngineAccessor.LANGUAGE.createFileSystemContext(PolyglotLanguageContext.this, config.internalFileSystem);
            this.operationLock = new ReentrantLock();
        }

        void computeAccessPermissions(PolyglotContextConfig config) {
            this.accessibleInternalLanguages = computeAccessibleLanguages(config, true);
            this.accessiblePublicLanguages = computeAccessibleLanguages(config, false);
        }

        private Map<String, LanguageInfo> computeAccessibleLanguages(PolyglotContextConfig config, boolean internal) {
            PolyglotLanguage thisLanguage = languageInstance.language;
            if (thisLanguage.isHost()) {
                return languageInstance.getEngine().idToInternalLanguageInfo;
            }
            boolean embedderAllAccess = config.allowedPublicLanguages.isEmpty();
            PolyglotEngineImpl engine = languageInstance.getEngine();
            Set<String> configuredAccess = null;
            UnmodifiableEconomicSet<String> configured = engine.getAPIAccess().getEvalAccess(config.polyglotAccess, thisLanguage.getId());
            if (configured != null) {
                configuredAccess = new HashSet<>();
                configuredAccess.addAll(Arrays.asList(configured.toArray(new String[configured.size()])));
            }

            Set<String> resolveLanguages;
            if (embedderAllAccess) {
                if (configuredAccess == null) {
                    if (internal) {
                        return engine.idToInternalLanguageInfo;
                    } else {
                        resolveLanguages = new HashSet<>();
                        resolveLanguages.addAll(engine.idToInternalLanguageInfo.keySet());
                    }
                } else {
                    resolveLanguages = new HashSet<>(configuredAccess);
                    resolveLanguages.add(thisLanguage.getId());
                }
            } else {
                if (configuredAccess == null) {
                    // all access configuration
                    configuredAccess = config.allowedPublicLanguages;
                }
                resolveLanguages = new HashSet<>(configuredAccess);
                resolveLanguages.add(thisLanguage.getId());
            }

            Map<String, LanguageInfo> resolvedLanguages = new LinkedHashMap<>();
            for (String id : resolveLanguages) {
                PolyglotLanguage resolvedLanguage = engine.idToLanguage.get(id);
                if (resolvedLanguage != null) { // resolved languages might not be on the
                                                // class-path.
                    if (!internal && resolvedLanguage.cache.isInternal()) {
                        // filter internal
                        continue;
                    }
                    resolvedLanguages.put(id, resolvedLanguage.info);
                }
            }
            if (internal) {
                addDependentLanguages(engine, resolvedLanguages, thisLanguage);
            }

            // all internal languages are accessible by default
            if (internal) {
                for (Entry<String, PolyglotLanguage> entry : languageInstance.getEngine().idToLanguage.entrySet()) {
                    if (entry.getValue().cache.isInternal()) {
                        resolvedLanguages.put(entry.getKey(), entry.getValue().info);
                    }
                }
                assert assertPermissionsConsistent(resolvedLanguages, languageInstance.language, config);
            }
            return resolvedLanguages;
        }

        private boolean assertPermissionsConsistent(Map<String, LanguageInfo> resolvedLanguages, PolyglotLanguage thisLanguage, PolyglotContextConfig config) {
            for (Entry<String, PolyglotLanguage> entry : languageInstance.getEngine().idToLanguage.entrySet()) {
                boolean permitted = config.isAccessPermitted(thisLanguage, entry.getValue());
                assert permitted == resolvedLanguages.containsKey(entry.getKey()) : "inconsistent access permissions";
            }
            return true;
        }

        private void addDependentLanguages(PolyglotEngineImpl engine, Map<String, LanguageInfo> resolvedLanguages, PolyglotLanguage currentLanguage) {
            for (String dependentLanguage : currentLanguage.cache.getDependentLanguages()) {
                PolyglotLanguage dependent = engine.idToLanguage.get(dependentLanguage);
                if (dependent == null) { // dependent languages might not exist.
                    continue;
                }
                if (resolvedLanguages.containsKey(dependentLanguage)) {
                    continue; // cycle or duplicate detection
                }
                resolvedLanguages.put(dependentLanguage, dependent.info);
                addDependentLanguages(engine, resolvedLanguages, dependent);
            }
        }
    }

    final PolyglotContextImpl context;
    final PolyglotLanguage language;
    final boolean eventsEnabled;

    private Thread creatingThread;
    private volatile boolean created;
    private volatile boolean initialized;
    volatile boolean finalized;
    volatile boolean exited;
    @CompilationFinal private volatile Value hostBindings;
    @CompilationFinal private volatile Lazy lazy;
    @CompilationFinal volatile Env env; // effectively final
    @CompilationFinal private volatile List<Object> languageServices = Collections.emptyList();

    PolyglotLanguageContext(PolyglotContextImpl context, PolyglotLanguage language) {
        this.context = context;
        this.language = language;
        this.eventsEnabled = !language.isHost();
    }

    boolean isPolyglotBindingsAccessAllowed() {
        if (context.config.polyglotAccess == PolyglotAccess.ALL) {
            return true;
        }

        UnmodifiableEconomicSet<String> accessibleLanguages = getAPIAccess().getBindingsAccess(context.config.polyglotAccess);
        if (accessibleLanguages == null) {
            return true;
        }
        return accessibleLanguages.contains(language.getId());
    }

    boolean isPolyglotEvalAllowed(String targetLanguage) {
        if (context.config.polyglotAccess == PolyglotAccess.ALL) {
            return true;
        } else if (targetLanguage != null && language.getId().equals(targetLanguage)) {
            return true;
        }
        UnmodifiableEconomicSet<String> accessibleLanguages = getAPIAccess().getEvalAccess(context.config.polyglotAccess,
                        language.getId());
        if (accessibleLanguages == null || accessibleLanguages.isEmpty()) {
            return false;
        } else if (accessibleLanguages.size() > 1 || !accessibleLanguages.iterator().next().equals(language.getId())) {
            return targetLanguage == null || accessibleLanguages.contains(targetLanguage);
        }
        return false;
    }

    Thread.UncaughtExceptionHandler getPolyglotExceptionHandler() {
        assert env != null;
        return lazy.uncaughtExceptionHandler;
    }

    Map<String, LanguageInfo> getAccessibleLanguages(boolean allowInternalAndDependent) {
        Lazy l = lazy;
        if (l != null) {
            if (allowInternalAndDependent) {
                return lazy.accessibleInternalLanguages;
            } else {
                return lazy.accessiblePublicLanguages;
            }
        } else {
            return null;
        }
    }

    PolyglotLanguageInstance getLanguageInstanceOrNull() {
        Lazy l = this.lazy;
        if (l == null) {
            return null;
        }
        return l.languageInstance;
    }

    PolyglotLanguageInstance getLanguageInstance() {
        assert env != null;
        return lazy.languageInstance;
    }

    private void checkThreadAccess(Env localEnv) {
        assert Thread.holdsLock(context);
        boolean singleThreaded = context.isSingleThreaded();
        Thread firstFailingThread = null;
        for (PolyglotThreadInfo threadInfo : context.getSeenThreads().values()) {
            if (!LANGUAGE.isThreadAccessAllowed(localEnv, threadInfo.getThread(), singleThreaded)) {
                firstFailingThread = threadInfo.getThread();
                break;
            }
        }
        if (firstFailingThread != null) {
            throw PolyglotContextImpl.throwDeniedThreadAccess(firstFailingThread, singleThreaded, Arrays.asList(language));
        }
    }

    Object getContextImpl() {
        Env localEnv = env;
        if (localEnv != null) {
            return LANGUAGE.getContext(localEnv);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return null;
        }
    }

    Object getPublicFileSystemContext() {
        Lazy l = lazy;
        if (l != null) {
            return l.publicFileSystemContext;
        } else {
            return null;
        }
    }

    Object getInternalFileSystemContext() {
        Lazy l = lazy;
        if (l != null) {
            return l.internalFileSystemContext;
        } else {
            return null;
        }
    }

    Value getHostBindings() {
        assert initialized;
        if (this.hostBindings == null) {
            synchronized (this) {
                if (this.hostBindings == null) {
                    Object prev = language.engine.enterIfNeeded(context, true);
                    try {
                        Object scope = LANGUAGE.getScope(env);
                        if (scope == null) {
                            scope = new DefaultTopScope();
                        }
                        this.hostBindings = this.asValue(scope);
                    } finally {
                        language.engine.leaveIfNeeded(prev, context);
                    }
                }
            }
        }
        return this.hostBindings;
    }

    Object getPolyglotGuestBindings() {
        assert isInitialized();
        return this.lazy.polyglotGuestBindings;
    }

    boolean isInitialized() {
        return initialized;
    }

    CallTarget parseCached(PolyglotLanguage accessingLanguage, Source source, String[] argumentNames) throws AssertionError {
        ensureInitialized(accessingLanguage);
        PolyglotSourceCache cache = context.layer.getSourceCache();
        assert cache != null;
        return cache.parseCached(this, source, argumentNames);
    }

    Env requireEnv() {
        Env localEnv = this.env;
        if (localEnv == null) {
            throw shouldNotReachHere("No language context is active on this thread.");
        }
        return localEnv;
    }

    @SuppressWarnings("deprecation")
    boolean finalizeContext(boolean cancelOrExitOperation, boolean notifyInstruments) {
        ReentrantLock lock = lazy.operationLock;
        lock.lock();
        try {
            if (!initialized) {
                return false;
            }
            if (!finalized) {
                finalized = true;
                try {
                    LANGUAGE.finalizeContext(env);
                } catch (Throwable t) {
                    if (cancelOrExitOperation) {
                        /*
                         * finalizeContext can run guest code, and so truffle and cancel exceptions
                         * are expected. However, they must not fail the cancel operation, and so we
                         * just log them.
                         */
                        assert context.state.isClosing();
                        assert context.state.isInvalidOrClosed();
                        if (t instanceof com.oracle.truffle.api.TruffleException || t instanceof PolyglotEngineImpl.CancelExecution || t instanceof PolyglotContextImpl.ExitException) {
                            context.engine.getEngineLogger().log(Level.FINE,
                                            "Exception was thrown while finalizing a polyglot context that is being cancelled or exited. Such exceptions are expected during cancelling or exiting.",
                                            t);
                        } else {
                            throw t;
                        }
                    } else {
                        throw t;
                    }
                }
                if (eventsEnabled && notifyInstruments) {
                    EngineAccessor.INSTRUMENT.notifyLanguageContextFinalized(context.engine, context.creatorTruffleContext, language.info);
                }
                return true;
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    boolean exitContext(TruffleLanguage.ExitMode exitMode, int exitCode) {
        ReentrantLock lock = lazy.operationLock;
        lock.lock();
        try {
            if (!initialized) {
                return false;
            }
            if (!exited) {
                exited = true;
                try {
                    LANGUAGE.exitContext(env, exitMode, exitCode);
                } catch (Throwable t) {
                    if (exitMode == TruffleLanguage.ExitMode.HARD) {
                        if (t instanceof com.oracle.truffle.api.TruffleException || t instanceof PolyglotContextImpl.ExitException) {
                            if (t instanceof com.oracle.truffle.api.TruffleException && !context.state.isCancelling()) {
                                context.engine.getEngineLogger().log(Level.WARNING, "TruffleException thrown during exit notification! Languages are supposed to handle this kind of exceptions.", t);
                            } else {
                                context.engine.getEngineLogger().log(Level.FINE, "Exception thrown during exit notification!", t);
                            }
                        } else {
                            throw t;
                        }
                    } else {
                        throw t;
                    }
                }
                return true;
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    boolean dispose() {
        assert Thread.holdsLock(context);
        Env localEnv = this.env;
        if (localEnv != null) {
            if (!lazy.activePolyglotThreads.isEmpty()) {
                // this should show up as internal error so it does not use PolyglotEngineException
                throw new IllegalStateException("The language did not complete all polyglot threads but should have: " + lazy.activePolyglotThreads);
            }
            try {
                for (PolyglotThreadInfo threadInfo : context.getSeenThreads().values()) {
                    assert threadInfo != PolyglotThreadInfo.NULL;
                    final Thread thread = threadInfo.getThread();
                    if (thread == null) {
                        continue;
                    }
                    assert !threadInfo.isPolyglotThread(context) : "Polyglot threads must no longer be active in TruffleLanguage.finalizeContext, but polyglot thread " + thread.getName() +
                                    " is still active.";
                    if (!threadInfo.isCurrent() && threadInfo.isActive() && !context.state.isInvalidOrClosed()) {
                        /*
                         * No other thread than the current thread should be active here. However,
                         * we do this check only for non-invalid contexts for the following reasons.
                         * enteredCount for a thread can be incremented on the fast-path even though
                         * the thread is not allowed to enter in the end because the context is
                         * invalid and so the enter falls back to the slow path which checks the
                         * invalid flag. threadInfo.isActive() returns true in this case and we
                         * cannot tell whether it is because the thread is before the fallback to
                         * the slow path or it is already fully entered (which would be an error)
                         * without adding further checks to the fast path, and so we don't perform
                         * the check for invalid contexts. Non-invalid context can have the same
                         * problem with the enteredCount of one of its threads, but closing
                         * non-invalid context in that state is an user error.
                         */
                        throw PolyglotEngineException.illegalState("Another main thread was started while closing a polyglot context!");
                    }
                    LANGUAGE.disposeThread(localEnv, thread);
                }
                LANGUAGE.dispose(localEnv);
            } catch (Throwable t) {
                if (t instanceof com.oracle.truffle.api.TruffleException || t instanceof PolyglotEngineImpl.CancelExecution || t instanceof PolyglotContextImpl.ExitException) {
                    throw new IllegalStateException("Guest language code was run during language disposal!", t);
                }
                throw t;
            }
            return true;
        }
        return false;
    }

    void notifyDisposed(boolean notifyInstruments) {
        if (eventsEnabled && notifyInstruments) {
            EngineAccessor.INSTRUMENT.notifyLanguageContextDisposed(context.engine, context.creatorTruffleContext, language.info);
        }
    }

    Object[] enterThread(PolyglotThread thread) {
        assert isInitialized();
        assert Thread.currentThread() == thread;
        synchronized (context) {
            Object[] prev = context.engine.enter(context);
            lazy.activePolyglotThreads.add(thread);
            return prev;
        }
    }

    void leaveAndDisposePolyglotThread(Object[] prev, PolyglotThread thread) {
        assert isInitialized();
        synchronized (context) {
            context.leaveAndDisposeThread(prev, thread);
            boolean removed = lazy.activePolyglotThreads.remove(thread);
            assert removed : "thread was not removed";
        }
    }

    boolean isCreated() {
        return created;
    }

    void ensureCreated(PolyglotLanguage accessingLanguage) {
        if (creatingThread == Thread.currentThread()) {
            throw PolyglotEngineException.illegalState(String.format("Cyclic access to language context for language %s. " +
                            "The context is currently being created.", language.getId()));
        }

        if (!created) {
            checkAccess(accessingLanguage);

            Map<String, Object> creatorConfig = context.creator == language ? context.creatorArguments : Collections.emptyMap();
            PolyglotContextConfig contextConfig = context.config;

            PolyglotLanguageInstance languageInstance;
            PolyglotSharingLayer layer = context.layer;
            synchronized (context.engine.lock) {
                if (language.isHost()) {
                    languageInstance = layer.allocateHostLanguage(language);
                } else {
                    context.claimSharingLayer(language);
                    languageInstance = layer.allocateInstance(context, language);
                }
            }

            synchronized (context) {
                if (!created) {
                    if (eventsEnabled) {
                        EngineAccessor.INSTRUMENT.notifyLanguageContextCreate(context.engine, context.creatorTruffleContext, language.info);
                    }
                    boolean wasCreated = false;
                    try {
                        Env localEnv = LANGUAGE.createEnv(this, languageInstance.spi, contextConfig.out,
                                        contextConfig.err,
                                        contextConfig.in,
                                        creatorConfig,
                                        contextConfig.getLanguageOptionValues(language).copy(),
                                        contextConfig.getApplicationArguments(language));
                        Lazy localLazy = new Lazy(languageInstance, contextConfig);

                        if (layer.isSingleContext()) {
                            languageInstance.singleLanguageContext.update(this);
                        } else {
                            languageInstance.singleLanguageContext.invalidate();
                        }

                        checkThreadAccess(localEnv);

                        // no more errors after this line
                        creatingThread = Thread.currentThread();
                        env = localEnv;
                        lazy = localLazy;
                        assert EngineAccessor.LANGUAGE.getLanguage(env) != null;

                        try {
                            List<Object> languageServicesCollector = new ArrayList<>();
                            Object contextImpl = LANGUAGE.createEnvContext(localEnv, languageServicesCollector);
                            language.initializeContextClass(contextImpl);
                            String errorMessage = verifyServices(language.info, languageServicesCollector, language.cache.getServices());
                            if (errorMessage != null) {
                                throw PolyglotEngineException.illegalState(errorMessage);
                            }
                            PolyglotFastThreadLocals.notifyLanguageCreated(this);
                            this.languageServices = languageServicesCollector;
                            if (language.isHost()) {
                                context.initializeHostContext(this, context.config);
                            }
                            wasCreated = true;
                            if (eventsEnabled) {
                                EngineAccessor.INSTRUMENT.notifyLanguageContextCreated(context.engine, context.creatorTruffleContext, language.info);
                            }
                            context.invokeContextLocalsFactory(context.contextLocals, languageInstance.contextLocalLocations);
                            context.invokeContextThreadLocalFactory(languageInstance.contextThreadLocalLocations);

                            languageInstance = null; // commit language use
                        } catch (Throwable e) {
                            env = null;
                            lazy = null;
                            throw e;
                        } finally {
                            creatingThread = null;
                        }
                        created = true;
                    } finally {
                        if (!wasCreated && eventsEnabled) {
                            EngineAccessor.INSTRUMENT.notifyLanguageContextCreateFailed(context.engine, context.creatorTruffleContext, language.info);
                        }
                    }
                }
            }
        }
    }

    void close() {
        assert Thread.holdsLock(context);
        created = false;
        lazy = null;
        env = null;
    }

    private static String verifyServices(LanguageInfo info, List<Object> registeredServices, Collection<String> expectedServices) {
        for (String expectedService : expectedServices) {
            boolean found = false;
            for (Object registeredService : registeredServices) {
                if (isSubType(registeredService.getClass(), expectedService)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return String.format("Language %s declares service %s but doesn't register it", info.getName(), expectedService);
            }
        }
        return null;
    }

    private static boolean isSubType(Class<?> clazz, String serviceClass) {
        if (clazz == null) {
            return false;
        }
        if (serviceClass.equals(clazz.getName()) || serviceClass.equals(clazz.getCanonicalName())) {
            return true;
        }
        if (isSubType(clazz.getSuperclass(), serviceClass)) {
            return true;
        }
        for (Class<?> implementedInterface : clazz.getInterfaces()) {
            if (isSubType(implementedInterface, serviceClass)) {
                return true;
            }
        }
        return false;
    }

    boolean ensureInitialized(PolyglotLanguage accessingLanguage) {
        ensureCreated(accessingLanguage);
        if (initialized) {
            // fast-path exit
            return false;
        }

        boolean initialize = false;
        ReentrantLock lock = lazy.operationLock;
        lock.lock();
        try {
            initialize = !initialized;
            if (initialize) {
                if (eventsEnabled) {
                    EngineAccessor.INSTRUMENT.notifyLanguageContextInitialize(context.engine, context.creatorTruffleContext, language.info);
                }

                initialized = true; // Allow language use during initialization
                try {
                    LANGUAGE.initializeThread(env, Thread.currentThread());
                    LANGUAGE.postInitEnv(env);

                } catch (Throwable e) {
                    // language not successfully initialized, reset to avoid inconsistent
                    // language contexts
                    initialized = false;
                    try {
                        if (eventsEnabled) {
                            EngineAccessor.INSTRUMENT.notifyLanguageContextInitializeFailed(context.engine, context.creatorTruffleContext, language.info);
                        }
                    } catch (Throwable inner) {
                        e.addSuppressed(inner);
                    }
                    throw e;
                }

                if (eventsEnabled) {
                    EngineAccessor.INSTRUMENT.notifyLanguageContextInitialized(context.engine, context.creatorTruffleContext, language.info);
                }
            }
        } finally {
            lock.unlock();
        }

        if (initialize) {
            synchronized (context) {
                ensureMultiThreadingInitialized();
                for (PolyglotThreadInfo threadInfo : context.getSeenThreads().values()) {
                    final Thread thread = threadInfo.getThread();
                    if (thread == Thread.currentThread()) {
                        continue;
                    }
                    LANGUAGE.initializeThread(env, thread);
                }
            }
        }

        return initialize;

    }

    void ensureMultiThreadingInitialized() {
        assert Thread.holdsLock(context);
        Lazy l = this.lazy;
        assert l != null;

        if (!l.multipleThreadsInitialized && !context.isSingleThreaded()) {
            LANGUAGE.initializeMultiThreading(env);
            l.multipleThreadsInitialized = true;
        }
    }

    void checkAccess(PolyglotLanguage accessingLanguage) {
        // Always check context first, as it might be invalidated.
        context.checkClosed();
        if (context.disposing) {
            throw PolyglotEngineException.illegalState("The Context is already closed.");
        }
        if (!context.config.isAccessPermitted(accessingLanguage, language)) {
            throw PolyglotEngineException.illegalArgument(String.format("Access to language '%s' is not permitted. ", language.getId()));
        }
        RuntimeException initError = language.initError;
        if (initError != null) {
            throw PolyglotEngineException.illegalState(String.format("Initialization error: %s", initError.getMessage(), initError));
        }
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return context.getEngine();
    }

    boolean patch(PolyglotContextConfig newConfig) {
        if (isCreated()) {
            try {
                OptionValuesImpl newOptionValues = newConfig.getLanguageOptionValues(language).copy();
                lazy.computeAccessPermissions(newConfig);
                Env newEnv = LANGUAGE.patchEnvContext(env, newConfig.out, newConfig.err, newConfig.in,
                                Collections.emptyMap(), newOptionValues, newConfig.getApplicationArguments(language));
                if (newEnv != null) {
                    env = newEnv;
                    if (!this.language.isHost()) {
                        LOG.log(Level.FINE, "Successfully patched context of language: {0}", this.language.getId());
                    }
                    return true;
                }
                LOG.log(Level.FINE, "Failed to patch context of language: {0}", this.language.getId());
                return false;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Exception during patching context of language: {0}", this.language.getId());
                // The conversion to the host exception happens in the
                // PolyglotEngineImpl.createContext
                throw silenceException(RuntimeException.class, t);
            }
        } else {
            return true;
        }
    }

    @SuppressWarnings({"unchecked", "unused"})
    static <E extends Throwable> RuntimeException silenceException(Class<E> type, Throwable ex) throws E {
        throw (E) ex;
    }

    <S> S lookupService(Class<S> type) {
        for (Object languageService : languageServices) {
            if (type.isInstance(languageService)) {
                return type.cast(languageService);
            }
        }
        return null;
    }

    static final class Generic {
        private Generic() {
            throw shouldNotReachHere("no instances");
        }
    }

    @TruffleBoundary
    Value asValue(Object guestValue) {
        assert lazy != null;
        assert guestValue != null;
        assert !(guestValue instanceof Value);
        assert !(guestValue instanceof Proxy);
        PolyglotValueDispatch cache = getLanguageInstance().lookupValueCache(context, guestValue);
        return context.engine.getAPIAccess().newValue(cache, this, guestValue);
    }

    public Object toGuestValue(Node node, Object receiver) {
        return context.toGuestValue(node, receiver, false);
    }

    static final class ToHostValueNode {

        final APIAccess apiAccess;
        @CompilationFinal volatile Class<?> cachedClass;
        @CompilationFinal volatile PolyglotValueDispatch cachedValue;

        private ToHostValueNode(AbstractPolyglotImpl polyglot) {
            this.apiAccess = polyglot.getAPIAccess();
        }

        Value execute(PolyglotLanguageContext languageContext, Object value) {
            Object receiver = value;
            Class<?> cachedClassLocal = cachedClass;
            PolyglotValueDispatch cache;
            if (cachedClassLocal != Generic.class) {
                if (cachedClassLocal == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedClass = receiver.getClass();
                    cachedValue = cache = languageContext.lazy.languageInstance.lookupValueCache(languageContext.context, receiver);
                } else if (value.getClass() == cachedClassLocal) {
                    receiver = CompilerDirectives.inInterpreter() ? receiver : CompilerDirectives.castExact(receiver, cachedClassLocal);
                    cache = cachedValue;
                    if (cache == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        // invalid state retry next time for now do generic
                    } else {
                        return apiAccess.newValue(cache, languageContext, receiver);
                    }
                } else {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    cachedClass = Generic.class; // switch to generic
                    cachedValue = null;
                    // fall through to generic
                }
            }
            return languageContext.asValue(value);
        }

        public static ToHostValueNode create(AbstractPolyglotImpl polyglot) {
            return new ToHostValueNode(polyglot);
        }
    }

    @SuppressWarnings("serial")
    static final class ValueMigrationException extends AbstractTruffleException {

        ValueMigrationException(String message, Node location) {
            super(message, location);
        }

    }

    @TruffleBoundary
    Value[] toHostValues(Object[] values, int startIndex) {
        Value[] args = new Value[values.length - startIndex];
        for (int i = startIndex; i < values.length; i++) {
            args[i - startIndex] = asValue(values[i]);
        }
        return args;
    }

    @TruffleBoundary
    Value[] toHostValues(Object[] values) {
        Value[] args = new Value[values.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = asValue(values[i]);
        }
        return args;
    }

    @Override
    public String toString() {
        return "PolyglotLanguageContext [language=" + language + ", initialized=" + (env != null) + "]";
    }

    private class PolyglotUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            Env currentEnv = env;
            if (currentEnv != null && !(e instanceof ThreadDeath)) {
                try {
                    e.printStackTrace(new PrintStream(currentEnv.err()));
                } catch (Throwable exc) {
                    // Still show the original error if printing on Env.err() fails for some
                    // reason
                    e.printStackTrace();
                }
            } else {
                e.printStackTrace();
            }
        }
    }

    public Object getLanguageView(Object receiver) {
        EngineAccessor.INTEROP.checkInteropType(receiver);
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(receiver);
        if (lib.hasLanguage(receiver)) {
            try {
                if (!this.isCreated()) {
                    throw PolyglotEngineException.illegalState("Language not yet created. Initialize the language first to request a language view.");
                }
                if (lib.getLanguage(receiver) == this.lazy.languageInstance.spi.getClass()) {
                    return receiver;
                }
            } catch (UnsupportedMessageException e) {
                throw shouldNotReachHere(e);
            }
        }
        return getLanguageViewNoCheck(receiver);
    }

    private boolean validLanguageView(Object result) {
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(result);
        Class<?> languageClass = EngineAccessor.LANGUAGE.getLanguage(env).getClass();
        try {
            assert lib.hasLanguage(result) &&
                            lib.getLanguage(result) == languageClass : String.format("The returned language view of language '%s' must return the class '%s' for InteropLibrary.getLanguage." +
                                            "Fix the implementation of %s.getLanguageView to resolve this.", languageClass.getTypeName(), languageClass.getTypeName(), languageClass.getTypeName());
        } catch (UnsupportedMessageException e) {
            throw shouldNotReachHere(e);
        }
        return true;
    }

    private boolean validScopedView(Object result, Node location) {
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(result);
        Class<?> languageClass = EngineAccessor.LANGUAGE.getLanguage(env).getClass();
        try {
            assert lib.hasLanguage(result) &&
                            lib.getLanguage(result) == languageClass : String.format("The returned scoped view of language '%s' must return the class '%s' for InteropLibrary.getLanguage." +
                                            "Fix the implementation of %s.getView to resolve this.", languageClass.getTypeName(), languageClass.getTypeName(), location.getClass().getTypeName());
        } catch (UnsupportedMessageException e) {
            throw shouldNotReachHere(e);
        }
        return true;
    }

    public Object getLanguageViewNoCheck(Object receiver) {
        Object result = EngineAccessor.LANGUAGE.getLanguageView(env, receiver);
        assert validLanguageView(result);
        return result;
    }

    public Object getScopedView(Node location, Frame frame, Object value) {
        validateLocationAndFrame(language.info, location, frame);
        Object languageView = getLanguageView(value);
        Object result = NodeLibrary.getUncached().getView(location, frame, languageView);
        assert validScopedView(result, location);
        return result;
    }

    private static void validateLocationAndFrame(LanguageInfo viewLanguage, Node location, Frame frame) {
        RootNode rootNode = location.getRootNode();
        if (rootNode == null) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' does not have a RootNode.", location));
        }
        LanguageInfo nodeLocation = rootNode.getLanguageInfo();
        if (nodeLocation == null) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' does not have a language associated.", location));
        }
        if (nodeLocation != viewLanguage) {
            throw PolyglotEngineException.illegalArgument(String.format("The view language '%s' must match the language of the location %s.", viewLanguage, nodeLocation));
        }
        if (!EngineAccessor.INSTRUMENT.isInstrumentable(location)) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' is not instrumentable but must be to request scoped views.", location));
        }
        if (!rootNode.getFrameDescriptor().equals(frame.getFrameDescriptor())) {
            throw PolyglotEngineException.illegalArgument(String.format("The frame provided does not originate from the location. " +
                            "Expected frame descriptor '%s' but was '%s'.", rootNode.getFrameDescriptor(), frame.getFrameDescriptor()));
        }
    }

    @GenerateUncached
    abstract static class ToGuestValueNode extends Node {

        abstract Object execute(PolyglotLanguageContext context, Object receiver);

        @Specialization(guards = "receiver == null")
        Object doNull(PolyglotLanguageContext context, @SuppressWarnings("unused") Object receiver) {
            return context.toGuestValue(this, receiver);
        }

        @Specialization(guards = {"receiver != null", "receiver.getClass() == cachedReceiver"}, limit = "3")
        Object doCached(PolyglotLanguageContext context, Object receiver, @Cached("receiver.getClass()") Class<?> cachedReceiver) {
            return context.toGuestValue(this, cachedReceiver.cast(receiver));
        }

        @Specialization(replaces = "doCached")
        @TruffleBoundary
        Object doUncached(PolyglotLanguageContext context, Object receiver) {
            return context.toGuestValue(this, receiver);
        }
    }

    static final class ToGuestValuesNode extends Node {

        @Children private volatile ToGuestValueNode[] toGuestValue;
        @CompilationFinal private volatile boolean needsCopy = false;
        @CompilationFinal private volatile boolean generic = false;

        private ToGuestValuesNode() {
        }

        public Object[] apply(PolyglotLanguageContext context, Object[] args) {
            ToGuestValueNode[] nodes = this.toGuestValue;
            if (nodes == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                nodes = new ToGuestValueNode[args.length];
                for (int i = 0; i < nodes.length; i++) {
                    nodes[i] = insert(PolyglotLanguageContextFactory.ToGuestValueNodeGen.create());
                }
                toGuestValue = nodes;
            }
            if (args.length == nodes.length) {
                // fast path
                if (nodes.length == 0) {
                    return args;
                } else {
                    Object[] newArgs = fastToGuestValuesUnroll(nodes, context, args);
                    return newArgs;
                }
            } else {
                if (!generic || nodes.length != 1) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    nodes = Arrays.copyOf(nodes, 1);
                    if (nodes[0] == null) {
                        nodes[0] = insert(PolyglotLanguageContextFactory.ToGuestValueNodeGen.create());
                    }
                    this.toGuestValue = nodes;
                    this.generic = true;
                }
                if (args.length == 0) {
                    return args;
                }
                return fastToGuestValues(nodes[0], context, args);
            }
        }

        /*
         * Specialization for constant number of arguments. Uses a profile for each argument.
         */
        @ExplodeLoop
        private Object[] fastToGuestValuesUnroll(ToGuestValueNode[] nodes, PolyglotLanguageContext context, Object[] args) {
            Object[] newArgs = needsCopy ? new Object[nodes.length] : args;
            for (int i = 0; i < nodes.length; i++) {
                Object arg = args[i];
                Object newArg = nodes[i].execute(context, arg);
                if (needsCopy) {
                    newArgs[i] = newArg;
                } else if (arg != newArg) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    newArgs = new Object[nodes.length];
                    System.arraycopy(args, 0, newArgs, 0, args.length);
                    newArgs[i] = newArg;
                    needsCopy = true;
                }
            }
            return newArgs;
        }

        /*
         * Specialization that supports multiple argument lengths but uses a single profile for all
         * arguments.
         */
        private Object[] fastToGuestValues(ToGuestValueNode node, PolyglotLanguageContext context, Object[] args) {
            assert toGuestValue[0] != null;
            Object[] newArgs = needsCopy ? new Object[args.length] : args;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                Object newArg = node.execute(context, arg);
                if (needsCopy) {
                    newArgs[i] = newArg;
                } else if (arg != newArg) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    newArgs = new Object[args.length];
                    System.arraycopy(args, 0, newArgs, 0, args.length);
                    newArgs[i] = newArg;
                    needsCopy = true;
                }
            }
            return newArgs;
        }

        public static ToGuestValuesNode create() {
            return new ToGuestValuesNode();
        }

    }

    void patchInstance(PolyglotLanguageInstance hostInstance) {
        if (lazy != null) {
            lazy.languageInstance = hostInstance;
        }
    }

}
