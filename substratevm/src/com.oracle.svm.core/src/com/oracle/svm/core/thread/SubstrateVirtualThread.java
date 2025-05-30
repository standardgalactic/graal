/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.thread;

// Checkstyle: stop

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.graalvm.compiler.serviceprovider.GraalUnsafeAccess;
import org.graalvm.nativeimage.IsolateThread;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.ContinuationsSupported;
import com.oracle.svm.core.jdk.NotLoomJDK;
import com.oracle.svm.core.monitor.MonitorSupport;
import com.oracle.svm.core.stack.JavaFrameAnchor;
import com.oracle.svm.core.stack.JavaFrameAnchors;
import com.oracle.svm.core.util.VMError;

import sun.misc.Unsafe;
// Checkstyle: resume

// Checkstyle: allow synchronization

/**
 * Implementation of {@link Thread} that does not correspond to an {@linkplain IsolateThread OS
 * thread} and instead gets mounted (scheduled to run) on a <em>platform thread</em> (OS thread),
 * which, until when it is unmounted again, is called its <em>carrier thread</em>.
 *
 * This class is based on Project Loom's {@code java.lang.VirtualThread}.
 */
final class SubstrateVirtualThread extends Thread {
    private static final Unsafe U = GraalUnsafeAccess.getUnsafe();
    private static final ScheduledExecutorService UNPARKER = createDelayedTaskScheduler();

    private static final long STATE;
    private static final long PARK_PERMIT;
    private static final long TERMINATION;
    static {
        try {
            STATE = U.objectFieldOffset(SubstrateVirtualThread.class.getDeclaredField("state"));
            PARK_PERMIT = U.objectFieldOffset(SubstrateVirtualThread.class.getDeclaredField("parkPermit"));
            TERMINATION = U.objectFieldOffset(SubstrateVirtualThread.class.getDeclaredField("termination"));
        } catch (ReflectiveOperationException e) {
            throw VMError.shouldNotReachHere(e);
        }
    }

    // scheduler and continuation
    private final Executor scheduler;
    private final Continuation cont;
    private final Runnable runContinuation;

    private volatile int state;

    private static final int NEW = 0;
    private static final int STARTED = 1;
    private static final int RUNNABLE = 2;     // runnable-unmounted
    private static final int RUNNING = 3;      // runnable-mounted
    private static final int PARKING = 4;
    private static final int PARKED = 5;       // unmounted
    private static final int PINNED = 6;       // mounted
    private static final int YIELDING = 7;     // Thread.yield
    private static final int TERMINATED = 99;  // final state

    // can be suspended from scheduling when unmounted
    private static final int SUSPENDED = 1 << 8;
    private static final int RUNNABLE_SUSPENDED = (RUNNABLE | SUSPENDED);
    private static final int PARKED_SUSPENDED = (PARKED | SUSPENDED);

    // parking permit, may eventually be merged into state
    private volatile int parkPermit;

    // carrier thread when mounted
    private volatile Thread carrierThread;

    /**
     * Number of active {@linkplain #pin() pinnings} of this virtual thread to its current carrier
     * thread, which prevent it from yielding and therefore unmounting. This is typically needed
     * when using or acquiring resources that are associated with the carrier thread.
     */
    private short pins;

    // termination object when joining, created lazily if needed
    private volatile CountDownLatch termination;

    SubstrateVirtualThread(Executor scheduler, Runnable task) {
        super(task);
        if (scheduler == null) {
            Thread parent = Thread.currentThread();
            if (parent instanceof SubstrateVirtualThread) {
                this.scheduler = ((SubstrateVirtualThread) parent).scheduler;
            } else {
                this.scheduler = SubstrateVirtualThreads.SCHEDULER;
            }
        } else {
            this.scheduler = scheduler;
        }
        this.cont = new Continuation(() -> run(task));
        this.runContinuation = this::runContinuation;
    }

    private void runContinuation() {
        if (Thread.currentThread() instanceof SubstrateVirtualThread) {
            throw new RuntimeException("Virtual thread was scheduled on another virtual thread");
        }

        int initialState = state();
        if (initialState == STARTED && compareAndSetState(STARTED, RUNNING)) {
            // first run
        } else if (initialState == RUNNABLE && compareAndSetState(RUNNABLE, RUNNING)) {
            setParkPermit(false); // consume parking permit
        } else {
            return; // not runnable
        }

        try {
            cont.enter();
        } finally {
            if (cont.isDone()) {
                afterTerminate();
            } else {
                afterYield();
            }
        }
    }

    private void submitRunContinuation() {
        scheduler.execute(runContinuation);
    }

    private void run(Runnable task) {
        assert state == RUNNING;

        mount();
        try {
            task.run();
        } catch (Throwable exc) {
            dispatchUncaughtException(exc);
        } finally {
            unmount();
            setState(TERMINATED);
        }
    }

    private void mount() {
        Thread carrier = PlatformThreads.currentThread.get();
        this.carrierThread = carrier; // can be a weaker write with release semantics

        if (JavaThreads.isInterrupted(this)) {
            PlatformThreads.setInterrupt(carrier);
        } else if (JavaThreads.isInterrupted(carrier)) {
            Object token = switchToCarrierAndAcquireInterruptLock();
            try {
                if (!JavaThreads.isInterrupted(this)) {
                    JavaThreads.getAndClearInterruptedFlag(carrier);
                }
            } finally {
                releaseInterruptLockAndSwitchBack(token);
            }
        }

        PlatformThreads.setCurrentThread(carrier, this);
    }

    private void unmount() {
        Thread carrier = this.carrierThread;
        PlatformThreads.setCurrentThread(carrier, carrier);

        // break connection to carrier thread
        synchronized (interruptLock()) { // synchronize with interrupt
            this.carrierThread = null; // can be a weaker write with release semantics
        }
        JavaThreads.getAndClearInterruptedFlag(carrier);
    }

    private boolean yieldContinuation() {
        assert this == Thread.currentThread();
        if (pins > 0) {
            return false;
        }
        JavaFrameAnchor anchor = JavaFrameAnchors.getFrameAnchor();
        if (anchor.isNonNull() && cont.sp.aboveThan(anchor.getLastJavaSP())) {
            return false;
        }

        unmount();
        try {
            return cont.yield() == Continuation.YIELD_SUCCESS;
        } finally {
            mount();
        }
    }

    private void afterYield() {
        int s = state();
        assert (s == PARKING || s == YIELDING) && (carrierThread == null);

        if (s == PARKING) {
            setState(PARKED);

            // may have been unparked while parking
            if (parkPermit() && compareAndSetState(PARKED, RUNNABLE)) {
                submitRunContinuation();
            }
        } else if (s == YIELDING) {   // Thread.yield
            setState(RUNNABLE);
            submitRunContinuation();
        }
    }

    private void afterTerminate() {
        assert (state() == TERMINATED) && (carrierThread == null);

        // notify anyone waiting for this virtual thread to terminate
        @SuppressWarnings("hiding")
        CountDownLatch termination = this.termination;
        if (termination != null) {
            assert termination.getCount() == 1;
            termination.countDown();
        }
    }

    private void parkOnCarrierThread(boolean timed, long nanos) {
        assert state() == PARKING;

        setState(PINNED);
        try {
            if (!parkPermit()) {
                if (!timed) {
                    U.park(false, 0);
                } else if (nanos > 0) {
                    U.park(false, nanos);
                }
            }
        } finally {
            setState(RUNNING);
        }
        setParkPermit(false); // consume
    }

    @Override
    @SuppressWarnings("sync-override")
    public void start() {
        if (!compareAndSetState(NEW, STARTED)) {
            throw new IllegalThreadStateException("Already started");
        }

        boolean started = false;
        try {
            submitRunContinuation();
            started = true;
        } finally {
            if (!started) {
                setState(TERMINATED);
                afterTerminate();
            }
        }
    }

    void park() {
        assert Thread.currentThread() == this;

        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || isInterrupted()) {
            return;
        }

        // park the thread
        setState(PARKING);
        try {
            if (!yieldContinuation()) { // pinned
                parkOnCarrierThread(false, 0);
            }
        } finally {
            assert Thread.currentThread() == this && state() == RUNNING;
        }
    }

    void parkNanos(long nanos) {
        assert Thread.currentThread() == this;

        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || isInterrupted()) {
            return;
        }

        if (nanos > 0) {
            long startTime = System.nanoTime();

            boolean yielded;
            Future<?> unparker = scheduleUnpark(nanos);
            setState(PARKING);
            try {
                yielded = yieldContinuation();
            } finally {
                assert (Thread.currentThread() == this) && (state() == RUNNING || state() == PARKING);
                cancel(unparker);
            }

            // park on the carrier thread for remaining time when pinned
            if (!yielded) {
                long deadline = startTime + nanos;
                if (deadline < 0L) {
                    deadline = Long.MAX_VALUE;
                }
                parkOnCarrierThread(true, deadline - System.nanoTime());
            }
        }
    }

    void parkUntil(long deadline) {
        long millis = deadline - System.currentTimeMillis();
        long nanos = TimeUnit.NANOSECONDS.convert(millis, TimeUnit.MILLISECONDS);
        parkNanos(nanos);
    }

    private Future<?> scheduleUnpark(long nanos) {
        Thread carrier = this.carrierThread;
        // need to switch to carrier thread to avoid nested parking
        PlatformThreads.setCurrentThread(carrier, carrier);
        try {
            return UNPARKER.schedule(this::unpark, nanos, NANOSECONDS);
        } finally {
            PlatformThreads.setCurrentThread(carrier, this);
        }
    }

    private void cancel(Future<?> future) {
        if (!future.isDone()) {
            Thread carrier = this.carrierThread;

            // need to switch to carrier thread to avoid nested parking
            PlatformThreads.setCurrentThread(carrier, carrier);
            try {
                future.cancel(false);
            } finally {
                PlatformThreads.setCurrentThread(carrier, this);
            }
        }
    }

    void unpark() {
        Thread currentThread = Thread.currentThread();
        if (!getAndSetParkPermit(true) && currentThread != this) {
            int s = state();
            if (s == PARKED && compareAndSetState(PARKED, RUNNABLE)) {
                if (currentThread instanceof SubstrateVirtualThread) {
                    SubstrateVirtualThread vthread = (SubstrateVirtualThread) currentThread;
                    Thread carrier = vthread.carrierThread;

                    PlatformThreads.setCurrentThread(carrier, carrier);
                    try {
                        submitRunContinuation();
                    } finally {
                        PlatformThreads.setCurrentThread(carrier, vthread);
                    }
                } else {
                    submitRunContinuation();
                }
            } else if (s == PINNED) {
                Object token = switchToCarrierAndAcquireInterruptLock();
                try {
                    Thread carrier = carrierThread;
                    if (carrier != null && state() == PINNED) {
                        U.unpark(carrier);
                    }
                } finally {
                    releaseInterruptLockAndSwitchBack(token);
                }
            }
        }
    }

    void tryYield() {
        assert Thread.currentThread() == this;
        setState(YIELDING);
        try {
            yieldContinuation();
        } finally {
            assert Thread.currentThread() == this;
            if (state() != RUNNING) {
                assert state() == YIELDING;
                setState(RUNNING);
            }
        }
    }

    boolean joinNanos(long nanos) throws InterruptedException {
        if (state() == TERMINATED) {
            return true;
        }

        // ensure termination object exists, then re-check state
        @SuppressWarnings("hiding")
        CountDownLatch termination = getTermination();
        if (state() == TERMINATED) {
            return true;
        }

        // wait for virtual thread to terminate
        if (nanos == 0) {
            termination.await();
        } else {
            boolean terminated = termination.await(nanos, NANOSECONDS);
            if (!terminated) {
                // waiting time elapsed
                return false;
            }
        }
        assert state() == TERMINATED;
        return true;
    }

    private Object interruptLock() {
        return JavaThreads.toTarget(this).blockerLock;
    }

    /** @see #releaseInterruptLockAndSwitchBack */
    private Object switchToCarrierAndAcquireInterruptLock() {
        Object token = null;
        Thread current = Thread.currentThread();
        if (current instanceof SubstrateVirtualThread) {
            SubstrateVirtualThread vthread = (SubstrateVirtualThread) current;
            /*
             * If we block on the interrupt lock in the virtual thread, we yield, for which we first
             * unmount. Unmounting also tries to acquire the interrupt lock and we might block
             * again, this time on the carrier thread. Then, the virtual thread cannot continue to
             * yield and execute on another thread later, and the carrier thread might not get
             * unparked, and both threads are stuck.
             */
            Thread carrier = vthread.carrierThread;
            PlatformThreads.setCurrentThread(carrier, carrier);
            token = vthread;
        }
        MonitorSupport.singleton().monitorEnter(interruptLock());
        return token;
    }

    /** @see #switchToCarrierAndAcquireInterruptLock */
    private void releaseInterruptLockAndSwitchBack(Object token) {
        MonitorSupport.singleton().monitorExit(interruptLock());
        if (token != null) {
            SubstrateVirtualThread vthread = (SubstrateVirtualThread) token;
            PlatformThreads.setCurrentThread(vthread.carrierThread, vthread);
        }
    }

    @Override
    public void interrupt() {
        if (Thread.currentThread() != this) {
            Object token = switchToCarrierAndAcquireInterruptLock();
            try {
                JavaThreads.writeInterruptedFlag(this, true);
                Target_sun_nio_ch_Interruptible b = JavaThreads.toTarget(this).blocker;
                if (b != null) {
                    b.interrupt(this);
                }
                Thread carrier = carrierThread;
                if (carrier != null) {
                    PlatformThreads.setInterrupt(carrier);
                }
            } finally {
                releaseInterruptLockAndSwitchBack(token);
            }
        } else {
            JavaThreads.writeInterruptedFlag(this, true);
            PlatformThreads.setInterrupt(carrierThread);
        }
        unpark();
    }

    boolean getAndClearInterrupt() {
        assert Thread.currentThread() == this;
        Object token = switchToCarrierAndAcquireInterruptLock();
        try {
            boolean oldValue = JavaThreads.getAndClearInterruptedFlag(this);
            JavaThreads.getAndClearInterruptedFlag(carrierThread);
            return oldValue;
        } finally {
            releaseInterruptLockAndSwitchBack(token);
        }
    }

    void sleepNanos(long nanos) throws InterruptedException {
        assert Thread.currentThread() == this;
        if (nanos >= 0) {
            if (JavaThreads.getAndClearInterrupt(this)) {
                throw new InterruptedException();
            }
            if (nanos == 0) {
                tryYield();
            } else {
                // park for the sleep time
                try {
                    long remainingNanos = nanos;
                    long startNanos = System.nanoTime();
                    while (remainingNanos > 0) {
                        parkNanos(remainingNanos);
                        if (JavaThreads.getAndClearInterrupt(this)) {
                            throw new InterruptedException();
                        }
                        remainingNanos = nanos - (System.nanoTime() - startNanos);
                    }
                } finally {
                    // may have been unparked while sleeping
                    setParkPermit(true);
                }
            }
        }
    }

    @Override
    public Thread.State getState() {
        switch (state()) {
            case NEW:
                return Thread.State.NEW;
            case STARTED:
            case RUNNABLE:
            case RUNNABLE_SUSPENDED:
                // runnable, not mounted
                return Thread.State.RUNNABLE;
            case RUNNING:
                // if mounted then return state of carrier thread
                Object token = switchToCarrierAndAcquireInterruptLock();
                try {
                    @SuppressWarnings("hiding")
                    Thread carrierThread = this.carrierThread;
                    if (carrierThread != null) {
                        return JavaThreads.getThreadState(carrierThread);
                    }
                } finally {
                    releaseInterruptLockAndSwitchBack(token);
                }
                // runnable, mounted
                return Thread.State.RUNNABLE;
            case PARKING:
            case YIELDING:
                // runnable, mounted, not yet waiting
                return Thread.State.RUNNABLE;
            case PARKED:
            case PARKED_SUSPENDED:
            case PINNED:
                return Thread.State.WAITING;
            case TERMINATED:
                return Thread.State.TERMINATED;
            default:
                throw new InternalError();
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VirtualThread[#");
        sb.append(getId());
        String name = getName();
        if (!name.isEmpty() && !name.equals("<unnamed>")) {
            sb.append(",");
            sb.append(name);
        }
        sb.append("]/");
        Thread carrier = carrierThread;
        if (carrier != null) {
            // include the carrier thread state and name when mounted
            Object token = switchToCarrierAndAcquireInterruptLock();
            try {
                carrier = carrierThread;
                if (carrier != null) {
                    String stateAsString = JavaThreads.getThreadState(carrier).toString();
                    sb.append(stateAsString.toLowerCase(Locale.ROOT));
                    sb.append('@');
                    sb.append(carrier.getName());
                }
            } finally {
                releaseInterruptLockAndSwitchBack(token);
            }
        }
        // include virtual thread state when not mounted
        if (carrier == null) {
            String stateAsString = getState().toString();
            sb.append(stateAsString.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return (int) getId();
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    private CountDownLatch getTermination() {
        @SuppressWarnings("hiding")
        CountDownLatch termination = this.termination;
        if (termination == null) {
            termination = new CountDownLatch(1);
            if (!U.compareAndSwapObject(this, TERMINATION, null, termination)) {
                termination = this.termination;
            }
        }
        return termination;
    }

    private int state() {
        return state;  // volatile read
    }

    private void setState(int newValue) {
        state = newValue;  // volatile write
    }

    private boolean compareAndSetState(int expectedValue, int newValue) {
        return U.compareAndSwapInt(this, STATE, expectedValue, newValue);
    }

    private boolean parkPermit() {
        return parkPermit != 0;
    }

    private void setParkPermit(boolean newValue) {
        int v = newValue ? 1 : 0;
        if (parkPermit != v) {
            parkPermit = v;
        }
    }

    private boolean getAndSetParkPermit(boolean newValue) {
        int v = newValue ? 1 : 0;
        if (parkPermit != v) {
            return U.getAndSetInt(this, PARK_PERMIT, v) != 0;
        } else {
            return newValue;
        }
    }

    private void dispatchUncaughtException(Throwable e) {
        getUncaughtExceptionHandler().uncaughtException(this, e);
    }

    void pin() {
        assert currentThread() == this;
        assert pins >= 0;
        if (pins == Short.MAX_VALUE) {
            throw new IllegalStateException("Too many pins");
        }
        pins++;
    }

    void unpin() {
        assert currentThread() == this;
        assert pins >= 0;
        if (pins == 0) {
            throw new IllegalStateException("Not pinned");
        }
        pins--;
    }

    @Override
    public void run() {
    }

    private static ScheduledExecutorService createDelayedTaskScheduler() {
        int poolSize = 1;
        ScheduledThreadPoolExecutor dts = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(poolSize,
                        task -> Target_jdk_internal_misc_InnocuousThread.newThread("VirtualThread-unparker", task));
        dts.setRemoveOnCancelPolicy(true);
        return dts;
    }
}

@TargetClass(className = "jdk.internal.misc.InnocuousThread", onlyWith = {ContinuationsSupported.class, NotLoomJDK.class})
final class Target_jdk_internal_misc_InnocuousThread {
    @Alias
    static native Thread newThread(String name, Runnable target);
}
