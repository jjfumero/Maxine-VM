/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.max.vm.cps.cir.transform;

import static com.sun.max.collect.SequenceBag.MapType.*;

import java.util.*;

import com.sun.max.collect.*;
import com.sun.max.lang.Arrays;
import com.sun.max.vm.cps.cir.*;
import com.sun.max.vm.cps.cir.variable.*;

/**
 * Beta reduction: substitution of formal parameters by actual parameters (arguments).
 *
 * Example: {lambda[x y] . f(x y 3)}(1 2)  -->  f(1 2 3)
 *
 * We assume that alpha conversion has already happened before using this class.
 *
 * This one transformation gets a lot of otherwise separately implemented optimizations done in one big swoop.
 *
 * @author Bernd Mathiske
 */
public abstract class CirBetaReduction {

    protected abstract CirNode transformVariable(CirVariable variable);

    static final class Single extends CirBetaReduction {
        private final CirVariable parameter;
        private final CirValue argument;

        Single(CirVariable parameter, CirValue argument) {
            this.parameter = parameter;
            this.argument = argument;
        }

        @Override
        protected CirNode transformVariable(CirVariable variable) {
            if (variable == parameter) {
                return argument;
            }
            return variable;
        }
    }

    static final class Multiple extends CirBetaReduction {
        private final CirVariable[] parameters;
        private final CirValue[] arguments;

        Multiple(CirVariable[] parameters, CirValue[] arguments) {
            assert parameters.length == arguments.length || parameters.length == arguments.length + 2 :  parameters.length + "," + arguments.length;
            this.parameters = Arrays.subArray(parameters, 0, arguments.length);
            this.arguments = arguments;
        }

        private final Bag<CirContinuation, CirContinuation, Sequence<CirContinuation>> continuationsBag = new SequenceBag<CirContinuation, CirContinuation>(HASHED);

        private void updateContinuations() {
            for (CirContinuation oldContinuation : continuationsBag.keys()) {
                final Sequence<CirContinuation> newContinuations = continuationsBag.get(oldContinuation);
                if (newContinuations.length() > 1) {
                    final CirBlock block = new CirBlock(oldContinuation.body());
                    CirFreeVariableSearch.applyClosureConversion(block.closure());
                    for (CirContinuation newContinuation : newContinuations) {
                        final CirVariable[] parameters = block.closure().parameters();
                        final CirValue[] arguments = parameters.length > 0 ? Arrays.from(CirValue.class, parameters) : CirCall.NO_ARGUMENTS;
                        final CirCall newBlockCall = new CirCall(block, arguments);
                        newContinuation.setBody(newBlockCall);
                    }
                }
            }
        }

        private CirContinuation gatherContinuation(CirContinuation oldContinuation) {
            final CirContinuation newContinuation = (CirContinuation) oldContinuation.clone();
            final CirVariable[] parameters = oldContinuation.parameters();
            newContinuation.setParameters(parameters.length > 0 ? parameters.clone() : CirClosure.NO_PARAMETERS);
            continuationsBag.add(oldContinuation, newContinuation);
            return newContinuation;
        }

        @Override
        protected CirNode transformVariable(CirVariable variable) {
            final int index = Arrays.find(parameters, variable);
            if (index >= 0) {
                if (arguments[index] instanceof CirContinuation) {
                    return gatherContinuation((CirContinuation) arguments[index]);
                }
                return arguments[index];
            }
            return variable;
        }
    }

    private void transformValues(CirValue[] values, LinkedList<CirNode> inspectionList) {
        for (int i = 0; i < values.length; i++) {
            final CirValue value = values[i];
            if (value != null) {
                if (value instanceof CirVariable) {
                    final CirNode newVariable = transformVariable((CirVariable) value);
                    if (newVariable != value) {
                        assert newVariable != null;
                        values[i] = (CirValue) newVariable;
                    }
                } else if (!(value instanceof CirConstant)) {
                    // Only CirProcedure needs further inspection.
                    inspectionList.add(value);
                }
            }
        }
    }

    protected void transform(CirNode node) {
        final LinkedList<CirNode> inspectionList = new LinkedList<CirNode>();
        CirNode currentNode = node;
        while (true) {
            if (currentNode instanceof CirCall) {
                final CirCall call = (CirCall) currentNode;

                final CirNode procedure = call.procedure();
                if (procedure instanceof CirVariable) {
                    final CirNode newProcedure = transformVariable((CirVariable) procedure);
                    if (newProcedure != procedure) {
                        call.setProcedure((CirValue) newProcedure);
                        call.clearJavaFrameDescriptorIfNotNeeded();
                    }
                } else {
                    inspectionList.add(procedure);
                }

                transformValues(call.arguments(), inspectionList);

                CirJavaFrameDescriptor javaFrameDescriptor = call.javaFrameDescriptor();
                while (javaFrameDescriptor != null) {
                    transformValues(javaFrameDescriptor.locals, inspectionList);
                    transformValues(javaFrameDescriptor.stackSlots, inspectionList);
                    javaFrameDescriptor = javaFrameDescriptor.parent();
                }
            } else if (currentNode instanceof CirClosure) {
                final CirClosure closure = (CirClosure) currentNode;
                currentNode = closure.body();
                continue;
            }
            if (inspectionList.isEmpty()) {
                return;
            }
            currentNode = inspectionList.removeFirst();
        }
    }

    /**
     * Reduces all arguments of the given closure.
     * If a continuation argument is placed multiple times into the block body,
     * it is wrapped into a block.
     */
    public static CirCall applyMultiple(CirClosure closure, CirValue... arguments) {
        final Multiple betaReduction = new Multiple(closure.parameters(), arguments);
        final CirCall call = closure.body();
        betaReduction.transform(call);
        betaReduction.updateContinuations();
        return call;
    }

    /**
     * Reduces a single argument, which must not be a continuation (or any sort of closure).
     */
    public static void applySingle(CirClosure closure, CirVariable parameter, CirValue argument) {
        final CirBetaReduction betaReduction = new Single(parameter, argument);
        betaReduction.transform(closure.body());
    }
}