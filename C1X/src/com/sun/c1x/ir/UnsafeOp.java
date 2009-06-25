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

import com.sun.c1x.value.ValueType;
import com.sun.c1x.value.BasicType;

/**
 * The <code>UnsafeOp</code> class is the base of all unsafe operations.
 *
 * @author Ben L. Titzer
 */
public abstract class UnsafeOp extends Instruction {
    BasicType _basicType;

    /**
     * Creates a new UnsafeOp instruction.
     * @param basicType the basic type of the operation
     * @param isStore <code>true</code> if this is a store operation
     */
    public UnsafeOp(BasicType basicType, boolean isStore) {
        super(isStore ? ValueType.VOID_TYPE : ValueType.fromBasicType(basicType));
        _basicType = basicType;
        pin();
    }

    public BasicType basicType() {
        return _basicType;
    }
}