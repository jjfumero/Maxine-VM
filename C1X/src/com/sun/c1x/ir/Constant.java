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
package com.sun.c1x.ir;

import com.sun.c1x.value.*;
import com.sun.c1x.util.InstructionVisitor;
import com.sun.c1x.util.InstructionClosure;

/**
 * The <code>Constant</code> instruction represents a constant such as an integer value,
 * long, float, object reference, address, etc.
 *
 * @author Ben L. Titzer
 */
public class Constant extends Instruction {

    private ValueStack _state;

    /**
     * Constructs a new instruction representing the specified constant.
     * @param type the constant
     */
    public Constant(ConstType type) {
        super(type);
    }

    /**
     * Constructs a new instruction representing the specified constant, which
     * may be an unresolved class reference that needs to be resolved.
     * @param type the constant
     * @param state the state, needed for deopt/gc in resolution code
     */
    public Constant(ClassType type, ValueStack state) {
        super(type);
        _state = state;
    }

    /**
     * Gets the state at this constant. This is only non-null if this
     * constant may require patching.
     * @return the value stack at this constant
     */
    public ValueStack state() {
        return _state;
    }

    /**
     * Checks whether this instruction can trap (i.e. cause an exception).
     * For constants, this can only occur if it is a constant that needs patching,
     * such as a class constant that must be resolved.
     * @return <code>true</code> if this instruction can cause a trap
     */
    @Override
    public boolean canTrap() {
        return _state != null;
    }

    /**
     * Implements half of the visitor pattern for this instruction.
     * @param v the visitor to accept
     */
    @Override
    public void accept(InstructionVisitor v) {
        v.visitConstant(this);
    }

    /**
     * Compare this instruction to another instruction. This method only
     * returns true if this instruction and the other instruction are both
     * the same constant (regardless of where they are computed). Note that
     * this method is <b>not</b> the <code>equals()</code> method.
     * @param i the other instruction
     * @return <code>true</code> if this instruction and the other instruction are value-numbering equal.
     */
    public boolean isEqual(Instruction i) {
        if (i instanceof Constant) {
            Constant c = (Constant) i;
            // XXX: why isn't this the equals method?
            return c.type().asConstant().equivalent(type());
        }
        return false;
    }

    /**
     * Iterates over the "other" values in this instruction. In the case of constants,
     * this method iterates over any values in the state if this constant may need patching.
     * @param closure the closure to apply to each value
     */
    @Override
    public void otherValuesDo(InstructionClosure closure) {
        if (_state != null) {
            _state.valuesDo(closure);
        }
    }

    /**
     * Utility method to create an instruction for a double constant.
     * @param d the double value for which to create the instruction
     * @return an instruction representing the double
     */
    public static Constant forDouble(double d) {
        return new Constant(ConstType.forDouble(d));
    }

    /**
     * Utility method to create an instruction for a float constant.
     * @param f the float value for which to create the instruction
     * @return an instruction representing the float
     */
    public static Constant forFloat(float f) {
        return new Constant(ConstType.forFloat(f));
    }

    /**
     * Utility method to create an instruction for an long constant.
     * @param i the long value for which to create the instruction
     * @return an instruction representing the long
     */
    public static Constant forLong(long i) {
        return new Constant(ConstType.forLong(i));
    }

    /**
     * Utility method to create an instruction for an integer constant.
     * @param i the integer value for which to create the instruction
     * @return an instruction representing the integer
     */
    public static Constant forInt(int i) {
        return new Constant(ConstType.forInt(i));
    }

    /**
     * Utility method to create an instruction for a boolean constant.
     * @param i the boolean value for which to create the instruction
     * @return an instruction representing the boolean
     */
    public static Constant forBoolean(boolean i) {
        return new Constant(ConstType.forBoolean(i));
    }

    /**
     * Utility method to create an instruction for an address (jsr/ret address) constant.
     * @param i the address value for which to create the instruction
     * @return an instruction representing the address
     */
    public static Constant forJsr(int i) {
        return new Constant(ConstType.forJsr(i));
    }

    /**
     * Utility method to create an instruction for an object constant.
     * @param o the object value for which to create the instruction
     * @return an instruction representing the object
     */
    public static Constant forObject(Object o) {
        final Constant constant = new Constant(ConstType.forObject(o));
        if (o != null) {
            constant.setFlag(Instruction.Flag.NonNull);
        }
        return constant;
    }

    @Override
    public int valueNumber() {
        ValueType vt = type();
        return vt.isConstant() ? 0x50000000 | vt.hashCode() : 0;
    }

    @Override
    public boolean valueEqual(Instruction i) {
        // basic type comparison is all that's necessary for constants
        return i instanceof Constant && i.type().equals(type());
    }

}