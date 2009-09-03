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
package com.sun.c1x.xir;

import java.util.ArrayList;
import java.util.List;

import com.sun.c1x.ci.CiKind;

/**
 * @author Thomas Wuerthinger
 * @author Ben L. Titzer
 */
public class XirAssembler {

    private final XirParameter resultOperand;
    private final XirParameter nullOperand;

    private final List<XirInstruction> instructions = new ArrayList<XirInstruction>();
    private final List<XirLabel> labels = new ArrayList<XirLabel>();
    private final List<XirParameter> parameters = new ArrayList<XirParameter>();

    public class XirLabel {
        public final int index;

        private XirLabel(int index) {
            this.index = index;
        }
    }

    public class XirParameter {

        public final CiKind kind;
        public final boolean mustBeConstant;
        public final int index;

        XirParameter(CiKind kind, boolean mustBeConstant, int index) {
            this.kind = kind;
            this.mustBeConstant = mustBeConstant;
            this.index = index;
        }

    }

    public XirAssembler(CiKind kind) {
        nullOperand = createParameter(CiKind.Illegal, true);
        resultOperand = createParameter(kind, false);
    }

    private XirParameter createParameter(CiKind kind, boolean isConstant) {
        final XirParameter result = new XirParameter(kind, isConstant, parameters.size());
        parameters.add(result);
        return result;
    }

    public class XirInstruction {
        public final CiKind kind;
        public final OperatorKind operator;
        public final XirParameter result;
        public final XirParameter a;
        public final XirParameter b;
        public final XirParameter c;
        public final XirLabel destination;

        public XirInstruction(CiKind kind, OperatorKind operator, XirParameter result, XirParameter... arguments) {
            this(kind, operator, null, result, arguments);
        }

        public XirInstruction(CiKind kind, OperatorKind operator, XirLabel destination, XirParameter result, XirParameter... arguments) {
            this.destination = destination;
            this.kind = kind;
            this.operator = operator;
            this.result = result;

            if (arguments.length > 0) {
                a = arguments[0];
            } else {
                a = null;
            }

            if (arguments.length > 1) {
                b = arguments[1];
            } else {
                b = null;
            }

            if (arguments.length > 2) {
                c = arguments[2];
            } else {
                c = null;
            }
        }
    }

    public enum OperatorKind {
        Add, Sub, Div, Mul, Mod, Shl, Shr, And, Or, Xor, Pload, Pstore, PloadDisp, PstoreDisp, Pcas, Call, Jmp, Jeq, Jneq, Jgt, Jgteq, Jlt, Jlteq, Bind
    }

    private void op2(OperatorKind op, XirParameter result, XirParameter a, XirParameter b) {
        append(new XirInstruction(result.kind, op, result, a, b));
    }

    private void append(XirInstruction xirInstruction) {
        instructions.add(xirInstruction);
    }

    public void add(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Add, result, a, b);
    }

    public XirLabel createInlineLabel() {
        final XirLabel result = new XirLabel(this.labels.size());
        labels.add(result);
        return result;
    }

    public XirLabel createOutOfLineLabel() {
        return null;
    }

    public void sub(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Sub, result, a, b);
    }

    public void div(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Div, result, a, b);
    }

    public void mul(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Mul, result, a, b);
    }

    public void mod(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Mod, result, a, b);
    }

    public void shl(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Shl, result, a, b);
    }

    public void shr(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Shr, result, a, b);
    }

    public void and(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.And, result, a, b);
    }

    public void or(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Or, result, a, b);
    }

    public void xor(XirParameter result, XirParameter a, XirParameter b) {
        op2(OperatorKind.Xor, result, a, b);
    }

    public void pload(CiKind kind, XirParameter result, XirParameter pointer) {
        append(new XirInstruction(kind, OperatorKind.Pload, result, pointer));
    }

    public void pstore(CiKind kind, XirParameter pointer, XirParameter value) {
        append(new XirInstruction(kind, OperatorKind.Pstore, nullOperand, pointer, value));
    }

    public void pload(CiKind kind, XirParameter result, XirParameter pointer, XirParameter disp) {
        append(new XirInstruction(kind, OperatorKind.PloadDisp, result, pointer, disp));
    }

    public void pstore(CiKind kind, XirParameter pointer, XirParameter disp, XirParameter value) {
        append(new XirInstruction(kind, OperatorKind.PstoreDisp, nullOperand, pointer, disp, value));
    }

    public void pcas(CiKind kind, XirParameter result, XirParameter pointer, XirParameter value, XirParameter expectedValue) {
        append(new XirInstruction(kind, OperatorKind.Pload, result, pointer, value, expectedValue));
    }


    public void jmp(XirLabel l) {
        append(new XirInstruction(CiKind.Void, OperatorKind.Jmp, l, nullOperand));
    }

    public void jeq(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jeq, l, a, b);
    }

    private void jcc(OperatorKind op, XirLabel l, XirParameter a, XirParameter b) {
        append(new XirInstruction(CiKind.Void, op, l, nullOperand, a, b));
    }

    public void jneq(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jneq, l, a, b);
    }

    public void jgt(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jgt, l, a, b);
    }

    public void jgteq(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jgteq, l, a, b);
    }

    public void jlt(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jlt, l, a, b);
    }

    public void jlteq(XirLabel l, XirParameter a, XirParameter b) {
        jcc(OperatorKind.Jlteq, l, a, b);
    }

    public void bind(XirLabel l) {
        append(new XirInstruction(CiKind.Void, OperatorKind.Bind, l, nullOperand));
    }

    public void call(XirParameter result, XirParameter destination) {
        append(new XirInstruction(result.kind, OperatorKind.Call, nullOperand, destination));
    }

    public XirParameter createInputOperand(CiKind object, boolean constant) {
        XirParameter param = new XirParameter(object, constant, parameters.size());
        parameters.add(param);
        return param;
    }

    public XirParameter getResultOperand() {
        return resultOperand;
    }

    public XirTemplate finished() {
        return new XirTemplate(instructions.toArray(new XirInstruction[instructions.size()]), labels.toArray(new XirAssembler.XirLabel[labels.size()]), parameters.toArray(new XirParameter[parameters
                .size()]));
    }
}