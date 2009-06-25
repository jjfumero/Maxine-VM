/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.compiler.c1x;

import java.util.*;

import com.sun.c1x.ci.*;
import com.sun.max.program.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 * The <code>MaxCiConstantPool</code> class implements a constant pool for
 * the compiler interface, including support for looking up constants during
 * compilation, as well as runtime support for resolving constants in
 * the compiled code.
 * <p>
 * Note that all methods that begin with "<code>resolve</code>" are runtime calls
 * that actually perform resolution, and therefore have side effects. Calls to
 * these methods are inserted into the compiled code for unresolved constant
 * pool constants by the compiler.
 * <p>
 * All methods that begin with "<code>lookup</code>" are side-effect free. They
 * will only look up constant pool constants that are already resolved. They
 * are used by the compiler in looking up constants during compilation.
 *
 * @author Ben L. Titzer
 */
public class MaxCiConstantPool implements CiConstantPool {
    final MaxCiRuntime _runtime;
    final ConstantPool _constantPool;
    final WeakHashMap<SignatureDescriptor, MaxCiSignature> _signatures = new WeakHashMap<SignatureDescriptor, MaxCiSignature>();

    /**
     * Creates a new constant pool inside of the specified runtime for the specified constant pool.
     * @param runtime the runtime implementation
     * @param constantPool the actual constant pool contents
     */
    MaxCiConstantPool(MaxCiRuntime runtime, ConstantPool constantPool) {
        _runtime = runtime;
        _constantPool = constantPool;
    }

    /**
     * Resolves a field reference for a getfield operation at runtime, and makes the
     * necessary runtime checks for getfield on the specified field.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the field reference
     * @return the compiler interface field resolved at that index
     */
    public CiField resolveGetField(char cpi) {
        return resolveField(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a field reference for a putfield operation at runtime, and makes the
     * necessary runtime checks for putfield on the specified field.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the field reference
     * @return the compiler interface field resolved at that index
     */
    public CiField resolvePutField(char cpi) {
        return resolveField(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a field reference for a getstatic operation at runtime, and makes the
     * necessary runtime checks for getstatic on the specified field.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the field reference
     * @return the compiler interface field resolved at that index
     */
    public CiField resolveGetStatic(char cpi) {
        return resolveField(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a field reference for a putstatic operation at runtime, and makes the
     * necessary runtime checks for putstatic on the specified field.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the field reference
     * @return the compiler interface field resolved at that index
     */
    public CiField resolvePutStatic(char cpi) {
        return resolveField(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a method reference for an invokevirtual at runtime, and makes the
     * necessary runtime checks for invokevirtual on the specified method.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the method reference
     * @return the compiler interface method resolved at that index
     */
    public CiMethod resolveInvokeVirtual(char cpi) {
        return resolveMethod(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a method reference for an invokespecial at runtime, and makes the
     * necessary runtime checks for invokespecial on the specified method.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the method reference
     * @return the compiler interface method resolved at that index
     */
    public CiMethod resolveInvokeSpecial(char cpi) {
        return resolveMethod(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a method reference for an invokeinterface at runtime, and makes the
     * necessary runtime checks for invokeinterface on the specified method.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the method reference
     * @return the compiler interface method resolved at that index
     */
    public CiMethod resolveInvokeInterface(char cpi) {
        return resolveMethod(cpi); // TODO: check for incompatible class changes
    }

    /**
     * Resolves a method reference for an invokestatic at runtime, and makes the
     * necessary runtime checks for invokestatic on the specified method.
     * (a call to this method is inserted into compiled code by the compiler)
     * @param cpi the constant pool index of the method reference
     * @return the compiler interface method resolved at that index
     */
    public CiMethod resolveInvokeStatic(char cpi) {
        return resolveMethod(cpi); // TODO: check for incompatible class changes
    }

    private MaxCiField resolveField(char cpi) {
        return canonicalCiField(_constantPool.fieldAt(cpi).resolve(_constantPool, cpi));
    }

    private MaxCiMethod resolveMethod(char cpi) {
        return canonicalCiMethod(_constantPool.methodAt(cpi).resolve(_constantPool, cpi));
    }

    /**
     * Resolves a type at runtime and makes the necessary access checks.
     * @param cpi the constant pool index of the type constant
     * @return the compiler interface type resolved at that index
     */
    public CiType resolveType(char cpi) {
        return canonicalCiType(_constantPool.classAt(cpi).resolve(_constantPool, cpi));
    }

    /**
     * Resolves a string constant at runtime.
     * @param cpi the constant pool index of the string constant
     * @return the string object resolved at that index
     */
    public String resolveString(char cpi) {
        return _constantPool.stringAt(cpi);
    }

    /**
     * Resolves a class constant at runtime and makes the necessary access checks.
     * @param cpi the constant pool index
     * @return the class object for the class at that index
     */
    public Class<?> resolveClass(char cpi) {
        return _constantPool.classAt(cpi).resolve(_constantPool, cpi).mirror();
    }

    /**
     * Looks up a type at the specified constant pool index, without performing
     * any resolution for unresolved types.
     * @param cpi the constant pool index
     * @return the compiler interface type at that index
     */
    public CiType lookupType(char cpi) {
        return typeFrom(_constantPool.classAt(cpi));
    }

    /**
     * Looks up a field at the specified index, without performing any resolution.
     * If the field is incompatible with the specified opcode (e.g. getfield of a
     * static field), it is expected that this method will return an unresolved
     * constant.
     * @param opcode the opcode for which the field is being resolved
     * @param cpi the constant pool index
     * @return the field at the specified index
     */
    public CiField lookupField(int opcode, char cpi) {
        return fieldFrom(_constantPool.fieldAt(cpi));
    }

    /**
     * Looks up a method at the specified index, without performing any resolution.
     * If the method is incompatible with the specified opcode (e.g. invokevirtual of a
     * static method), it is expected that this method will return an unresolved
     * constant.
     * @param opcode the opcode for which the method is being resolved
     * @param cpi the constant pool index
     * @return the method at the specified index
     */
    public CiMethod lookupMethod(int opcode, char cpi) {
        return methodFrom(_constantPool.methodAt(cpi));
    }

    public boolean willLinkField(int opcode, char cpi) {
        return true; // TODO: do incompatible class change checks
    }

    public boolean willLinkMethod(int opcode, char cpi) {
        return true; // TODO: do incompatible class change checks
    }

    /**
     * Looks up a constant at the specified index, without performing any resolution.
     * @param cpi the constant pool index
     * @return the compiler interface constant at that index
     */
    public CiConstant lookupConstant(char cpi) {
        switch (_constantPool.tagAt(cpi)) {
            case CLASS: {
                return new MaxCiConstant(typeFrom(_constantPool.classAt(cpi)));
            }
            case INTEGER: {
                return new MaxCiConstant(IntValue.from(_constantPool.intAt(cpi)));
            }
            case FLOAT: {
                return new MaxCiConstant(FloatValue.from(_constantPool.floatAt(cpi)));
            }
            case STRING: {
                return new MaxCiConstant(ReferenceValue.from(_constantPool.stringAt(cpi)));
            }
            case LONG: {
                return new MaxCiConstant(LongValue.from(_constantPool.longAt(cpi)));
            }
            case DOUBLE: {
                return new MaxCiConstant(DoubleValue.from(_constantPool.doubleAt(cpi)));
            }
            default:
                throw ProgramError.unexpected("unknown constant type");
        }
    }

    private MaxCiField fieldFrom(FieldRefConstant constant) {
        if (constant instanceof FieldRefConstant.Resolved) {
            return canonicalCiField(((FieldRefConstant.Resolved) constant).fieldActor());
        }
        return new MaxCiField(this, constant);
    }

    private MaxCiMethod methodFrom(MethodRefConstant constant) {
        if (constant instanceof ClassMethodRefConstant.Resolved) {
            return canonicalCiMethod(((ClassMethodRefConstant.Resolved) constant).methodActor());
        }
        if (constant instanceof InterfaceMethodRefConstant.Resolved) {
            return canonicalCiMethod(((InterfaceMethodRefConstant.Resolved) constant).methodActor());
        }
        // no canonicalization necessary for unresolved constants
        return new MaxCiMethod(this, constant);
    }

    private MaxCiType typeFrom(ClassConstant constant) {
        if (constant instanceof ClassConstant.Resolved) {
            return canonicalCiType(((ClassConstant.Resolved) constant).classActor());
        }
        // no canonicalization necessary for unresolved constants
        return new MaxCiType(this, constant);
    }

    /**
     * Canonicalizes resolved <code>MaxCiType</code> instances (per runtime), so
     * that the same <code>MaxCiType</code> instance is always returned for the
     * same <code>ClassActor</code>.
     * @param classActor the class actor for which to get the canonical type
     * @return the canonical compiler interface type for the class actor
     */
    public MaxCiType canonicalCiType(ClassActor classActor) {
        final MaxCiType type = new MaxCiType(this, classActor);
        synchronized (_runtime) {
            // all resolved types are canonicalized per runtime instance
            final MaxCiType previous = _runtime._types.get(type);
            if (previous == null) {
                _runtime._types.put(type, type);
                return type;
            }
            return previous;
        }
    }

    /**
     * Canonicalizes resolved <code>MaxCiMethod</code> instances (per runtime), so
     * that the same <code>MaxCiMethod</code> instance is always returned for the
     * same <code>MethodActor</code>.
     * @param methodActor the mehtod actor for which to get the canonical type
     * @return the canonical compiler interface method for the method actor
     */
    public MaxCiMethod canonicalCiMethod(MethodActor methodActor) {
        final MaxCiMethod method = new MaxCiMethod(this, methodActor);
        synchronized (_runtime) {
            // all resolved methods are canonicalized per runtime instance
            final MaxCiMethod previous = _runtime._methods.get(method);
            if (previous == null) {
                _runtime._methods.put(method, method);
                return method;
            }
            return previous;
        }
    }

    /**
     * Canonicalizes resolved <code>MaxCiFielde</code> instances (per runtime), so
     * that the same <code>MaxCiField</code> instance is always returned for the
     * same <code>FieldActor</code>.
     * @param fieldActor the field actor for which to get the canonical type
     * @return the canonical compiler interface field for the field actor
     */
    public MaxCiField canonicalCiField(FieldActor fieldActor) {
        final MaxCiField field = new MaxCiField(this, fieldActor);
        synchronized (_runtime) {
            // all resolved field are canonicalized per runtime instance
            final MaxCiField previous = _runtime._fields.get(field);
            if (previous == null) {
                _runtime._fields.put(field, field);
                return field;
            }
            return previous;
        }
    }

    /**
     * Caches the compiler interface signature objects (per constant pool), to
     * reduce the amount of decoding done for repeated uses of the same signature.
     * @param descriptor the signature descriptor.
     * @return the cached compiler interface signature object
     */
    public synchronized MaxCiSignature cacheSignature(SignatureDescriptor descriptor) {
        MaxCiSignature signature = _signatures.get(descriptor);
        if (signature == null) {
            signature = new MaxCiSignature(this, descriptor);
            _signatures.put(descriptor, signature);
        }
        return signature;
    }

    /**
     * Creates new a new compiler interface exception handler.
     * @param startBCI the start bytecode index of the protected range
     * @param endBCI the end bytecode index of the protected range
     * @param catchBCI the bytecode index of the handler block
     * @param classCPI the index into the constant pool for the catch class
     * @return a compiler interface exception handler object
     */
    public CiExceptionHandler newExceptionHandler(int startBCI, int endBCI, int catchBCI, int classCPI) {
        return new MaxCiExceptionHandler(startBCI, endBCI, catchBCI, classCPI);
    }
}