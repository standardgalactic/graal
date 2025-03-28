/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.espresso.impl.ClassRegistry;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.vm.VM;

public class EspressoThreadLocalState {
    private EspressoException pendingJniException;
    private final ClassRegistry.TypeStack typeStack;
    private final VM.PrivilegedStack privilegedStack;

    /**
     * This is declared as a compilation final for whenever Truffle is able to constant-fold
     * accesses to ContextThreadLocals (Which might be the case when running single-threaded, for
     * example).
     * <p>
     * Consistency is guaranteed because a call to {@link #setCurrentThread(StaticObject)} must have
     * been performed before execution of guest code on the current thread. Guest threads are
     * coupled with host threads on 4 occasions within Espresso:
     * <ul>
     * <li>Main thread creation.</li>
     * <li>Guest code creation of threads. The coupling is performed before execution of the guest
     * {@link Thread#run()} (see {@code GuestRunnable})</li>
     * <li>Attaching through JNI
     * {@link VM#AttachCurrentThread(TruffleObject, TruffleObject, TruffleObject)}.</li>
     * <li>Attaching through Truffle
     * {@code EspressoLanguage#initializeThread(EspressoContext, Thread)}.</li>
     * </ul>
     * For these two last cases, coupling is performed right as the supporting guest thread is
     * allocated (see
     * {@link com.oracle.truffle.espresso.threads.EspressoThreadRegistry#createGuestThreadFromHost(Thread, Meta, VM, String, StaticObject)}).
     */
    @CompilationFinal //
    private StaticObject currentThread;

    @SuppressWarnings("unused")
    public EspressoThreadLocalState(EspressoContext context) {
        typeStack = new ClassRegistry.TypeStack();
        privilegedStack = new VM.PrivilegedStack(context);
    }

    public StaticObject getPendingExceptionObject() {
        EspressoException espressoException = getPendingException();
        if (espressoException == null) {
            return null;
        }
        return espressoException.getGuestException();
    }

    public EspressoException getPendingException() {
        return pendingJniException;
    }

    public void setPendingException(EspressoException t) {
        // TODO(peterssen): Warn about overwritten pending exceptions.
        pendingJniException = t;
    }

    public void clearPendingException() {
        setPendingException(null);
    }

    public void setCurrentThread(StaticObject t) {
        assert currentThread == null || currentThread == t;
        assert t != null && StaticObject.notNull(t);
        assert t.getKlass().getContext().getThreadAccess().getHost(t) == Thread.currentThread() : "Current thread fast access set by non-current thread";
        currentThread = t;
    }

    public StaticObject getCurrentThread(EspressoContext context) {
        StaticObject result = currentThread;
        if (result == null) {
            // Failsafe, should not happen.
            CompilerDirectives.transferToInterpreterAndInvalidate();
            context.getLogger().warning("Uninitialized fast current thread lookup for " + Thread.currentThread());
            result = context.getGuestThreadFromHost(Thread.currentThread());
            if (result != null) {
                setCurrentThread(result);
            }
            return result;
        }
        return result;
    }

    public ClassRegistry.TypeStack getTypeStack() {
        return typeStack;
    }

    public VM.PrivilegedStack getPrivilegedStack() {
        return privilegedStack;
    }
}
