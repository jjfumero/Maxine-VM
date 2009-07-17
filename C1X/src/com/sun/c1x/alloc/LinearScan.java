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
package com.sun.c1x.alloc;

import java.util.*;

import com.sun.c1x.*;
import com.sun.c1x.alloc.Interval.*;
import com.sun.c1x.bytecode.*;
import com.sun.c1x.gen.*;
import com.sun.c1x.graph.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.ir.BlockBegin.*;
import com.sun.c1x.lir.*;
import com.sun.c1x.target.*;
import com.sun.c1x.util.*;
import com.sun.c1x.value.*;

/**
 *
 * @author Thomas Wuerthinger
 *
 */
public class LinearScan extends RegisterAllocator {

    private int nofCpuRegs;
    private int vregBase;
    private Register[] registerMapping;
    int nofRegs;

    C1XCompilation compilation;
    IR ir;
    LIRGenerator gen;
    FrameMap frameMap;

    List<BlockBegin> cachedBlocks; // cached list with all blocks in linear-scan order (only correct if original list
    // keeps
    // unchanged)
    int numVirtualRegs; // number of virtual registers (without new registers introduced because of splitting intervals)
    boolean hasFpuRegisters; // true if this method uses any floating point registers (and so fpu stack allocation is
    // necessary)
    int numCalls; // total number of calls in this method
    int maxSpills; // number of stack slots used for intervals allocated to memory
    int unusedSpillSlot; // unused spill slot for a single-word value because of alignment of a double-word value

    List<Interval> intervals; // mapping from register number to interval
    List<Interval> newIntervalsFromAllocation; // list with all intervals created during allocation when an existing
    // interval is split
    Interval[] sortedIntervals; // intervals sorted by Interval.from()

    LIRInstruction[] lirOps; // mapping from LIRInstruction id to LIRInstruction node
    BlockBegin[] blockOfOp; // mapping from LIRInstruction id to the BlockBegin containing this instruction
    BitMap hasInfo; // bit set for each LIRInstruction id that has a CodeEmitInfo
    BitMap hasCall; // bit set for each LIRInstruction id that destroys all caller save registers
    BitMap2D intervalInLoop; // bit set for each virtual register that is contained in each loop

    // Implementation of LinearScan

    public LinearScan(C1XCompilation compilation, IR ir, LIRGenerator gen, FrameMap frameMap) {
        this.compilation = compilation;
        this.ir = ir;
        this.gen = gen;
        this.frameMap = frameMap;
        this.numVirtualRegs = gen.maxVirtualRegisterNumber();
        this.hasFpuRegisters = false;
        this.numCalls = -1;
        this.maxSpills = 0;
        this.unusedSpillSlot = -1;
        this.newIntervalsFromAllocation = new ArrayList<Interval>();
        this.cachedBlocks = new ArrayList<BlockBegin>(ir.linearScanOrder());

        initializeRegisters(compilation.runtime.getAllocatableRegisters());

        vregBase = nofRegs;

        // note: to use more than on instance of LinearScan at a time this function call has to
        // be moved somewhere outside of this constructor:
        // Interval.initialize();

        assert this.ir() != null : "check if valid";
        assert this.compilation() != null : "check if valid";
        assert this.gen() != null : "check if valid";
        assert this.frameMap() != null : "check if valid";
    }

    private void initializeRegisters(Register[] registers) {

        int cpuCnt = 0;
        int cpuFirst = Integer.MAX_VALUE;
        int cpuLast = Integer.MIN_VALUE;
        int byteCnt = 0;
        int byteFirst = Integer.MAX_VALUE;
        int byteLast = Integer.MIN_VALUE;
        int fpuCnt = 0;
        int fpuFirst = Integer.MAX_VALUE;
        int fpuLast = Integer.MIN_VALUE;
        int xmmCnt = 0;
        int xmmFirst = Integer.MAX_VALUE;
        int xmmLast = Integer.MIN_VALUE;

        for (Register r : registers) {

            if (r.isCpu()) {
                cpuCnt++;
                cpuFirst = Math.min(cpuFirst, r.number);
                cpuLast = Math.max(cpuLast, r.number);
            }

            if (r.isByte()) {
                byteCnt++;
                byteFirst = Math.min(byteFirst, r.number);
                byteLast = Math.max(byteLast, r.number);
            }

            if (r.isFpu()) {
                fpuCnt++;
                fpuFirst = Math.min(fpuFirst, r.number);
                fpuLast = Math.max(fpuLast, r.number);
            }

            if (r.isXmm()) {
                xmmCnt++;
                xmmFirst = Math.min(xmmFirst, r.number);
                xmmLast = Math.max(xmmLast, r.number);
            }
        }

        int maxReg = Math.max(fpuLast, Math.max(cpuLast, xmmLast));
        registerMapping = new Register[maxReg + 1];

        for (Register r : registers) {
            assert registerMapping[r.number] == null : "duplicate register!";
            registerMapping[r.number] = r;
        }

        pdFirstByteReg = byteFirst;
        pdLastByteReg = byteLast;

        pdFirstCpuReg = cpuFirst;
        pdLastCpuReg = cpuLast;

        pdFirstFpuReg = fpuFirst;
        pdLastFpuReg = fpuLast;

        pdFirstXmmReg = xmmFirst;
        pdLastXmmReg = xmmLast;


        nofCpuRegs = cpuCnt;

        nofRegs = registerMapping.length;

        if (C1XOptions.TraceLinearScanLevel >= 2) {
            TTY.println("Register set analyzed: nofRegs=%d cpuCnt=%d [%d/%d] byteCnt=%d [%d/%d] fpuCnt=%d [%d/%d] xmmCnt=%d [%d/%d]", nofRegs, cpuCnt, cpuFirst, cpuLast, byteCnt, byteFirst, byteLast,
                            fpuCnt, fpuFirst, fpuLast, xmmCnt, xmmFirst, xmmLast);
        }
    }

    // * functions for converting LIR-Operands to register numbers
    //
    // Emulate a flat register file comprising physical integer registers,
    // physical floating-point registers and virtual registers, in that order.
    // Virtual registers already have appropriate numbers, since V0 is
    // the number of physical registers.
    // Returns -1 for hi word if opr is a single word operand.
    //
    // Note: the inverse operation (calculating an operand for register numbers)
// is done in calcOperandForInterval()

    int regNum(LIROperand opr) {
        assert opr.isRegister() : "should not call this otherwise";

        if (opr.isVirtualRegister()) {
            assert opr.vregNumber() >= nofRegs : "found a virtual register with a fixed-register number";
            return opr.vregNumber();
        } else if (opr.isSingleCpu()) {
            return opr.cpuRegnr();
        } else if (opr.isDoubleCpu()) {
            return opr.cpuRegnrLo();
        } else if (opr.isSingleXmm()) {
            return opr.fpuRegnr();
        } else if (opr.isDoubleXmm()) {
            return opr.fpuRegnrLo();
        } else if (opr.isSingleFpu()) {
            return opr.fpuRegnr();
        } else if (opr.isDoubleFpu()) {
            return opr.fpuRegnrLo();
        } else {
            Util.shouldNotReachHere();
            return -1;
        }
    }

    int regNumHi(LIROperand opr) {
        assert opr.isRegister() : "should not call this otherwise";

        if (opr.isVirtualRegister()) {
            return -1;
        } else if (opr.isSingleCpu()) {
            return -1;
        } else if (opr.isDoubleCpu()) {
            return opr.cpuRegnrHi();
        } else if (opr.isSingleXmm()) {
            return -1;
        } else if (opr.isDoubleXmm()) {
            return -1;
        } else if (opr.isSingleFpu()) {
            return -1;
        } else if (opr.isDoubleFpu()) {
            return opr.fpuRegnrHi();
        } else {
            Util.shouldNotReachHere();
            return -1;
        }
    }

    // * functions for classification of intervals

    boolean isPrecoloredInterval(Interval i) {
        return i.regNum() < nofRegs;
    }

    IntervalClosure isPrecoloredCpuInterval = new IntervalClosure() {

        public boolean apply(Interval i) {
            return isCpu(i.regNum());
        }
    };

    IntervalClosure isVirtualCpuInterval = new IntervalClosure() {

        public boolean apply(Interval i) {
            return i.regNum() >= vregBase && (i.type() != BasicType.Float && i.type() != BasicType.Double);
        }
    };

    IntervalClosure isPrecoloredFpuInterval = new IntervalClosure() {

        public boolean apply(Interval i) {
            return isFpu(i.regNum());
        }
    };

    IntervalClosure isVirtualFpuInterval = new IntervalClosure() {

        public boolean apply(Interval i) {
            return i.regNum() >= vregBase && (i.type() == BasicType.Float || i.type() == BasicType.Double);
        }
    };

    IntervalClosure isOopInterval = new IntervalClosure() {

        public boolean apply(Interval i) {
            // fixed intervals never contain oops
            return i.regNum() >= nofRegs && i.type() == BasicType.Object;
        }
    };

    // * General helper functions

    // compute next unused stack index that can be used for spilling
    int allocateSpillSlot(boolean doubleWord) {
        int spillSlot;
        if (doubleWord) {
            if ((maxSpills & 1) == 1) {
                // alignment of double-word values
                // the hole because of the alignment is filled with the next single-word value
                assert unusedSpillSlot == -1 : "wasting a spill slot";
                unusedSpillSlot = maxSpills;
                maxSpills++;
            }
            spillSlot = maxSpills;
            maxSpills += 2;

        } else if (unusedSpillSlot != -1) {
            // re-use hole that was the result of a previous double-word alignment
            spillSlot = unusedSpillSlot;
            unusedSpillSlot = -1;

        } else {
            spillSlot = maxSpills;
            maxSpills++;
        }

        int result = spillSlot + nofRegs + frameMap().argcount();

        // the class OopMapValue uses only 11 bits for storing the name of the
        // oop location. So a stack slot bigger than 2^11 leads to an overflow
        // that is not reported in product builds. Prevent this by checking the
        // spill slot here (altough this value and the later used location name
        // are slightly different)
        if (result > 2000) {
            bailout("too many stack slots used");
        }

        return result;
    }

    void assignSpillSlot(Interval it) {
        // assign the canonical spill slot of the parent (if a part of the interval
        // is already spilled) or allocate a new spill slot
        if (it.canonicalSpillSlot() >= 0) {
            it.assignReg(it.canonicalSpillSlot());
        } else {
            int spill = allocateSpillSlot(numberOfSpillSlots(it.type()) == 2);
            it.setCanonicalSpillSlot(spill);
            it.assignReg(spill);
        }
    }

    void propagateSpillSlots() {
        if (!frameMap().finalizeFrame(maxSpills())) {
            throw new Bailout("frame too large");
        }
    }

    // create a new interval with a predefined regNum
    // (only used for parent intervals that are created during the building phase)
    Interval createInterval(int regNum) {
        assert intervals.get(regNum) == null : "overwriting exisiting interval";

        Interval interval = new Interval(regNum);
        intervals.set(regNum, interval);

        // assign register number for precolored intervals
        if (regNum < vregBase) {
            interval.assignReg(regNum);
        }
        return interval;
    }

    // assign a new regNum to the interval and append it to the list of intervals
    // (only used for child intervals that are created during register allocation)
    void appendInterval(Interval it) {
        it.setRegNum(intervals.size());
        intervals.add(it);
        newIntervalsFromAllocation.add(it);
    }

    // copy the vreg-flags if an interval is split
    void copyRegisterFlags(Interval from, Interval to) {
        if (gen().isVregFlagSet(from.regNum(), LIRGenerator.VregFlag.ByteReg)) {
            gen().setVregFlag(to.regNum(), LIRGenerator.VregFlag.ByteReg);
        }
        if (gen().isVregFlagSet(from.regNum(), LIRGenerator.VregFlag.CalleeSaved)) {
            gen().setVregFlag(to.regNum(), LIRGenerator.VregFlag.CalleeSaved);
        }

        // Note: do not copy the mustStartInMemory flag because it is not necessary for child
        // intervals (only the very beginning of the interval must be in memory)
    }

    // accessors
    IR ir() {
        return ir;
    }

    C1XCompilation compilation() {
        return compilation;
    }

    LIRGenerator gen() {
        return gen;
    }

    FrameMap frameMap() {
        return frameMap;
    }

    // unified bailout support
    void bailout(String msg) {
        throw new Bailout(msg);
    }

    // TODO: Inline
    boolean bailedOut() {
        return false;
    }

    // access to block list (sorted in linear scan order)
    int blockCount() {
        assert cachedBlocks.size() == ir().linearScanOrder().size() : "invalid cached block list";
        return cachedBlocks.size();
    }

    BlockBegin blockAt(int idx) {
        assert cachedBlocks.get(idx) == ir().linearScanOrder().get(idx) : "invalid cached block list";
        return cachedBlocks.get(idx);
    }

    int numVirtualRegs() {
        return numVirtualRegs;
    }

    // size of liveIn and liveOut sets of BasicBlocks (BitMap needs rounded size for iteration)
    int liveSetSize() {
        return Util.roundTo(numVirtualRegs, compilation.target.arch.bitsPerWord);
    }

    boolean hasFpuRegisters() {
        return hasFpuRegisters;
    }

    int numLoops() {
        return ir().numLoops();
    }

    boolean isIntervalInLoop(int interval, int loop) {
        return intervalInLoop.at(interval, loop);
    }

    // access to interval list
    int intervalCount() {
        return intervals.size();
    }

    Interval intervalAt(int regNum) {
        return intervals.get(regNum);
    }

    List<Interval> newIntervalsFromAllocation() {
        return newIntervalsFromAllocation;
    }

    // access to LIROps and Blocks indexed by opId
    int maxLirOpId() {
        assert lirOps.length > 0 : "no operations";
        return (lirOps.length - 1) << 1;
    }

    LIRInstruction lirOpWithId(int opId) {
        assert opId >= 0 && opId <= maxLirOpId() && opId % 2 == 0 : "opId out of range or not even";
        return lirOps[opId >> 1];
    }

    BlockBegin blockOfOpWithId(int opId) {
        assert blockOfOp.length > 0 && opId >= 0 && opId <= maxLirOpId() + 1 : "opId out of range";
        return blockOfOp[opId >> 1];
    }

    boolean isBlockBegin(int opId) {
        return opId == 0 || blockOfOpWithId(opId) != blockOfOpWithId(opId - 1);
    }

    boolean coversBlockBegin(int opId1, int opId2) {
        return blockOfOpWithId(opId1) != blockOfOpWithId(opId2);
    }

    boolean hasCall(int opId) {
        assert opId % 2 == 0 : "must be even";
        return hasCall.get(opId >> 1);
    }

    boolean hasInfo(int opId) {
        assert opId % 2 == 0 : "must be even";
        return hasInfo.get(opId >> 1);
    }

    // functions for converting LIR-Operands to register numbers
    static boolean isValidRegNum(int regNum) {
        return regNum >= 0;
    }

    // accessors used by Compilation
    public int maxSpills() {
        return maxSpills;
    }

    public int numCalls() {
        assert numCalls >= 0 : "not set";
        return numCalls;
    }

    // * spill move optimization
    // eliminate moves from register to stack if stack slot is known to be correct

    // called during building of intervals
    void changeSpillDefinitionPos(Interval interval, int defPos) {
        assert interval.isSplitParent() : "can only be called for split parents";

        switch (interval.spillState()) {
            case noDefinitionFound:
                assert interval.spillDefinitionPos() == -1 : "must no be set before";
                interval.setSpillDefinitionPos(defPos);
                interval.setSpillState(IntervalSpillState.oneDefinitionFound);
                break;

            case oneDefinitionFound:
                assert defPos <= interval.spillDefinitionPos() : "positions are processed in reverse order when intervals are created";
                if (defPos < interval.spillDefinitionPos() - 2) {
                    // second definition found, so no spill optimization possible for this interval
                    interval.setSpillState(IntervalSpillState.noOptimization);
                } else {
                    // two consecutive definitions (because of two-operand LIR form)
                    assert blockOfOpWithId(defPos) == blockOfOpWithId(interval.spillDefinitionPos()) : "block must be equal";
                }
                break;

            case noOptimization:
                // nothing to do
                break;

            default:
                assert false : "other states not allowed at this time";
        }
    }

    // called during register allocation
    void changeSpillState(Interval interval, int spillPos) {
        switch (interval.spillState()) {
            case oneDefinitionFound: {
                int defLoopDepth = blockOfOpWithId(interval.spillDefinitionPos()).loopDepth();
                int spillLoopDepth = blockOfOpWithId(spillPos).loopDepth();

                if (defLoopDepth < spillLoopDepth) {
                    // the loop depth of the spilling position is higher then the loop depth
                    // at the definition of the interval . move write to memory out of loop
                    // by storing at definitin of the interval
                    interval.setSpillState(IntervalSpillState.storeAtDefinition);
                } else {
                    // the interval is currently spilled only once, so for now there is no
                    // reason to store the interval at the definition
                    interval.setSpillState(IntervalSpillState.oneMoveInserted);
                }
                break;
            }

            case oneMoveInserted: {
                // the interval is spilled more then once, so it is better to store it to
                // memory at the definition
                interval.setSpillState(IntervalSpillState.storeAtDefinition);
                break;
            }

            case storeAtDefinition:
            case startInMemory:
            case noOptimization:
            case noDefinitionFound:
                // nothing to do
                break;

            default:
                assert false : "other states not allowed at this time";
        }
    }

    interface IntervalClosure {

        boolean apply(Interval i);
    }

    private final IntervalClosure mustStoreAtDefinition = new IntervalClosure() {

        public boolean apply(Interval i) {
            return i.isSplitParent() && i.spillState() == IntervalSpillState.storeAtDefinition;
        }
    };

    // called once before asignment of register numbers
    void eliminateSpillMoves() {
        // TIMELINEARSCAN(timerEliminateSpillMoves);
        Util.traceLinearScan(3, " Eliminating unnecessary spill moves");

        // collect all intervals that must be stored after their definion.
        // the list is sorted by Interval.spillDefinitionPos
        Interval interval;
        Interval[] result = createUnhandledLists(mustStoreAtDefinition, null);
        interval = result[0];
        if (C1XOptions.DetailedAsserts) {
            Interval prev = null;
            Interval temp = interval;
            while (temp != Interval.end()) {
                assert temp.spillDefinitionPos() > 0 : "invalid spill definition pos";
                if (prev != null) {
                    assert temp.from() >= prev.from() : "intervals not sorted";
                    assert temp.spillDefinitionPos() >= prev.spillDefinitionPos() : "when intervals are sorted by from :  then they must also be sorted by spillDefinitionPos";
                }

                assert temp.canonicalSpillSlot() >= nofRegs : "interval has no spill slot assigned";
                assert temp.spillDefinitionPos() >= temp.from() : "invalid order";
                assert temp.spillDefinitionPos() <= temp.from() + 2 : "only intervals defined once at their start-pos can be optimized";

                Util.traceLinearScan(4, "interval %d (from %d to %d) must be stored at %d", temp.regNum(), temp.from(), temp.to(), temp.spillDefinitionPos());

                // TODO: Check if this is correct?!
                prev = temp;
                temp = temp.next();
            }
        }

        LIRInsertionBuffer insertionBuffer = new LIRInsertionBuffer();
        int numBlocks = blockCount();
        for (int i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            List<LIRInstruction> instructions = block.lir().instructionsList();
            int numInst = instructions.size();
            boolean hasNew = false;

            // iterate all instructions of the block. skip the first because it is always a label
            for (int j = 1; j < numInst; j++) {
                LIRInstruction op = instructions.get(j);
                int opId = op.id();

                if (opId == -1) {
                    // remove move from register to stack if the stack slot is guaranteed to be correct.
                    // only moves that have been inserted by LinearScan can be removed.
                    assert op.code() == LIROpcode.Move : "only moves can have a opId of -1";
                    assert ((LIROp1) op).resultOpr().isVirtual() : "LinearScan inserts only moves to virtual registers";

                    LIROp1 op1 = (LIROp1) op;
                    Interval curInterval = intervalAt(op1.resultOpr().vregNumber());

                    if (curInterval.assignedReg() >= nofRegs && curInterval.alwaysInMemory()) {
                        // move target is a stack slot that is always correct, so eliminate instruction
                        Util.traceLinearScan(4, "eliminating move from interval %d to %d", op1.inOpr().vregNumber(), op1.resultOpr().vregNumber());
                        instructions.set(j, null); // null-instructions are deleted by assignRegNum
                    }

                } else {
                    // insert move from register to stack just after the beginning of the interval
                    assert interval == Interval.end() || interval.spillDefinitionPos() >= opId : "invalid order";
                    assert interval == Interval.end() || (interval.isSplitParent() && interval.spillState() == IntervalSpillState.storeAtDefinition) : "invalid interval";

                    while (interval != Interval.end() && interval.spillDefinitionPos() == opId) {
                        if (!hasNew) {
                            // prepare insertion buffer (appended when all instructions of the block are processed)
                            insertionBuffer.init(block.lir());
                            hasNew = true;
                        }

                        LIROperand fromOpr = operandForInterval(interval);
                        LIROperand toOpr = canonicalSpillOpr(interval);
                        assert fromOpr.isFixedCpu() || fromOpr.isFixedFpu() : "from operand must be a register";
                        assert toOpr.isStack() : "to operand must be a stack slot";

                        insertionBuffer.move(j, fromOpr, toOpr);
                        Util.traceLinearScan(4, "inserting move after definition of interval %d to stack slot %d at opId %d", interval.regNum(), interval.canonicalSpillSlot() - nofRegs, opId);

                        interval = interval.next();
                    }
                }
            } // end of instruction iteration

            if (hasNew) {
                block.lir().append(insertionBuffer);
            }
        } // end of block iteration

        assert interval == Interval.end() : "missed an interval";
    }

    // * Phase 1: number all instructions in all blocks
    // Compute depth-first and linear scan block orders, and number LIRInstruction nodes for linear scan.

    void numberInstructions() {

        // TIMELINEARSCAN(timerNumberInstructions);

        // Assign IDs to LIR nodes and build a mapping, lirOps, from ID to LIRInstruction node.
        int numBlocks = blockCount();
        int numInstructions = 0;
        int i;
        for (i = 0; i < numBlocks; i++) {
            numInstructions += blockAt(i).lir().instructionsList().size();
        }

        // initialize with correct length
        lirOps = new LIRInstruction[numInstructions];
        blockOfOp = new BlockBegin[numInstructions];

        int opId = 0;
        int idx = 0;

        for (i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            block.setFirstLirInstructionId(opId);
            List<LIRInstruction> instructions = block.lir().instructionsList();

            int numInst = instructions.size();
            for (int j = 0; j < numInst; j++) {
                LIRInstruction op = instructions.get(j);
                op.setId(opId);

                lirOps[idx] = op;
                blockOfOp[idx] = block;
                assert lirOpWithId(opId) == op : "must match";

                idx++;
                opId += 2; // numbering of lirOps by two
            }
            block.setLastLirInstructionId(opId - 2);
        }
        assert idx == numInstructions : "must match";
        assert idx * 2 == opId : "must match";

        hasCall = new BitMap(numInstructions);
        hasInfo = new BitMap(numInstructions);
    }

    // * Phase 2: compute local live sets separately for each block
    // (sets liveGen and liveKill for each block)

    void setLiveGenKill(Instruction value, LIRInstruction op, BitMap liveGen, BitMap liveKill) {
        LIROperand opr = value.operand();
        Constant con = null;
        if (value instanceof Constant) {
            con = (Constant) value;
        }

        // check some asumptions about debug information
        assert !value.type().isIllegal() : "if this local is used by the interpreter it shouldn't be of indeterminate type";
        assert con == null || opr.isVirtual() || opr.isConstant() || opr.isIllegal() : "asumption: Constant instructions have only constant operands";
        assert con != null || opr.isVirtual() : "asumption: non-Constant instructions have only virtual operands";

        if ((con == null || con.isPinned()) && opr.isRegister()) {
            assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
            int reg = opr.vregNumber();
            if (!liveKill.get(reg)) {
                liveGen.set(reg);
                Util.traceLinearScan(4, "  Setting liveGen for value %c%d, LIR opId %d, register number %d", value.type().tchar(), value.id(), op.id(), reg);
            }
        }
    }

    void computeLocalLiveSets() {
        // TIMELINEARSCAN(timerComputeLocalLiveSets);

        int numBlocks = blockCount();
        int liveSize = liveSetSize();
        boolean localHasFpuRegisters = false;
        int localNumCalls = 0;
        LIRVisitState visitor = new LIRVisitState();

        BitMap2D localIntervalInLoop = new BitMap2D(numVirtualRegs, numLoops());

        // iterate all blocks
        for (int i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            BitMap liveGen = new BitMap(liveSize);
            BitMap liveKill = new BitMap(liveSize);

            if (block.checkBlockFlag(BlockBegin.BlockFlag.ExceptionEntry)) {
                // Phi functions at the begin of an exception handler are
                // implicitly defined (= killed) at the beginning of the block.

                for (Phi phi : block.phis()) {
                    liveKill.set(phi.operand().vregNumber());
                }
            }

            List<LIRInstruction> instructions = block.lir().instructionsList();
            int numInst = instructions.size();

            // iterate all instructions of the block. skip the first because it is always a label
            assert visitor.noOperands(instructions.get(0)) : "first operation must always be a label";
            for (int j = 1; j < numInst; j++) {
                LIRInstruction op = instructions.get(j);

                // visit operation to collect all operands
                visitor.visit(op);

                if (visitor.hasCall()) {
                    hasCall.set(op.id() >> 1);
                    localNumCalls++;
                }
                if (visitor.infoCount() > 0) {
                    hasInfo.set(op.id() >> 1);
                }

                // iterate input operands of instruction
                int k;
                int n;
                int reg;
                n = visitor.oprCount(LIRVisitState.OperandMode.InputMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.InputMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";

                    if (opr.isVirtualRegister()) {
                        assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
                        reg = opr.vregNumber();
                        if (!liveKill.get(reg)) {
                            liveGen.set(reg);
                            Util.traceLinearScan(4, "  Setting liveGen for register %d at instruction %d", reg, op.id());
                        }
                        if (block.loopIndex() >= 0) {
                            localIntervalInLoop.setBit(reg, block.loopIndex());
                        }
                        localHasFpuRegisters = localHasFpuRegisters || opr.isVirtualFpu();
                    }

                    if (C1XOptions.DetailedAsserts) {
                        // fixed intervals are never live at block boundaries, so
                        // they need not be processed in live sets.
                        // this is checked by these assert ons to be sure about it. // the entry block may have incoming
                        // values in registers, which is ok.
                        if (!opr.isVirtualRegister() && block != ir().startBlock) {
                            reg = regNum(opr);
                            if (isProcessedRegNum(reg)) {
                                assert liveKill.get(reg) : "using fixed register that is not defined in this block";
                            }
                            reg = regNumHi(opr);
                            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                                assert liveKill.get(reg) : "using fixed register that is not defined in this block";
                            }
                        }
                    }
                }

                // Add uses of live locals from interpreter's point of view for proper debug information generation
                n = visitor.infoCount();
                for (k = 0; k < n; k++) {
                    CodeEmitInfo info = visitor.infoAt(k);
                    ValueStack stack = info.stack();
                    for (Instruction value : stack.allStateValues()) {
                        setLiveGenKill(value, op, liveGen, liveKill);
                    }
                }

                // iterate temp operands of instruction
                n = visitor.oprCount(LIRVisitState.OperandMode.TempMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.TempMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";

                    if (opr.isVirtualRegister()) {
                        assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
                        reg = opr.vregNumber();
                        liveKill.set(reg);
                        if (block.loopIndex() >= 0) {
                            localIntervalInLoop.setBit(reg, block.loopIndex());
                        }
                        localHasFpuRegisters = localHasFpuRegisters || opr.isVirtualFpu();
                    }

                    if (C1XOptions.DetailedAsserts) {
                        // fixed intervals are never live at block boundaries, so
                        // they need not be processed in live sets
                        // process them only in debug mode so that this can be checked
                        if (!opr.isVirtualRegister()) {
                            reg = regNum(opr);
                            if (isProcessedRegNum(reg)) {
                                liveKill.set(regNum(opr));
                            }
                            reg = regNumHi(opr);
                            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                                liveKill.set(reg);
                            }
                        }
                    }
                }

                // iterate output operands of instruction
                n = visitor.oprCount(LIRVisitState.OperandMode.OutputMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.OutputMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";

                    if (opr.isVirtualRegister()) {
                        assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
                        reg = opr.vregNumber();
                        liveKill.set(reg);
                        if (block.loopIndex() >= 0) {
                            localIntervalInLoop.setBit(reg, block.loopIndex());
                        }
                        localHasFpuRegisters = localHasFpuRegisters || opr.isVirtualFpu();
                    }

                    if (C1XOptions.DetailedAsserts) {
                        // fixed intervals are never live at block boundaries, so
                        // they need not be processed in live sets
                        // process them only in debug mode so that this can be checked
                        if (!opr.isVirtualRegister()) {
                            reg = regNum(opr);
                            if (isProcessedRegNum(reg)) {
                                liveKill.set(regNum(opr));
                            }
                            reg = regNumHi(opr);
                            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                                liveKill.set(reg);
                            }
                        }
                    }
                }
            } // end of instruction iteration

            block.setLiveGen(liveGen);
            block.setLiveKill(liveKill);
            block.setLiveIn(new BitMap(liveSize));
            block.setLiveOut(new BitMap(liveSize));

            Util.traceLinearScan(4, "liveGen  B%d ", block.blockID());
            if (C1XOptions.TraceLinearScanLevel >= 4) {
                TTY.println(block.liveGen().toString());
            }
            Util.traceLinearScan(4, "liveKill B%d ", block.blockID());
            if (C1XOptions.TraceLinearScanLevel >= 4) {
                TTY.println(block.liveKill().toString());
            }
        } // end of block iteration

        // propagate local calculated information into LinearScan object
        hasFpuRegisters = localHasFpuRegisters;
        compilation().setHasFpuCode(localHasFpuRegisters);

        numCalls = localNumCalls;
        intervalInLoop = localIntervalInLoop;
    }

    // * Phase 3: perform a backward dataflow analysis to compute global live sets
    // (sets liveIn and liveOut for each block)

    void computeGlobalLiveSets() {
        // TIMELINEARSCAN(timerComputeGlobalLiveSets);

        int numBlocks = blockCount();
        boolean changeOccurred;
        boolean changeOccurredInBlock;
        int iterationCount = 0;
        BitMap liveOut = new BitMap(liveSetSize()); // scratch set for calculations

        // Perform a backward dataflow analysis to compute liveOut and liveIn for each block.
        // The loop is executed until a fixpoint is reached (no changes in an iteration)
        // Exception handlers must be processed because not all live values are
        // present in the state array, e.g. because of global value numbering
        do {
            changeOccurred = false;

            // iterate all blocks in reverse order
            for (int i = numBlocks - 1; i >= 0; i--) {
                BlockBegin block = blockAt(i);

                changeOccurredInBlock = false;

                // liveOut(block) is the union of liveIn(sux), for successors sux of block
                int n = block.numberOfSux();
                int e = block.numberOfExceptionHandlers();
                if (n + e > 0) {
                    // block has successors
                    if (n > 0) {
                        liveOut.setFrom(block.suxAt(0).liveIn());
                        for (int j = 1; j < n; j++) {
                            liveOut.setUnion(block.suxAt(j).liveIn());
                        }
                    } else {
                        liveOut.clearAll();
                    }
                    for (int j = 0; j < e; j++) {
                        liveOut.setUnion(block.exceptionHandlerAt(j).liveIn());
                    }

                    if (!block.liveOut().isSame(liveOut)) {
                        // A change occurred. Swap the old and new live out sets to avoid copying.
                        BitMap temp = block.liveOut();
                        block.setLiveOut(liveOut);
                        liveOut = temp;

                        changeOccurred = true;
                        changeOccurredInBlock = true;
                    }
                }

                if (iterationCount == 0 || changeOccurredInBlock) {
                    // liveIn(block) is the union of liveGen(block) with (liveOut(block) & !liveKill(block))
                    // note: liveIn has to be computed only in first iteration or if liveOut has changed!
                    BitMap liveIn = block.liveIn();
                    liveIn.setFrom(block.liveOut());
                    liveIn.setDifference(block.liveKill());
                    liveIn.setUnion(block.liveGen());
                }

                if (C1XOptions.TraceLinearScanLevel >= 4) {
                    char c = ' ';
                    if (iterationCount == 0 || changeOccurredInBlock) {
                        c = '*';
                    }
                    TTY.print("(%d) liveIn%c  B%d ", iterationCount, c, block.blockID());
                    TTY.println(block.liveIn().toString());
                    TTY.print("(%d) liveOut%c B%d ", iterationCount, c, block.blockID());
                    TTY.println(block.liveOut().toString());
                }
            }
            iterationCount++;

            if (changeOccurred && iterationCount > 50) {
                throw new Bailout("too many iterations in computeGlobalLiveSets");
            }
        } while (changeOccurred);

        if (C1XOptions.DetailedAsserts) {
            // check that fixed intervals are not live at block boundaries
            // (live set must be empty at fixed intervals)
            for (int i = 0; i < numBlocks; i++) {
                BlockBegin block = blockAt(i);
                for (int j = 0; j < Register.vregBase; j++) {
                    assert block.liveIn().get(j) == false : "liveIn  set of fixed register must be empty";
                    assert block.liveOut().get(j) == false : "liveOut set of fixed register must be empty";
                    assert block.liveGen().get(j) == false : "liveGen set of fixed register must be empty";
                }
            }
        }

        // check that the liveIn set of the first block is empty
        BitMap liveInArgs = new BitMap(ir().startBlock.liveIn().size());
        if (!ir().startBlock.liveIn().isSame(liveInArgs)) {

            if (C1XOptions.DetailedAsserts) {
                TTY.println("Error: liveIn set of first block must be empty (when this fails, virtual registers are used before they are defined)");
                TTY.print("affected registers:");
                TTY.println(ir().startBlock.liveIn().toString());

                // print some additional information to simplify debugging
                for (int i = 0; i < ir().startBlock.liveIn().size(); i++) {
                    if (ir().start().liveIn().get(i)) {
                        Instruction instr = gen().instructionForVreg(i);
                        TTY.println(" vreg %d (HIR instruction %c%d)", i, instr == null ? ' ' : instr.type().tchar(), instr == null ? 0 : instr.id());

                        for (int j = 0; j < numBlocks; j++) {
                            BlockBegin block = blockAt(j);
                            if (block.liveGen().get(i)) {
                                TTY.println("  used in block B%d", block.blockID());
                            }
                            if (block.liveKill().get(i)) {
                                TTY.println("  defined in block B%d", block.blockID());
                            }
                        }
                    }
                }
            }

            // when this fails, virtual registers are used before they are defined.
            assert false : "liveIn set of first block must be empty";
            // bailout of if this occurs in product mode.
            bailout("liveIn set of first block not empty");
        }
    }

    // * Phase 4: build intervals
    // (fills the list intervals)

    void addUse(Instruction value, int from, int to, IntervalUseKind useKind) {
        assert !value.type().isIllegal() : "if this value is used by the interpreter it shouldn't be of indeterminate type";
        LIROperand opr = value.operand();
        Constant con = null;
        if (value instanceof Constant) {
            con = (Constant) value;
        }

        if ((con == null || con.isPinned()) && opr.isRegister()) {
            assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
            addUse(opr, from, to, useKind);
        }
    }

    void addDef(LIROperand opr, int defPos, IntervalUseKind useKind) {
        if (C1XOptions.TraceLinearScanLevel >= 2) {
            TTY.print(" def ");
            opr.print(TTY.out);
            TTY.println(" defPos %d (%s)", defPos, useKind.name());
        }
        assert opr.isRegister() : "should not be called otherwise";

        if (opr.isVirtualRegister()) {
            assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
            addDef(opr.vregNumber(), defPos, useKind, opr.typeRegister());

        } else {
            int reg = regNum(opr);
            if (isProcessedRegNum(reg)) {
                addDef(reg, defPos, useKind, opr.typeRegister());
            }
            reg = regNumHi(opr);
            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                addDef(reg, defPos, useKind, opr.typeRegister());
            }
        }
    }

    void addUse(LIROperand opr, int from, int to, IntervalUseKind useKind) {
        if (C1XOptions.TraceLinearScanLevel >= 2) {
            TTY.print(" use ");
            opr.print(TTY.out);
            TTY.println(" from %d to %d (%s)", from, to, useKind.name());
        }
        assert opr.isRegister() : "should not be called otherwise";

        if (opr.isVirtualRegister()) {
            assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
            addUse(opr.vregNumber(), from, to, useKind, opr.typeRegister());

        } else {
            int reg = regNum(opr);
            if (isProcessedRegNum(reg)) {
                addUse(reg, from, to, useKind, opr.typeRegister());
            }
            reg = regNumHi(opr);
            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                addUse(reg, from, to, useKind, opr.typeRegister());
            }
        }
    }

    void addTemp(LIROperand opr, int tempPos, IntervalUseKind useKind) {
        if (C1XOptions.TraceLinearScanLevel >= 2) {
            TTY.print(" temp ");
            opr.print(TTY.out);
            TTY.println(" tempPos %d (%s)", tempPos, useKind.name());
        }
        assert opr.isRegister() : "should not be called otherwise";

        if (opr.isVirtualRegister()) {
            assert regNum(opr) == opr.vregNumber() && !isValidRegNum(regNumHi(opr)) : "invalid optimization below";
            addTemp(opr.vregNumber(), tempPos, useKind, opr.typeRegister());

        } else {
            int reg = regNum(opr);
            if (isProcessedRegNum(reg)) {
                addTemp(reg, tempPos, useKind, opr.typeRegister());
            }
            reg = regNumHi(opr);
            if (isValidRegNum(reg) && isProcessedRegNum(reg)) {
                addTemp(reg, tempPos, useKind, opr.typeRegister());
            }
        }
    }

    boolean isProcessedRegNum(int reg) {
        return reg > Register.vregBase || (reg >= 0 && reg < registerMapping.length && registerMapping[reg] != null);
    }

    void addDef(int regNum, int defPos, IntervalUseKind useKind, BasicType type) {
        Interval interval = intervalAt(regNum);
        if (interval != null) {
            assert interval.regNum() == regNum : "wrong interval";

            if (type != BasicType.Illegal) {
                interval.setType(type);
            }

            Range r = interval.first();
            if (r.from() <= defPos) {
                // Update the starting point (when a range is first created for a use, its
                // start is the beginning of the current block until a def is encountered.)
                r.setFrom(defPos);
                interval.addUsePos(defPos, useKind);

            } else {
                // Dead value - make vacuous interval
                // also add useKind for dead intervals
                interval.addRange(defPos, defPos + 1);
                interval.addUsePos(defPos, useKind);
                Util.traceLinearScan(2, "Warning: def of reg %d at %d occurs without use", regNum, defPos);
            }

        } else {
            // Dead value - make vacuous interval
            // also add useKind for dead intervals
            interval = createInterval(regNum);
            if (type != BasicType.Illegal) {
                interval.setType(type);
            }

            interval.addRange(defPos, defPos + 1);
            interval.addUsePos(defPos, useKind);
            Util.traceLinearScan(2, "Warning: dead value %d at %d in live intervals", regNum, defPos);
        }

        changeSpillDefinitionPos(interval, defPos);
        if (useKind == IntervalUseKind.noUse && interval.spillState().ordinal() <= IntervalSpillState.startInMemory.ordinal()) {
            // detection of method-parameters and roundfp-results
            // TODO: move this directly to position where use-kind is computed
            interval.setSpillState(IntervalSpillState.startInMemory);
        }
    }

    void addUse(int regNum, int from, int to, IntervalUseKind useKind, BasicType type) {
        Interval interval = intervalAt(regNum);
        if (interval == null) {
            interval = createInterval(regNum);
        }
        assert interval.regNum() == regNum : "wrong interval";

        if (type != BasicType.Illegal) {
            interval.setType(type);
        }

        interval.addRange(from, to);
        interval.addUsePos(to, useKind);
    }

    void addTemp(int regNum, int tempPos, IntervalUseKind useKind, BasicType type) {
        Interval interval = intervalAt(regNum);
        if (interval == null) {
            interval = createInterval(regNum);
        }
        assert interval.regNum() == regNum : "wrong interval";

        if (type != BasicType.Illegal) {
            interval.setType(type);
        }

        interval.addRange(tempPos, tempPos + 1);
        interval.addUsePos(tempPos, useKind);
    }

    // the results of this functions are used for optimizing spilling and reloading
    // if the functions return shouldHaveRegister and the interval is spilled,
    // it is not reloaded to a register.
    IntervalUseKind useKindOfOutputOperand(LIRInstruction op, LIROperand opr) {
        if (op.code() == LIROpcode.Move) {
            LIROp1 move = (LIROp1) op;
            LIROperand res = move.resultOpr();
            boolean resultInMemory = res.isVirtual() && gen().isVregFlagSet(res.vregNumber(), LIRGenerator.VregFlag.MustStartInMemory);

            if (resultInMemory) {
                // Begin of an interval with mustStartInMemory set.
                // This interval will always get a stack slot first, so return noUse.
                return IntervalUseKind.noUse;

            } else if (move.inOpr().isStack()) {
                // method argument (condition must be equal to handleMethodArguments)
                return IntervalUseKind.noUse;

            } else if (move.inOpr().isRegister() && move.resultOpr().isRegister()) {
                // Move from register to register
                if (blockOfOpWithId(op.id()).checkBlockFlag(BlockBegin.BlockFlag.OsrEntry)) {
                    // special handling of phi-function moves inside osr-entry blocks
                    // input operand must have a register instead of output operand (leads to better register
                    // allocation)
                    return IntervalUseKind.shouldHaveRegister;
                }
            }
        }

        if (opr.isVirtual() && gen().isVregFlagSet(opr.vregNumber(), LIRGenerator.VregFlag.MustStartInMemory)) {
            // result is a stack-slot, so prevent immediate reloading
            return IntervalUseKind.noUse;
        }

        // all other operands require a register
        return IntervalUseKind.mustHaveRegister;
    }

    IntervalUseKind useKindOfInputOperand(LIRInstruction op, LIROperand opr) {
        if (op.code() == LIROpcode.Move) {
            LIROp1 move = (LIROp1) op;
            LIROperand res = move.resultOpr();
            boolean resultInMemory = res.isVirtual() && gen().isVregFlagSet(res.vregNumber(), LIRGenerator.VregFlag.MustStartInMemory);

            if (resultInMemory) {
                // Move to an interval with mustStartInMemory set.
                // To avoid moves from stack to stack (not allowed) force the input operand to a register
                return IntervalUseKind.mustHaveRegister;

            } else if (move.inOpr().isRegister() && move.resultOpr().isRegister()) {
                // Move from register to register
                if (blockOfOpWithId(op.id()).checkBlockFlag(BlockBegin.BlockFlag.OsrEntry)) {
                    // special handling of phi-function moves inside osr-entry blocks
                    // input operand must have a register instead of output operand (leads to better register
                    // allocation)
                    return IntervalUseKind.mustHaveRegister;
                }

                // The input operand is not forced to a register (moves from stack to register are allowed),
                // but it is faster if the input operand is in a register
                return IntervalUseKind.shouldHaveRegister;
            }
        }

        if (compilation.target.arch.isX86()) {
            if (op.code() == LIROpcode.Cmove) {
                // conditional moves can handle stack operands
                assert op.result().isRegister() : "result must always be in a register";
                return IntervalUseKind.shouldHaveRegister;
            }

            // optimizations for second input operand of arithmehtic operations on Intel
            // this operand is allowed to be on the stack in some cases
            BasicType oprType = opr.typeRegister();
            if (oprType == BasicType.Float || oprType == BasicType.Double) {
                if ((C1XOptions.SSEVersion == 1 && oprType == BasicType.Float) || C1XOptions.SSEVersion >= 2) {
                    // SSE float instruction (BasicType.Double only supported with SSE2)
                    switch (op.code()) {
                        case Cmp:
                        case Add:
                        case Sub:
                        case Mul:
                        case Div: {
                            LIROp2 op2 = (LIROp2) op;
                            if (op2.inOpr1() != op2.inOpr2() && op2.inOpr2() == opr) {
                                assert (op2.resultOpr().isRegister() || op.code() == LIROpcode.Cmp) && op2.inOpr1().isRegister() : "cannot mark second operand as stack if others are not in register";
                                return IntervalUseKind.shouldHaveRegister;
                            }
                        }
                    }
                } else {
                    // FPU stack float instruction
                    switch (op.code()) {
                        case Add:
                        case Sub:
                        case Mul:
                        case Div: {
                            LIROp2 op2 = (LIROp2) op;
                            if (op2.inOpr1() != op2.inOpr2() && op2.inOpr2() == opr) {
                                assert (op2.resultOpr().isRegister() || op.code() == LIROpcode.Cmp) && op2.inOpr1().isRegister() : "cannot mark second operand as stack if others are not in register";
                                return IntervalUseKind.shouldHaveRegister;
                            }
                        }
                    }
                }

            } else if (oprType != BasicType.Long) {
                // integer instruction (note: long operands must always be in register)
                switch (op.code()) {
                    case Cmp:
                    case Add:
                    case Sub:
                    case LogicAnd:
                    case LogicOr:
                    case LogicXor: {
                        LIROp2 op2 = (LIROp2) op;
                        if (op2.inOpr1() != op2.inOpr2() && op2.inOpr2() == opr) {
                            assert (op2.resultOpr().isRegister() || op.code() == LIROpcode.Cmp) && op2.inOpr1().isRegister() : "cannot mark second operand as stack if others are not in register";
                            return IntervalUseKind.shouldHaveRegister;
                        }
                    }
                }
            }
        } // X86

        // all other operands require a register
        return IntervalUseKind.mustHaveRegister;
    }

    void handleMethodArguments(LIRInstruction op) {
        // special handling for method arguments (moves from stack to virtual register):
        // the interval gets no register assigned, but the stack slot.
        // it is split before the first use by the register allocator.

        if (op.code() == LIROpcode.Move) {
            LIROp1 move = (LIROp1) op;

            if (move.inOpr().isStack()) {
                if (C1XOptions.DetailedAsserts) {
                    int argSize = compilation().method().signatureType().argumentSlots(!compilation().method.isStatic());
                    LIROperand o = move.inOpr();
                    if (o.isSingleStack()) {
                        assert o.singleStackIx() >= 0 && o.singleStackIx() < argSize : "out of range";
                    } else if (o.isDoubleStack()) {
                        assert o.doubleStackIx() >= 0 && o.doubleStackIx() < argSize : "out of range";
                    } else {
                        Util.shouldNotReachHere();
                    }

                    assert move.id() > 0 : "invalid id";
                    assert blockOfOpWithId(move.id()).numberOfPreds() == 0 : "move from stack must be in first block";
                    assert move.resultOpr().isVirtual() : "result of move must be a virtual register";

                    Util.traceLinearScan(4, "found move from stack slot %d to vreg %d", o.isSingleStack() ? o.singleStackIx() : o.doubleStackIx(), regNum(move.resultOpr()));
                }

                Interval interval = intervalAt(regNum(move.resultOpr()));

                int stackSlot = nofRegs + (move.inOpr().isSingleStack() ? move.inOpr().singleStackIx() : move.inOpr().doubleStackIx());
                interval.setCanonicalSpillSlot(stackSlot);
                interval.assignReg(stackSlot);
            }
        }
    }

    void handleDoublewordMoves(LIRInstruction op) {
        // special handling for doubleword move from memory to register:
        // in this case the registers of the input Pointer and the result
        // registers must not overlap . add a temp range for the input registers
        if (op.code() == LIROpcode.Move) {
            LIROp1 move = (LIROp1) op;

            if (move.resultOpr().isDoubleCpu() && move.inOpr().isPointer()) {
                final LIRAddress pointer = move.inOpr().asAddressPtr();
                if (pointer != null) {
                    if (pointer.base().isValid()) {
                        addTemp(pointer.base(), op.id(), IntervalUseKind.noUse);
                    }
                    if (pointer.index().isValid()) {
                        addTemp(pointer.index(), op.id(), IntervalUseKind.noUse);
                    }
                }
            }
        }
    }

    void addRegisterHints(LIRInstruction op) {
        switch (op.code()) {
            case Move: // fall through
            case Convert: {
                LIROp1 move = (LIROp1) op;

                LIROperand moveFrom = move.inOpr();
                LIROperand moveTo = move.resultOpr();

                if (moveTo.isRegister() && moveFrom.isRegister()) {
                    Interval from = intervalAt(regNum(moveFrom));
                    Interval to = intervalAt(regNum(moveTo));
                    if (from != null && to != null) {
                        to.setRegisterHint(from);
                        Util.traceLinearScan(4, "operation at opId %d: added hint from interval %d to %d", move.id(), from.regNum(), to.regNum());
                    }
                }
                break;
            }
            case Cmove: {
                LIROp2 cmove = (LIROp2) op;

                LIROperand moveFrom = cmove.inOpr1();
                LIROperand moveTo = cmove.resultOpr();

                if (moveTo.isRegister() && moveFrom.isRegister()) {
                    Interval from = intervalAt(regNum(moveFrom));
                    Interval to = intervalAt(regNum(moveTo));
                    if (from != null && to != null) {
                        to.setRegisterHint(from);
                        Util.traceLinearScan(4, "operation at opId %d: added hint from interval %d to %d", cmove.id(), from.regNum(), to.regNum());
                    }
                }
                break;
            }
        }
    }

    void buildIntervals() {
        // TIMELINEARSCAN(timerBuildIntervals);

        // initialize interval list with expected number of intervals
        // (32 is added to have some space for split children without having to resize the list)
        intervals = new ArrayList<Interval>(numVirtualRegs() + 32);
        // initialize all slots that are used by buildIntervals
        Util.atPutGrow(intervals, numVirtualRegs() - 1, null, null);

        // create a list with all caller-save registers (cpu, fpu, xmm)
        // when an instruction is a call, a temp range is created for all these registers
        int numCallerSaveRegisters = 0;
        int[] callerSaveRegisters = new int[nofRegs];
        int z = 0;
        for (Register r : compilation.runtime.callerSavedRegisters()) {
            callerSaveRegisters[z++] = r.number;
        }

        // TODO: Check if the order of the registers (cpu, fpu, xmm) is important there!

        LIRVisitState visitor = new LIRVisitState();

        // iterate all blocks in reverse order
        for (int i = blockCount() - 1; i >= 0; i--) {
            BlockBegin block = blockAt(i);
            List<LIRInstruction> instructions = block.lir().instructionsList();
            int blockFrom = block.firstLirInstructionId();
            int blockTo = block.lastLirInstructionId();

            assert blockFrom == instructions.get(0).id() : "must be";
            assert blockTo == instructions.get(instructions.size() - 1).id() : "must be";

            // Update intervals for registers live at the end of this block;
            BitMap live = block.liveOut();
            int size = live.size();
            for (int number = live.getNextOneOffset(0, size); number < size; number = live.getNextOneOffset(number + 1, size)) {
                assert live.get(number) : "should not stop here otherwise";
                assert number >= Register.vregBase : "fixed intervals must not be live on block bounds";
                Util.traceLinearScan(2, "live in %d to %d", number, blockTo + 2);

                addUse(number, blockFrom, blockTo + 2, IntervalUseKind.noUse, BasicType.Illegal);

                // add special use positions for loop-end blocks when the
                // interval is used anywhere inside this loop. It's possible
                // that the block was part of a non-natural loop, so it might
                // have an invalid loop index.
                if (block.checkBlockFlag(BlockBegin.BlockFlag.LinearScanLoopEnd) && block.loopIndex() != -1 && isIntervalInLoop(number, block.loopIndex())) {
                    intervalAt(number).addUsePos(blockTo + 1, IntervalUseKind.loopEndMarker);
                }
            }

            // iterate all instructions of the block in reverse order.
            // skip the first instruction because it is always a label
            // definitions of intervals are processed before uses
            assert visitor.noOperands(instructions.get(0)) : "first operation must always be a label";
            for (int j = instructions.size() - 1; j >= 1; j--) {
                LIRInstruction op = instructions.get(j);
                int opId = op.id();

                // visit operation to collect all operands
                visitor.visit(op);

                // add a temp range for each register if operation destroys caller-save registers
                if (visitor.hasCall()) {
                    for (int k = 0; k < numCallerSaveRegisters; k++) {
                        addTemp(callerSaveRegisters[k], opId, IntervalUseKind.noUse, BasicType.Illegal);
                    }
                    Util.traceLinearScan(4, "operation destroys all caller-save registers");
                }

                // Add any platform dependent temps
                pdAddTemps(op);

                // visit definitions (output and temp operands)
                int k;
                int n;
                n = visitor.oprCount(LIRVisitState.OperandMode.OutputMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.OutputMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";
                    addDef(opr, opId, useKindOfOutputOperand(op, opr));
                }

                n = visitor.oprCount(LIRVisitState.OperandMode.TempMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.TempMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";
                    addTemp(opr, opId, IntervalUseKind.mustHaveRegister);
                }

                // visit uses (input operands)
                n = visitor.oprCount(LIRVisitState.OperandMode.InputMode);
                for (k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(LIRVisitState.OperandMode.InputMode, k);
                    assert opr.isRegister() : "visitor should only return register operands";
                    addUse(opr, blockFrom, opId, useKindOfInputOperand(op, opr));
                }

                // Add uses of live locals from interpreter's point of view for proper
                // debug information generation
                // Treat these operands as temp values (if the life range is extended
                // to a call site, the value would be in a register at the call otherwise)
                n = visitor.infoCount();
                for (k = 0; k < n; k++) {
                    CodeEmitInfo info = visitor.infoAt(k);
                    ValueStack stack = info.stack();
                    for (Instruction value : stack.allStateValues()) {
                        addUse(value, blockFrom, opId + 1, IntervalUseKind.noUse);
                    }
                }

                // special steps for some instructions (especially moves)
                handleMethodArguments(op);
                handleDoublewordMoves(op);
                addRegisterHints(op);

            } // end of instruction iteration
        } // end of block iteration

        // add the range [0, 1[ to all fixed intervals
        // . the register allocator need not handle unhandled fixed intervals
        for (int n = 0; n < nofRegs; n++) {
            Interval interval = intervalAt(n);
            if (interval != null) {
                interval.addRange(0, 1);
            }
        }
    }

    // * Phase 5: actual register allocation

    private void pdAddTemps(LIRInstruction op) {
        // TODO Platform dependent!
        assert compilation.target.arch.isX86();

        switch (op.code()) {
            case Tan:
            case Sin:
            case Cos: {
                // The slow path for these functions may need to save and
                // restore all live registers but we don't want to save and
                // restore everything all the time, so mark the xmms as being
                // killed. If the slow path were explicit or we could propagate
                // live register masks down to the assembly we could do better
                // but we don't have any easy way to do that right now. We
                // could also consider not killing all xmm registers if we
                // assume that slow paths are uncommon but it's not clear that
                // would be a good idea.
                if (C1XOptions.SSEVersion > 0) {
                    if (C1XOptions.TraceLinearScanLevel >= 2) {
                        TTY.println("killing XMMs for trig");
                    }
                    int opId = op.id();

                    for (Register r : compilation.frameMap().callerSavedRegisters()) {
                        if (r.isXmm()) {
                            addTemp(r.number, opId, IntervalUseKind.noUse, BasicType.Illegal);
                        }
                    }
                }
                break;
            }
        }

    }

    int intervalCmp(Interval a, Interval b) {
        if (a != null) {
            if (b != null) {
                return (a).from() - (b).from();
            } else {
                return -1;
            }
        } else {
            if (b != null) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    boolean isSorted(Interval[] intervals) {
        int from = -1;
        int i;
        int j;
        for (i = 0; i < intervals.length; i++) {
            Interval it = intervals[i];
            if (it != null) {
                if (from > it.from()) {
                    assert false : "";
                    return false;
                }
                from = it.from();
            }
        }

        // check in both directions if sorted list and unsorted list contain same intervals
        for (i = 0; i < intervalCount(); i++) {
            if (intervalAt(i) != null) {
                int numFound = 0;
                for (j = 0; j < intervals.length; j++) {
                    if (intervalAt(i) == intervals[j]) {
                        numFound++;
                    }
                }
                assert numFound == 1 : "lists do not contain same intervals";
            }
        }
        for (j = 0; j < intervals.length; j++) {
            int numFound = 0;
            for (i = 0; i < intervalCount(); i++) {
                if (intervalAt(i) == intervals[j]) {
                    numFound++;
                }
            }
            assert numFound == 1 : "lists do not contain same intervals";
        }

        return true;
    }

    Interval addToList(Interval first, Interval prev, Interval interval) {
        Interval newFirst = first;
        if (prev != null) {
            prev.setNext(interval);
        } else {
            newFirst = interval;
        }
        return newFirst;
    }

    Interval[] createUnhandledLists(IntervalClosure isList1, IntervalClosure isList2) {
        assert isSorted(sortedIntervals) : "interval list is not sorted";

        Interval list1 = Interval.end();
        Interval list2 = Interval.end();

        Interval[] result = new Interval[2];
        Interval list1Prev = null;
        Interval list2Prev = null;
        Interval v;

        int n = sortedIntervals.length;
        for (int i = 0; i < n; i++) {
            v = sortedIntervals[i];
            if (v == null) {
                continue;
            }

            if (isList1.apply(v)) {
                list1 = addToList(list1, list1Prev, v);
                list1Prev = v;
            } else if (isList2 == null || isList2.apply(v)) {
                list2 = addToList(list2, list2Prev, v);
                list2Prev = v;
            }
        }

        if (list1Prev != null) {
            list1Prev.setNext(Interval.end());
        }
        if (list2Prev != null) {
            list2Prev.setNext(Interval.end());
        }

        assert list1Prev == null || list1Prev.next() == Interval.end() : "linear list ends not with sentinel";
        assert list2Prev == null || list2Prev.next() == Interval.end() : "linear list ends not with sentinel";

        result[0] = list1;
        result[1] = list2;
        return result;
    }

    void sortIntervalsBeforeAllocation() {
        // TIMELINEARSCAN(timerSortIntervalsBefore);

        List<Interval> unsortedList = intervals;
        int unsortedLen = unsortedList.size();
        int sortedLen = 0;
        int unsortedIdx;
        int sortedIdx = 0;
        int sortedFromMax = -1;

        // calc number of items for sorted list (sorted list must not contain null values)
        for (unsortedIdx = 0; unsortedIdx < unsortedLen; unsortedIdx++) {
            if (unsortedList.get(unsortedIdx) != null) {
                sortedLen++;
            }
        }
        Interval[] sortedList = new Interval[sortedLen];

        // special sorting algorithm: the original interval-list is almost sorted,
        // only some intervals are swapped. So this is much faster than a complete QuickSort
        for (unsortedIdx = 0; unsortedIdx < unsortedLen; unsortedIdx++) {
            Interval curInterval = unsortedList.get(unsortedIdx);

            if (curInterval != null) {
                int curFrom = curInterval.from();

                if (sortedFromMax <= curFrom) {
                    sortedList[sortedIdx++] = curInterval;
                    sortedFromMax = curInterval.from();
                } else {
                    // the asumption that the intervals are already sorted failed,
                    // so this interval must be sorted in manually
                    int j;
                    for (j = sortedIdx - 1; j >= 0 && curFrom < sortedList[j].from(); j--) {
                        sortedList[j + 1] = sortedList[j];
                    }
                    sortedList[j + 1] = curInterval;
                    sortedIdx++;
                }
            }
        }
        sortedIntervals = sortedList;
    }

    void sortIntervalsAfterAllocation() {
        // TIMELINEARSCAN(timerSortIntervalsAfter);

        Interval[] oldList = sortedIntervals;
        List<Interval> newList = newIntervalsFromAllocation;
        int oldLen = oldList.length;
        int newLen = newList.size();

        if (newLen == 0) {
            // no intervals have been added during allocation, so sorted list is already up to date
            return;
        }

        // conventional sort-algorithm for new intervals
        Collections.sort(newList, intervalCmp);

        // merge old and new list (both already sorted) into one combined list
        Interval[] combinedList = new Interval[oldLen + newLen];
        int oldIdx = 0;
        int newIdx = 0;

        while (oldIdx + newIdx < oldLen + newLen) {
            if (newIdx >= newLen || (oldIdx < oldLen && oldList[oldIdx].from() <= newList.get(newIdx).from())) {
                combinedList[oldIdx + newIdx] = oldList[oldIdx];
                oldIdx++;
            } else {
                combinedList[oldIdx + newIdx] = newList.get(newIdx);
                newIdx++;
            }
        }

        sortedIntervals = combinedList;
    }

    private final Comparator<Interval> intervalCmp = new Comparator<Interval>() {

        @Override
        public int compare(Interval a, Interval b) {
            if (a != null) {
                if (b != null) {
                    return a.from() - b.from();
                } else {
                    return -1;
                }
            } else {
                if (b != null) {
                    return 1;
                } else {
                    return 0;
                }
            }
        }
    };

    public void allocateRegisters() {
        // TIMELINEARSCAN(timerAllocateRegisters);

        Interval precoloredCpuIntervals;
        Interval notPrecoloredCpuIntervals;
        Interval precoloredFpuIntervals = null;
        Interval notPrecoloredFpuIntervals = null;

        Interval[] result = createUnhandledLists(isPrecoloredCpuInterval, isVirtualCpuInterval);
        precoloredCpuIntervals = result[0];
        notPrecoloredCpuIntervals = result[1];
        if (hasFpuRegisters()) {
            result = createUnhandledLists(isPrecoloredFpuInterval, isVirtualFpuInterval);
            precoloredFpuIntervals = result[0];
            notPrecoloredFpuIntervals = result[1];
        } else if (C1XOptions.DetailedAsserts) {
            // fpu register allocation is omitted because no virtual fpu registers are present
            // just check this again...
            result = createUnhandledLists(isPrecoloredFpuInterval, isVirtualFpuInterval);
            precoloredFpuIntervals = result[0];
            notPrecoloredFpuIntervals = result[1];
            assert notPrecoloredFpuIntervals == Interval.end() : "missed an uncolored fpu interval";
        }

        // allocate cpu registers
        LinearScanWalker cpuLsw = new LinearScanWalker(this, precoloredCpuIntervals, notPrecoloredCpuIntervals);
        cpuLsw.walk();
        cpuLsw.finishAllocation();

        if (hasFpuRegisters()) {
            // allocate fpu registers
            LinearScanWalker fpuLsw = new LinearScanWalker(this, precoloredFpuIntervals, notPrecoloredFpuIntervals);
            fpuLsw.walk();
            fpuLsw.finishAllocation();
        }
    }

    // * Phase 6: resolve data flow
    // (insert moves at edges between blocks if intervals have been split)

    // wrapper for Interval.splitChildAtOpId that performs a bailout in product mode
    // instead of returning null
    Interval splitChildAtOpId(Interval interval, int opId, LIRVisitState.OperandMode mode) {
        Interval result = interval.splitChildAtOpId(opId, mode, this);
        if (result != null) {
            return result;
        }

        assert false : "must find an interval :  but do a clean bailout in product mode";
        result = new Interval(Register.vregBase);
        result.assignReg(0);
        result.setType(BasicType.Int);
        throw new Bailout("LinearScan: interval is null");
    }

    Interval intervalAtBlockBegin(BlockBegin block, int regNum) {
        assert nofRegs <= regNum && regNum < numVirtualRegs() : "register number out of bounds";
        assert intervalAt(regNum) != null : "no interval found";

        return splitChildAtOpId(intervalAt(regNum), block.firstLirInstructionId(), LIRVisitState.OperandMode.OutputMode);
    }

    Interval intervalAtBlockEnd(BlockBegin block, int regNum) {
        assert nofRegs <= regNum && regNum < numVirtualRegs() : "register number out of bounds";
        assert intervalAt(regNum) != null : "no interval found";

        return splitChildAtOpId(intervalAt(regNum), block.lastLirInstructionId() + 1, LIRVisitState.OperandMode.OutputMode);
    }

    Interval intervalAtOpId(int regNum, int opId) {
        assert nofRegs <= regNum && regNum < numVirtualRegs() : "register number out of bounds";
        assert intervalAt(regNum) != null : "no interval found";

        return splitChildAtOpId(intervalAt(regNum), opId, LIRVisitState.OperandMode.InputMode);
    }

    void resolveCollectMappings(BlockBegin fromBlock, BlockBegin toBlock, MoveResolver moveResolver) {
        assert moveResolver.checkEmpty();

        int numRegs = numVirtualRegs();
        int size = liveSetSize();
        BitMap liveAtEdge = toBlock.liveIn();

        // visit all registers where the liveAtEdge bit is set
        for (int r = liveAtEdge.getNextOneOffset(0, size); r < size; r = liveAtEdge.getNextOneOffset(r + 1, size)) {
            assert r < numRegs : "live information set for not exisiting interval";
            assert fromBlock.liveOut().get(r) && toBlock.liveIn().get(r) : "interval not live at this edge";

            Interval fromInterval = intervalAtBlockEnd(fromBlock, r);
            Interval toInterval = intervalAtBlockBegin(toBlock, r);

            if (fromInterval != toInterval && (fromInterval.assignedReg() != toInterval.assignedReg() || fromInterval.assignedRegHi() != toInterval.assignedRegHi())) {
                // need to insert move instruction
                moveResolver.addMapping(fromInterval, toInterval);
            }
        }
    }

    void resolveFindInsertPos(BlockBegin fromBlock, BlockBegin toBlock, MoveResolver moveResolver) {
        if (fromBlock.numberOfSux() <= 1) {
            Util.traceLinearScan(4, "inserting moves at end of fromBlock B%d", fromBlock.blockID());

            List<LIRInstruction> instructions = fromBlock.lir().instructionsList();
            LIRInstruction instr = instructions.get(instructions.size() - 1);
            if (instr instanceof LIRBranch) {
                LIRBranch branch = (LIRBranch) instr;
                // insert moves before branch
                assert branch.cond() == LIRCondition.Always : "block does not end with an unconditional jump";
                moveResolver.setInsertPosition(fromBlock.lir(), instructions.size() - 2);
            } else {
                moveResolver.setInsertPosition(fromBlock.lir(), instructions.size() - 1);
            }

        } else {
            Util.traceLinearScan(4, "inserting moves at beginning of toBlock B%d", toBlock.blockID());

            if (C1XOptions.DetailedAsserts) {
                assert fromBlock.lir().instructionsList().get(0) instanceof LIRLabel : "block does not start with a label";

                // because the number of predecessor edges matches the number of
                // successor edges, blocks which are reached by switch statements
                // may have be more than one predecessor but it will be guaranteed
                // that all predecessors will be the same.
                for (int i = 0; i < toBlock.numberOfPreds(); i++) {
                    assert fromBlock == toBlock.predAt(i) : "all critical edges must be broken";
                }
            }

            moveResolver.setInsertPosition(toBlock.lir(), 0);
        }
    }

    // insert necessary moves (spilling or reloading) at edges between blocks if interval has been split
    void resolveDataFlow() {
        // TIMELINEARSCAN(timerResolveDataFlow);

        int numBlocks = blockCount();
        MoveResolver moveResolver = new MoveResolver(this);
        BitMap blockCompleted = new BitMap(numBlocks);
        BitMap alreadyResolved = new BitMap(numBlocks);

        int i;
        for (i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);

            // check if block has only one predecessor and only one successor
            if (block.numberOfPreds() == 1 && block.numberOfSux() == 1 && block.numberOfExceptionHandlers() == 0) {
                List<LIRInstruction> instructions = block.lir().instructionsList();
                assert instructions.get(0).code() == LIROpcode.Label : "block must start with label";
                assert instructions.get(instructions.size() - 1).code() == LIROpcode.Branch : "block with successors must end with branch";
                assert ((LIRBranch) instructions.get(instructions.size() - 1)).cond() == LIRCondition.Always : "block with successor must end with unconditional branch";

                // check if block is empty (only label and branch)
                if (instructions.size() == 2) {
                    BlockBegin pred = block.predAt(0);
                    BlockBegin sux = block.suxAt(0);

                    // prevent optimization of two consecutive blocks
                    if (!blockCompleted.get(pred.linearScanNumber()) && !blockCompleted.get(sux.linearScanNumber())) {
                        Util.traceLinearScan(3, " optimizing empty block B%d (pred: B%d, sux: B%d)", block.blockID(), pred.blockID(), sux.blockID());
                        blockCompleted.set(block.linearScanNumber());

                        // directly resolve between pred and sux (without looking at the empty block between)
                        resolveCollectMappings(pred, sux, moveResolver);
                        if (moveResolver.hasMappings()) {
                            moveResolver.setInsertPosition(block.lir(), 0);
                            moveResolver.resolveAndAppendMoves();
                        }
                    }
                }
            }
        }

        for (i = 0; i < numBlocks; i++) {
            if (!blockCompleted.get(i)) {
                BlockBegin fromBlock = blockAt(i);
                alreadyResolved.setFrom(blockCompleted);

                int numSux = fromBlock.numberOfSux();
                for (int s = 0; s < numSux; s++) {
                    BlockBegin toBlock = fromBlock.suxAt(s);

                    // check for duplicate edges between the same blocks (can happen with switch blocks)
                    if (!alreadyResolved.get(toBlock.linearScanNumber())) {
                        Util.traceLinearScan(3, " processing edge between B%d and B%d", fromBlock.blockID(), toBlock.blockID());
                        alreadyResolved.set(toBlock.linearScanNumber());

                        // collect all intervals that have been split between fromBlock and toBlock
                        resolveCollectMappings(fromBlock, toBlock, moveResolver);
                        if (moveResolver.hasMappings()) {
                            resolveFindInsertPos(fromBlock, toBlock, moveResolver);
                            moveResolver.resolveAndAppendMoves();
                        }
                    }
                }
            }
        }
    }

    void resolveExceptionEntry(BlockBegin block, int regNum, MoveResolver moveResolver) {
        if (intervalAt(regNum) == null) {
            // if a phi function is never used, no interval is created . ignore this
            return;
        }

        Interval interval = intervalAtBlockBegin(block, regNum);
        int reg = interval.assignedReg();
        int regHi = interval.assignedRegHi();

        if ((reg < nofRegs && interval.alwaysInMemory()) || (useFpuStackAllocation() && isFpu(reg))) {
            // the interval is split to get a short range that is located on the stack
            // in the following two cases:
            // * the interval started in memory (e.g. method parameter), but is currently in a register
            // this is an optimization for exception handling that reduces the number of moves that
            // are necessary for resolving the states when an exception uses this exception handler
            // * the interval would be on the fpu stack at the begin of the exception handler
            // this is not allowed because of the complicated fpu stack handling on Intel

            // range that will be spilled to memory
            int fromOpId = block.firstLirInstructionId();
            int toOpId = fromOpId + 1; // short live range of length 1
            assert interval.from() <= fromOpId && interval.to() >= toOpId : "no split allowed between exception entry and first instruction";

            if (interval.from() != fromOpId) {
                // the part before fromOpId is unchanged
                interval = interval.split(fromOpId);
                interval.assignReg(reg, regHi);
                appendInterval(interval);
            }
            assert interval.from() == fromOpId : "must be true now";

            Interval spilledPart = interval;
            if (interval.to() != toOpId) {
                // the part after toOpId is unchanged
                spilledPart = interval.splitFromStart(toOpId);
                appendInterval(spilledPart);
                moveResolver.addMapping(spilledPart, interval);
            }
            assignSpillSlot(spilledPart);

            assert spilledPart.from() == fromOpId && spilledPart.to() == toOpId : "just checking";
        }
    }

    void resolveExceptionEntry(BlockBegin block, MoveResolver moveResolver) {
        assert block.checkBlockFlag(BlockBegin.BlockFlag.ExceptionEntry) : "should not call otherwise";
        assert moveResolver.checkEmpty();

        // visit all registers where the liveIn bit is set
        int size = liveSetSize();
        for (int r = block.liveIn().getNextOneOffset(0, size); r < size; r = block.liveIn().getNextOneOffset(r + 1, size)) {
            resolveExceptionEntry(block, r, moveResolver);
        }

        // the liveIn bits are not set for phi functions of the xhandler entry, so iterate them separately
        for (Phi phi : block.phis()) {
            resolveExceptionEntry(block, phi.operand().vregNumber(), moveResolver);
        }

        if (moveResolver.hasMappings()) {
            // insert moves after first instruction
            moveResolver.setInsertPosition(block.lir(), 1);
            moveResolver.resolveAndAppendMoves();
        }
    }

    void resolveExceptionEdge(ExceptionHandler handler, int throwingOpId, int regNum, Phi phi, MoveResolver moveResolver) {
        if (intervalAt(regNum) == null) {
            // if a phi function is never used, no interval is created . ignore this
            return;
        }

        // the computation of toInterval is equal to resolveCollectMappings,
        // but fromInterval is more complicated because of phi functions
        BlockBegin toBlock = handler.entryBlock();
        Interval toInterval = intervalAtBlockBegin(toBlock, regNum);

        if (phi != null) {
            // phi function of the exception entry block
            // no moves are created for this phi function in the LIRGenerator, so the
            // interval at the throwing instruction must be searched using the operands
            // of the phi function
            Instruction fromValue = phi.operandAt(handler.phiOperand());

            // with phi functions it can happen that the same fromValue is used in
            // multiple mappings, so notify move-resolver that this is allowed
            moveResolver.setMultipleReadsAllowed();

            Constant con = null;
            if (fromValue instanceof Constant) {
                con = (Constant) fromValue;
            }
            if (con != null && !con.isPinned()) {
                // unpinned constants may have no register, so add mapping from constant to interval
                moveResolver.addMapping(LIROperandFactory.valueType(con.type()), toInterval);
            } else {
                // search split child at the throwing opId
                Interval fromInterval = intervalAtOpId(fromValue.operand().vregNumber(), throwingOpId);
                moveResolver.addMapping(fromInterval, toInterval);
            }

        } else {
            // no phi function, so use regNum also for fromInterval
            // search split child at the throwing opId
            Interval fromInterval = intervalAtOpId(regNum, throwingOpId);
            if (fromInterval != toInterval) {
                // optimization to reduce number of moves: when toInterval is on stack and
                // the stack slot is known to be always correct, then no move is necessary
                if (!fromInterval.alwaysInMemory() || fromInterval.canonicalSpillSlot() != toInterval.assignedReg()) {
                    moveResolver.addMapping(fromInterval, toInterval);
                }
            }
        }
    }

    void resolveExceptionEdge(ExceptionHandler handler, int throwingOpId, MoveResolver moveResolver) {
        Util.traceLinearScan(4, "resolving exception handler B%d: throwingOpId=%d", handler.entryBlock().blockID(), throwingOpId);

        assert moveResolver.checkEmpty();
        assert handler.lirOpId() == -1 : "already processed this xhandler";
        handler.setLirOpId(throwingOpId);
        assert handler.entryCode() == null : "code already present";

        // visit all registers where the liveIn bit is set
        BlockBegin block = handler.entryBlock();
        int size = liveSetSize();
        for (int r = block.liveIn().getNextOneOffset(0, size); r < size; r = block.liveIn().getNextOneOffset(r + 1, size)) {
            resolveExceptionEdge(handler, throwingOpId, r, null, moveResolver);
        }

        // the liveIn bits are not set for phi functions of the xhandler entry, so iterate them separately
        for (Phi phi : block.phis()) {
            resolveExceptionEdge(handler, throwingOpId, phi.operand().vregNumber(), phi, moveResolver);
        }
        if (moveResolver.hasMappings()) {
            LIRList entryCode = new LIRList(compilation());
            moveResolver.setInsertPosition(entryCode, 0);
            moveResolver.resolveAndAppendMoves();

            entryCode.jump(handler.entryBlock());
            handler.setEntryCode(entryCode);
        }
    }

    void resolveExceptionHandlers() {
        MoveResolver moveResolver = new MoveResolver(this);
        LIRVisitState visitor = new LIRVisitState();
        int numBlocks = blockCount();

        int i;
        for (i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            if (block.checkBlockFlag(BlockFlag.ExceptionEntry)) {
                resolveExceptionEntry(block, moveResolver);
            }
        }

        for (i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            LIRList ops = block.lir();
            int numOps = ops.length();

            // iterate all instructions of the block. skip the first because it is always a label
            assert visitor.noOperands(ops.at(0)) : "first operation must always be a label";
            for (int j = 1; j < numOps; j++) {
                LIRInstruction op = ops.at(j);
                int opId = op.id();

                if (opId != -1 && hasInfo(opId)) {
                    // visit operation to collect all operands
                    visitor.visit(op);
                    assert visitor.infoCount() > 0 : "should not visit otherwise";

                    List<ExceptionHandler> xhandlers = visitor.allXhandler();
                    int n = xhandlers.size();
                    for (int k = 0; k < n; k++) {
                        resolveExceptionEdge(xhandlers.get(k), opId, moveResolver);
                    }

                } else if (C1XOptions.DetailedAsserts) {
                    visitor.visit(op);
                    assert visitor.allXhandler().size() == 0 : "missed exception handler";
                }
            }
        }
    }

    // * Phase 7: assign register numbers back to LIR
    // (includes computation of debug information and oop maps)

    VMReg vmRegForInterval(Interval interval) {
        VMReg reg = interval.cachedVmReg();
        if (!reg.isValid()) {
            reg = vmRegForOperand(operandForInterval(interval));
            interval.setCachedVmReg(reg);
        }
        assert reg == vmRegForOperand(operandForInterval(interval)) : "wrong cached value";
        return reg;
    }

    VMReg vmRegForOperand(LIROperand opr) {
        assert opr.isOop() : "currently only implemented for oop operands";
        return frameMap().regname(opr);
    }

    LIROperand operandForInterval(Interval interval) {
        LIROperand opr = interval.cachedOpr();
        if (opr.isIllegal()) {
            opr = calcOperandForInterval(interval);
            interval.setCachedOpr(opr);
        }

        assert opr.equals(calcOperandForInterval(interval)) : "wrong cached value";
        return opr;
    }

    LIROperand calcOperandForInterval(Interval interval) {
        int assignedReg = interval.assignedReg();
        BasicType type = interval.type();

        if (assignedReg >= nofRegs) {
            // stack slot
            assert interval.assignedRegHi() == getAnyreg() : "must not have hi register";
            return LIROperandFactory.stack(assignedReg - nofRegs, type);

        } else {
            // register
            switch (type) {
                case Object: {
                    assert isCpu(assignedReg) : "no cpu register";
                    assert interval.assignedRegHi() == getAnyreg() : "must not have hi register";
                    return LIROperandFactory.singleCpuOop(toRegister(assignedReg));
                }

                case Int: {
                    assert isCpu(assignedReg) : "no cpu register";
                    assert interval.assignedRegHi() == getAnyreg() : "must not have hi register";
                    return LIROperandFactory.singleCpu(toRegister(assignedReg));
                }

                case Long: {
                    int assignedRegHi = interval.assignedRegHi();
                    assert isCpu(assignedReg) : "no cpu register";
                    assert numPhysicalRegs(BasicType.Long) == 1 || (isCpu(assignedRegHi)) : "no cpu register";

                    assert assignedReg != assignedRegHi : "invalid allocation";
                    assert numPhysicalRegs(BasicType.Long) == 1 || assignedReg < assignedRegHi : "register numbers must be sorted (ensure that e.g. a move from eax,ebx to ebx,eax can not occur)";
                    assert (assignedRegHi != getAnyreg()) ^ (numPhysicalRegs(BasicType.Long) == 1) : "must be match";
                    if (requiresAdjacentRegs(BasicType.Long)) {
                        assert assignedReg % 2 == 0 && assignedReg + 1 == assignedRegHi : "must be sequential and even";
                    }

                    if (compilation.target.arch.is64bit()) {
                        return LIROperandFactory.doubleCpu(toRegister(assignedReg), toRegister(assignedReg));
                    } else {
                        if (compilation.target.arch.isSPARC()) {
                            return LIROperandFactory.doubleCpu(toRegister(assignedRegHi), toRegister(assignedReg));
                        } else {
                            return LIROperandFactory.doubleCpu(toRegister(assignedReg), toRegister(assignedRegHi));
                        }
                    }
                }

                case Float: {
                    if (C1XOptions.SSEVersion >= 1 && compilation.target.arch.isX86()) {
                        assert isXmm(assignedReg) : "no xmm register";
                        assert interval.assignedRegHi() == getAnyreg() : "must not have hi register";
                        return LIROperandFactory.singleXmmX86(toRegister(assignedReg));
                    }

                    assert isFpu(assignedReg) : "no fpu register";
                    assert interval.assignedRegHi() == getAnyreg() : "must not have hi register";
                    return LIROperandFactory.singleFpu(toRegister(assignedReg));
                }

                case Double: {
                    if (C1XOptions.SSEVersion >= 2 && compilation.target.arch.isX86()) {
                        assert isXmm(assignedReg) : "no xmm register";
                        assert interval.assignedRegHi() == getAnyreg() : "must not have hi register (double xmm values are stored in one register)";
                        return LIROperandFactory.doubleXmmX86(toRegister(assignedReg));
                    }

                    LIROperand result;
                    if (compilation.target.arch.isSPARC()) {
                        assert isFpu(assignedReg) : "no fpu register";
                        assert isFpu(interval.assignedRegHi()) : "no fpu register";
                        assert assignedReg % 2 == 0 && assignedReg + 1 == interval.assignedRegHi() : "must be sequential and even";
                        result = LIROperandFactory.doubleFpuSparc(toRegister(interval.assignedRegHi()), toRegister(assignedReg));
                    } else {
                        assert isFpu(assignedReg) : "no fpu register";
                        assert interval.assignedRegHi() == getAnyreg() : "must not have hi register (double fpu values are stored in one register on Intel)";
                        result = LIROperandFactory.doubleFpuX86(toRegister(assignedReg));
                    }
                    return result;
                }

                default: {
                    Util.shouldNotReachHere();
                    return LIROperandFactory.illegalOperand;
                }
            }
        }
    }

    boolean isFpu(int assignedReg) {
        return assignedReg >= 0 && assignedReg < registerMapping.length && registerMapping[assignedReg] != null && this.registerMapping[assignedReg].isFpu();
    }

    boolean isXmm(int assignedReg) {
        return assignedReg >= 0 && assignedReg < registerMapping.length && registerMapping[assignedReg] != null && this.registerMapping[assignedReg].isXmm();
    }

    Register toRegister(int assignedReg) {
        final Register result = registerMapping[assignedReg];
        assert result != null : "register not found!";
        return result;
    }

    boolean isCpu(int assignedReg) {

        return assignedReg >= 0 && assignedReg < registerMapping.length && registerMapping[assignedReg] != null && registerMapping[assignedReg].isCpu();
    }

    LIROperand canonicalSpillOpr(Interval interval) {
        assert interval.canonicalSpillSlot() >= nofRegs : "canonical spill slot not set";
        return LIROperandFactory.stack(interval.canonicalSpillSlot() - nofRegs, interval.type());
    }

    LIROperand colorLirOpr(LIROperand opr, int opId, LIRVisitState.OperandMode mode) {
        assert opr.isVirtual() : "should not call this otherwise";

        Interval interval = intervalAt(opr.vregNumber());
        assert interval != null : "interval must exist";

        if (opId != -1) {
            if (C1XOptions.DetailedAsserts) {
                BlockBegin block = blockOfOpWithId(opId);
                if (block.numberOfSux() <= 1 && opId == block.lastLirInstructionId()) {
                    // check if spill moves could have been appended at the end of this block, but
                    // before the branch instruction. So the split child information for this branch would
                    // be incorrect.
                    LIRInstruction instr = block.lir().instructionsList().get(block.lir().instructionsList().size() - 1);
                    if (instr instanceof LIRBranch) {
                        LIRBranch branch = (LIRBranch) instr;
                        if (block.liveOut().get(opr.vregNumber())) {
                            assert branch.cond() == LIRCondition.Always : "block does not end with an unconditional jump";
                            assert false : "can't get split child for the last branch of a block because the information would be incorrect (moves are inserted before the branch in resolveDataFlow)";
                        }
                    }
                }
            }

            // operands are not changed when an interval is split during allocation,
            // so search the right interval here
            interval = splitChildAtOpId(interval, opId, mode);
        }

        LIROperand res = operandForInterval(interval);

        if (compilation.target.arch.isX86()) {
            // new semantic for isLastUse: not only set on definite end of interval,
            // but also before hole
            // This may still miss some cases (e.g. for dead values), but it is not necessary that the
            // last use information is completely correct
            // information is only needed for fpu stack allocation
            if (res.isFpuRegister()) {
                if (opr.isLastUse() || opId == interval.to() || (opId != -1 && interval.hasHoleBetween(opId, opId + 1))) {
                    assert opId == -1 || !isBlockBegin(opId) : "holes at begin of block may also result from control flow";
                    res = res.makeLastUse();
                }
            }
        }

        assert !gen().isVregFlagSet(opr.vregNumber(), LIRGenerator.VregFlag.CalleeSaved) || !frameMap.isCallerSaveRegister(res) : "bad allocation";

        return res;
    }

    // some methods used to check correctness of debug information

    void assertNoRegisterValuesScope(List<ScopeValue> values) {
        if (values == null) {
            return;
        }

        for (int i = 0; i < values.size(); i++) {
            ScopeValue value = values.get(i);

            if (value.isLocation()) {
                Location location = ((LocationValue) value).location();
                assert location.where() == Location.Where.OnStack : "value is in register";
            }
        }
    }

    void assertNoRegisterValuesMonitor(List<MonitorValue> values) {
        if (values == null) {
            return;
        }

        for (int i = 0; i < values.size(); i++) {
            MonitorValue value = values.get(i);

            if (value.owner().isLocation()) {
                Location location = ((LocationValue) value.owner()).location();
                assert location.where() == Location.Where.OnStack : "owner is in register";
            }
            assert value.basicLock().where() == Location.Where.OnStack : "basicLock is in register";
        }
    }

    void assertEqual(Location l1, Location l2) {
        assert l1.where() == l2.where() && l1.type() == l2.type() && l1.offset() == l2.offset() : "";
    }

    void assertEqual(ScopeValue v1, ScopeValue v2) {
        if (v1.isLocation()) {
            assert v2.isLocation() : "";
            assertEqual(((LocationValue) v1).location(), ((LocationValue) v2).location());
        } else if (v1.isConstantInt()) {
            assert v2.isConstantInt() : "";
            assert ((ConstantIntValue) v1).value() == ((ConstantIntValue) v2).value() : "";
        } else if (v1.isConstantDouble()) {
            assert v2.isConstantDouble() : "";
            assert ((ConstantDoubleValue) v1).value() == ((ConstantDoubleValue) v2).value() : "";
        } else if (v1.isConstantLong()) {
            assert v2.isConstantLong() : "";
            assert ((ConstantLongValue) v1).value() == ((ConstantLongValue) v2).value() : "";
        } else if (v1.isConstantOop()) {
            assert v2.isConstantOop() : "";
            assert ((ConstantOopWriteValue) v1).value() == ((ConstantOopWriteValue) v2).value() : "";
        } else {
            Util.shouldNotReachHere();
        }
    }

    void assertEqual(MonitorValue m1, MonitorValue m2) {
        assertEqual(m1.owner(), m2.owner());
        assertEqual(m1.basicLock(), m2.basicLock());
    }

    void assertEqual(IRScopeDebugInfo d1, IRScopeDebugInfo d2) {
        assert d1.scope() == d2.scope() : "not equal";
        assert d1.bci() == d2.bci() : "not equal";

        if (d1.locals() != null) {
            assert d1.locals() != null && d2.locals() != null : "not equal";
            assert d1.locals().size() == d2.locals().size() : "not equal";
            for (int i = 0; i < d1.locals().size(); i++) {
                assertEqual(d1.locals().get(i), d2.locals().get(i));
            }
        } else {
            assert d1.locals() == null && d2.locals() == null : "not equal";
        }

        if (d1.expressions() != null) {
            assert d1.expressions() != null && d2.expressions() != null : "not equal";
            assert d1.expressions().size() == d2.expressions().size() : "not equal";
            for (int i = 0; i < d1.expressions().size(); i++) {
                assertEqual(d1.expressions().get(i), d2.expressions().get(i));
            }
        } else {
            assert d1.expressions() == null && d2.expressions() == null : "not equal";
        }

        if (d1.monitors() != null) {
            assert d1.monitors() != null && d2.monitors() != null : "not equal";
            assert d1.monitors().size() == d2.monitors().size() : "not equal";
            for (int i = 0; i < d1.monitors().size(); i++) {
                assertEqual(d1.monitors().get(i), d2.monitors().get(i));
            }
        } else {
            assert d1.monitors() == null && d2.monitors() == null : "not equal";
        }

        if (d1.caller() != null) {
            assert d1.caller() != null && d2.caller() != null : "not equal";
            assertEqual(d1.caller(), d2.caller());
        } else {
            assert d1.caller() == null && d2.caller() == null : "not equal";
        }
    }

    boolean checkStackDepth(CodeEmitInfo info, int stackEnd) {
        if (info.bci() != C1XCompilation.MethodCompilation.SynchronizationEntryBCI.value && !info.scope().method.isNative()) {
            int code = info.scope().method.javaCodeAtBci(info.bci());
            switch (code) {
                case Bytecodes.IFNULL: // fall through
                case Bytecodes.IFNONNULL: // fall through
                case Bytecodes.IFEQ: // fall through
                case Bytecodes.IFNE: // fall through
                case Bytecodes.IFLT: // fall through
                case Bytecodes.IFGE: // fall through
                case Bytecodes.IFGT: // fall through
                case Bytecodes.IFLE: // fall through
                case Bytecodes.IF_ICMPEQ: // fall through
                case Bytecodes.IF_ICMPNE: // fall through
                case Bytecodes.IF_ICMPLT: // fall through
                case Bytecodes.IF_ICMPGE: // fall through
                case Bytecodes.IF_ICMPGT: // fall through
                case Bytecodes.IF_ICMPLE: // fall through
                case Bytecodes.IF_ACMPEQ: // fall through
                case Bytecodes.IF_ACMPNE:
                    // TODO: Check if this assertion can be enabled
                    // assert stackEnd >= -Bytecodes.depth(code) :
                    // "must have non-empty expression stack at if bytecode";
                    break;
            }
        }

        return true;
    }

    IntervalWalker initComputeOopMaps() {
        // setup lists of potential oops for walking
        Interval oopIntervals;
        Interval nonOopIntervals;

        Interval[] result = createUnhandledLists(isOopInterval, null);
        oopIntervals = result[0];
        nonOopIntervals = result[1];

        // intervals that have no oops inside need not to be processed
        // to ensure a walking until the last instruction id, add a dummy interval
        // with a high operation id
        nonOopIntervals = new Interval(getAnyreg());
        nonOopIntervals.addRange(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1);

        return new IntervalWalker(this, oopIntervals, nonOopIntervals);
    }

    OopMap computeOopMap(IntervalWalker iw, LIRInstruction op, CodeEmitInfo info, boolean isCallSite) {
        Util.traceLinearScan(3, "creating oop map at opId %d", op.id());

        // walk before the current operation . intervals that start at
        // the operation (= output operands of the operation) are not
        // included in the oop map
        iw.walkBefore(op.id());

        int frameSize = frameMap().framesize();
        int argCount = frameMap().oopMapArgCount();
        OopMap map = new OopMap(frameSize, argCount);

        // Check if this is a patch site.
        boolean isPatchInfo = false;
        if (op.code() == LIROpcode.Move) {
            assert !isCallSite : "move must not be a call site";
            LIROp1 move = (LIROp1) op;

            isPatchInfo = move.patchCode() != LIRPatchCode.PatchNone;
        }

        // Iterate through active intervals
        for (Interval interval = iw.activeFirst(IntervalKind.fixedKind); interval != Interval.end(); interval = interval.next()) {
            int assignedReg = interval.assignedReg();

            assert interval.currentFrom() <= op.id() && op.id() <= interval.currentTo() : "interval should not be active otherwise";
            assert interval.assignedRegHi() == getAnyreg() : "oop must be single word";
            assert interval.regNum() >= Register.vregBase : "fixed interval found";

            // Check if this range covers the instruction. Intervals that
            // start or end at the current operation are not included in the
            // oop map, except in the case of patching moves. For patching
            // moves, any intervals which end at this instruction are included
            // in the oop map since we may safepoint while doing the patch
            // before we've consumed the inputs.
            if (isPatchInfo || op.id() < interval.currentTo()) {

                // caller-save registers must not be included into oop-maps at calls
                assert !isCallSite || assignedReg >= nofRegs || !isCallerSave(assignedReg) : "interval is in a caller-save register at a call . register will be overwritten";

                VMReg name = vmRegForInterval(interval);
                map.setOop(name);

                // Spill optimization: when the stack value is guaranteed to be always correct,
                // then it must be added to the oop map even if the interval is currently in a register
                if (interval.alwaysInMemory() && op.id() > interval.spillDefinitionPos() && interval.assignedReg() != interval.canonicalSpillSlot()) {
                    assert interval.spillDefinitionPos() > 0 : "position not set correctly";
                    assert interval.canonicalSpillSlot() >= nofRegs : "no spill slot assigned";
                    assert interval.assignedReg() < nofRegs : "interval is on stack :  so stack slot is registered twice";

                    map.setOop(frameMap().slotRegname(interval.canonicalSpillSlot() - nofRegs));
                }
            }
        }

        // add oops from lock stack
        assert info.stack() != null : "CodeEmitInfo must always have a stack";
        int locksCount = info.stack().locksSize();
        for (int i = 0; i < locksCount; i++) {
            map.setOop(frameMap().monitorObjectRegname(i));
        }

        return map;
    }

    private boolean isCallerSave(int assignedReg) {
        // TODO Auto-generated method stub
        return false;
    }

    void computeOopMap(IntervalWalker iw, LIRVisitState visitor, LIRInstruction op) {
        assert visitor.infoCount() > 0 : "no oop map needed";

        // compute oopMap only for first CodeEmitInfo
        // because it is (in most cases) equal for all other infos of the same operation
        CodeEmitInfo firstInfo = visitor.infoAt(0);
        OopMap firstOopMap = computeOopMap(iw, op, firstInfo, visitor.hasCall());

        for (int i = 0; i < visitor.infoCount(); i++) {
            CodeEmitInfo info = visitor.infoAt(i);
            OopMap oopMap = firstOopMap;

            if (info.stack().locksSize() != firstInfo.stack().locksSize()) {
                // this info has a different number of locks then the precomputed oop map
                // (possible for lock and unlock instructions) . compute oop map with
                // correct lock information
                oopMap = computeOopMap(iw, op, info, visitor.hasCall());
            }

            if (info.oopMap == null) {
                info.oopMap = oopMap;
            } else {
                // a CodeEmitInfo can not be shared between different LIR-instructions
                // because interval splitting can occur anywhere between two instructions
                // and so the oop maps must be different
                // . check if the already set oopMap is exactly the one calculated for this operation
                assert info.oopMap == oopMap : "same CodeEmitInfo used for multiple LIR instructions";
            }
        }
    }

    // frequently used constants
    ConstantOopWriteValue oopNullScopeValue = new ConstantOopWriteValue(null);
    ConstantIntValue intM1ScopeValue = new ConstantIntValue(-1);
    ConstantIntValue int0ScopeValue = new ConstantIntValue(0);
    ConstantIntValue int1ScopeValue = new ConstantIntValue(1);
    ConstantIntValue int2ScopeValue = new ConstantIntValue(2);
    LocationValue illegalValue = new LocationValue(new Location());
    private ScopeValue[] scopeValueCache;
    private FpuStackAllocator fpuStackAllocator;
    int pdFirstFpuReg;
    int pdLastFpuReg;
    int pdFirstCpuReg;
    int pdLastCpuReg;
    int pdFirstByteReg;
    int pdLastByteReg;
    int pdFirstXmmReg;
    int pdLastXmmReg;

    void initComputeDebugInfo() {
        // cache for frequently used scope values
        // (cpu registers and stack slots)
        scopeValueCache = new ScopeValue[(nofCpuRegs + frameMap().argcount() + maxSpills()) * 2];
    }

    MonitorValue locationForMonitorIndex(int monitorIndex) {
        Location[] loc = new Location[1];

        if (!frameMap().locationForMonitorObject(monitorIndex, loc)) {
            bailout("too large frame");
        }
        ScopeValue objectScopeValue = new LocationValue(loc[0]);

        if (!frameMap().locationForMonitorLock(monitorIndex, loc)) {
            bailout("too large frame");
        }
        return new MonitorValue(objectScopeValue, loc[0]);
    }

    LocationValue locationForName(int name, Location.LocationType locType) {
        Location[] loc = new Location[1];
        if (!frameMap().locationsForSlot(name, locType, loc)) {
            bailout("too large frame");
        }
        return new LocationValue(loc[0]);
    }

    int appendScopeValueForConstant(LIROperand opr, List<ScopeValue> scopeValues) {
        assert opr.isConstant() : "should not be called otherwise";

        LIRConstant c = opr.asConstantPtr();
        BasicType t = c.type();
        switch (t) {
            case Object: {
                Object value = c.asJobject();
                if (value == null) {
                    scopeValues.add(oopNullScopeValue);
                } else {
                    scopeValues.add(new ConstantOopWriteValue(c.asJobject()));
                }
                return 1;
            }

            case Int: // fall through
            case Float: {
                int value = c.asIntBits();
                switch (value) {
                    case -1:
                        scopeValues.add(intM1ScopeValue);
                        break;
                    case 0:
                        scopeValues.add(int0ScopeValue);
                        break;
                    case 1:
                        scopeValues.add(int1ScopeValue);
                        break;
                    case 2:
                        scopeValues.add(int2ScopeValue);
                        break;
                    default:
                        scopeValues.add(new ConstantIntValue(c.asIntBits()));
                        break;
                }
                return 1;
            }

            case Long: // fall through
            case Double: {
                if (compilation.target.arch.hiWordOffsetInBytes > compilation.target.arch.loWordOffsetInBytes) {
                    scopeValues.add(new ConstantIntValue(c.asIntHiBits()));
                    scopeValues.add(new ConstantIntValue(c.asIntLoBits()));
                } else {
                    scopeValues.add(new ConstantIntValue(c.asIntLoBits()));
                    scopeValues.add(new ConstantIntValue(c.asIntHiBits()));
                }

                return 2;
            }

            default:
                Util.shouldNotReachHere();
                return -1;
        }
    }

    int appendScopeValueForOperand(LIROperand opr, List<ScopeValue> scopeValues) {
        if (opr.isSingleStack()) {
            int stackIdx = opr.singleStackIx();
            boolean isOop = opr.isOopRegister();
            int cacheIdx = (stackIdx + nofCpuRegs) * 2 + (isOop ? 1 : 0);

            ScopeValue sv = scopeValueCache[cacheIdx];
            if (sv == null) {
                Location.LocationType locType = isOop ? Location.LocationType.Oop : Location.LocationType.Normal;
                sv = locationForName(stackIdx, locType);
                scopeValueCache[cacheIdx] = sv;
            }

            // check if cached value is correct
            assertEqual(sv, locationForName(stackIdx, isOop ? Location.LocationType.Oop : Location.LocationType.Normal));

            scopeValues.add(sv);
            return 1;

        } else if (opr.isSingleCpu()) {
            boolean isOop = opr.isOopRegister();
            int cacheIdx = opr.cpuRegnr() * 2 + (isOop ? 1 : 0);

            ScopeValue sv = scopeValueCache[cacheIdx];
            if (sv == null) {
                Location.LocationType locType = isOop ? Location.LocationType.Oop : Location.LocationType.Normal;
                VMReg rname = frameMap().regname(opr);
                sv = new LocationValue(Location.newRegLoc(locType, rname));
                scopeValueCache[cacheIdx] = sv;
            }

            // check if cached value is correct
            assertEqual(sv, new LocationValue(Location.newRegLoc(isOop ? Location.LocationType.Oop : Location.LocationType.Normal, frameMap().regname(opr))));

            scopeValues.add(sv);
            return 1;

        } else if (opr.isSingleXmm() && compilation.target.arch.isX86()) {
            VMReg rname = opr.asRegister().asVMReg();
            LocationValue sv = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rname));

            scopeValues.add(sv);
            return 1;

        } else if (opr.isSingleFpu()) {

            if (compilation.target.arch.isX86()) {
                // the exact location of fpu stack values is only known
                // during fpu stack allocation, so the stack allocator object
                // must be present
                assert useFpuStackAllocation() : "should not have float stack values without fpu stack allocation (all floats must be SSE2)";
                assert fpuStackAllocator != null : "must be present";
                opr = fpuStackAllocator.toFpuStack(opr);
            }

            Location.LocationType locType = floatSavedAsDouble() ? Location.LocationType.FloatInDbl : Location.LocationType.Normal;
            VMReg rname = frameMap().fpuRegname(opr.fpuRegnr());
            LocationValue sv = new LocationValue(Location.newRegLoc(locType, rname));

            scopeValues.add(sv);
            return 1;

        } else {
            // double-size operands

            ScopeValue first;
            ScopeValue second;

            if (opr.isDoubleStack()) {

                if (compilation.target.arch.is64bit()) {
                    Location[] loc1 = new Location[1];
                    Location.LocationType locType = opr.type() == BasicType.Long ? Location.LocationType.Long : Location.LocationType.Double;
                    if (!frameMap().locationsForSlot(opr.doubleStackIx(), locType, loc1, null)) {
                        bailout("too large frame");
                    }
                    // Does this reverse on x86 vs. sparc?
                    first = new LocationValue(loc1[0]);
                    second = int0ScopeValue;
                } else {
                    Location[] loc1 = new Location[1];
                    Location[] loc2 = new Location[1];
                    if (!frameMap().locationsForSlot(opr.doubleStackIx(), Location.LocationType.Normal, loc1, loc2)) {
                        bailout("too large frame");
                    }
                    first = new LocationValue(loc1[0]);
                    second = new LocationValue(loc2[0]);
                }

            } else if (opr.isDoubleCpu()) {

                if (compilation.target.arch.is64bit()) {
                    VMReg rnameFirst = opr.asRegisterLo().asVMReg();
                    first = new LocationValue(Location.newRegLoc(Location.LocationType.Long, rnameFirst));
                    second = int0ScopeValue;
                } else {
                    VMReg rnameFirst = opr.asRegisterLo().asVMReg();
                    VMReg rnameSecond = opr.asRegisterHi().asVMReg();

                    if (compilation.target.arch.hiWordOffsetInBytes < compilation.target.arch.loWordOffsetInBytes) {
                        // lo/hi and swapped relative to first and second, so swap them
                        VMReg tmp = rnameFirst;
                        rnameFirst = rnameSecond;
                        rnameSecond = tmp;
                    }

                    first = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameFirst));
                    second = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameSecond));
                }

            } else if (opr.isDoubleXmm() && compilation.target.arch.isX86()) {
                assert opr.fpuRegnrLo() == opr.fpuRegnrHi() : "assumed in calculation";
                VMReg rnameFirst = opr.asRegister().asVMReg();
                first = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameFirst));
                // %%% This is probably a waste but we'll keep things as they were for now
                if (true) {
                    VMReg rnameSecond = rnameFirst.next();
                    second = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameSecond));
                }

            } else if (opr.isDoubleFpu()) {
                // On SPARC, fpuRegnrLo/fpuRegnrHi represents the two halves of
                // the double as float registers in the native ordering. On X86,
                // fpuRegnrLo is a FPU stack slot whose VMReg represents
                // the low-order word of the double and fpuRegnrLo + 1 is the
                // name for the other half. *first and *second must represent the
                // least and most significant words, respectively.

                if (compilation.target.arch.isX86()) {
                    // the exact location of fpu stack values is only known
                    // during fpu stack allocation, so the stack allocator object
                    // must be present
                    assert useFpuStackAllocation() : "should not have float stack values without fpu stack allocation (all floats must be SSE2)";
                    assert fpuStackAllocator != null : "must be present";
                    opr = fpuStackAllocator.toFpuStack(opr);

                    assert opr.fpuRegnrLo() == opr.fpuRegnrHi() : "assumed in calculation (only fpuRegnrHi is used)";
                } else if (compilation.target.arch.isSPARC()) {
                    assert opr.fpuRegnrLo() == opr.fpuRegnrHi() + 1 : "assumed in calculation (only fpuRegnrHi is used)";
                }

                VMReg rnameFirst = frameMap().fpuRegname(opr.fpuRegnrHi());

                first = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameFirst));
                // %%% This is probably a waste but we'll keep things as they were for now
                if (true) {
                    VMReg rnameSecond = rnameFirst.next();
                    second = new LocationValue(Location.newRegLoc(Location.LocationType.Normal, rnameSecond));
                }

            } else {
                Util.shouldNotReachHere();
                first = null;
                second = null;
            }

            assert first != null && second != null : "must be set";
            // The convention the interpreter uses is that the second local
            // holds the first raw word of the native double representation.
            // This is actually reasonable, since locals and stack arrays
            // grow downwards in all implementations.
            // (If, on some machine, the interpreter's Java locals or stack
            // were to grow upwards, the embedded doubles would be word-swapped.)
            scopeValues.add(second);
            scopeValues.add(first);
            return 2;
        }
    }

    private boolean floatSavedAsDouble() {
        return compilation.target.arch.isX86();
    }

    int appendScopeValue(int opId, Instruction value, List<ScopeValue> scopeValues) {
        if (value != null) {
            LIROperand opr = value.operand();
            Constant con = null;
            if (value instanceof Constant) {
                con = (Constant) value;
            }

            assert con == null || opr.isVirtual() || opr.isConstant() || opr.isIllegal() : "asumption: Constant instructions have only constant operands (or illegal if constant is optimized away)";
            assert con != null || opr.isVirtual() : "asumption: non-Constant instructions have only virtual operands";

            if (con != null && !con.isPinned() && !opr.isConstant()) {
                // Unpinned constants may have a virtual operand for a part of the lifetime
                // or may be illegal when it was optimized away,
                // so always use a constant operand
                opr = LIROperandFactory.valueType(con.type());
            }
            assert opr.isVirtual() || opr.isConstant() : "other cases not allowed here";

            if (opr.isVirtual()) {
                BlockBegin block = blockOfOpWithId(opId);
                if (block.numberOfSux() == 1 && opId == block.lastLirInstructionId()) {
                    // generating debug information for the last instruction of a block.
                    // if this instruction is a branch, spill moves are inserted before this branch
                    // and so the wrong operand would be returned (spill moves at block boundaries are not
                    // considered in the live ranges of intervals)
                    // Solution: use the first opId of the branch target block instead.
                    final LIRInstruction instr = block.lir().instructionsList().get(block.lir().instructionsList().size() - 1);
                    if (instr instanceof LIRBranch) {
                        if (block.liveOut().get(opr.vregNumber())) {
                            opId = block.suxAt(0).firstLirInstructionId();
                        }
                    }
                }

                // Get current location of operand
                // The operand must be live because debug information is considered when building the intervals
                // if the interval is not live, colorLirOpr will cause an assert on failure opr = colorLirOpr(opr, opId,
                // mode);
                assert !hasCall(opId) || opr.isStack() || !isCallerSave(regNum(opr)) : "can not have caller-save register operands at calls";

                // Append to ScopeValue array
                return appendScopeValueForOperand(opr, scopeValues);

            } else {
                assert value instanceof Constant : "all other instructions have only virtual operands";
                assert opr.isConstant() : "operand must be constant";

                return appendScopeValueForConstant(opr, scopeValues);
            }
        } else {
            // append a dummy value because real value not needed
            scopeValues.add(illegalValue);
            return 1;
        }
    }

    IRScopeDebugInfo computeDebugInfoForScope(int opId, IRScope curScope, ValueStack curState, ValueStack innermostState, int curBci, int stackEnd, int locksEnd) {
        IRScopeDebugInfo callerDebugInfo = null;
        int stackBegin;
        int locksBegin;

        ValueStack callerState = curScope.callerState();
        if (callerState != null) {
            // process recursively to compute outermost scope first
            stackBegin = callerState.stackSize();
            locksBegin = callerState.locksSize();
            callerDebugInfo = computeDebugInfoForScope(opId, curScope.caller, callerState, innermostState, curScope.callerBCI(), stackBegin, locksBegin);
        } else {
            stackBegin = 0;
            locksBegin = 0;
        }

        // initialize these to null.
        // If we don't need deopt info or there are no locals, expressions or monitors,
        // then these get recorded as no information and avoids the allocation of 0 length arrays.
        List<ScopeValue> locals = null;
        List<ScopeValue> expressions = null;
        List<MonitorValue> monitors = null;

        // describe local variable values
        int nofLocals = curScope.method.maxLocals();
        if (nofLocals > 0) {
            locals = new ArrayList<ScopeValue>(nofLocals);

            int pos = 0;
            while (pos < nofLocals) {
                assert pos < curState.localsSize() : "why not?";

                Instruction local = curState.localAt(pos);
                pos += appendScopeValue(opId, local, locals);

                assert locals.size() == pos : "must match";
            }
            assert locals.size() == curScope.method.maxLocals() : "wrong number of locals";
            assert locals.size() == curState.localsSize() : "wrong number of locals";
        }

        // describe expression stack
        //
        // When we inline methods containing exception handlers, the
        // "lockStacks" are changed to preserve expression stack values
        // in caller scopes when exception handlers are present. This
        // can cause callee stacks to be smaller than caller stacks.
        if (stackEnd > innermostState.stackSize()) {
            stackEnd = innermostState.stackSize();
        }

        int nofStack = stackEnd - stackBegin;
        if (nofStack > 0) {
            expressions = new ArrayList<ScopeValue>(nofStack);

            int pos = stackBegin;
            while (pos < stackEnd) {
                Instruction expression = innermostState.stackAt(pos);
                appendScopeValue(opId, expression, expressions);

                assert expressions.size() + stackBegin == pos : "must match";
            }
        }

        // describe monitors
        assert locksBegin <= locksEnd : "error in scope iteration";
        int nofLocks = locksEnd - locksBegin;
        if (nofLocks > 0) {
            monitors = new ArrayList<MonitorValue>(nofLocks);
            for (int i = locksBegin; i < locksEnd; i++) {
                monitors.add(locationForMonitorIndex(i));
            }
        }

        return new IRScopeDebugInfo(curScope, curBci, locals, expressions, monitors, callerDebugInfo);
    }

    void computeDebugInfo(CodeEmitInfo info, int opId) {
        if (!compilation().needsDebugInformation()) {
            return;
        }
        Util.traceLinearScan(3, "creating debug information at opId %d", opId);

        IRScope innermostScope = info.scope();
        ValueStack innermostState = info.stack();

        assert innermostScope != null && innermostState != null : "why is it missing?";

        int stackEnd = innermostState.stackSize();
        int locksEnd = innermostState.locksSize();

        assert checkStackDepth(info, stackEnd);

        if (info.scopeDebugInfo == null) {
            // compute debug information
            info.scopeDebugInfo = computeDebugInfoForScope(opId, innermostScope, innermostState, innermostState, info.bci(), stackEnd, locksEnd);
        } else {
            // debug information already set. Check that it is correct from the current point of view
            assertEqual(info.scopeDebugInfo, computeDebugInfoForScope(opId, innermostScope, innermostState, innermostState, info.bci(), stackEnd, locksEnd));
        }
    }

    void assignRegNum(List<LIRInstruction> instructions, IntervalWalker iw) {
        LIRVisitState visitor = new LIRVisitState();
        int numInst = instructions.size();
        boolean hasDead = false;

        for (int j = 0; j < numInst; j++) {
            LIRInstruction op = instructions.get(j);
            if (op == null) { // this can happen when spill-moves are removed in eliminateSpillMoves
                hasDead = true;
                continue;
            }
            int opId = op.id();

            // visit instruction to get list of operands
            visitor.visit(op);

            // iterate all modes of the visitor and process all virtual operands
            for (LIRVisitState.OperandMode mode : LIRVisitState.OperandMode.values()) {
                int n = visitor.oprCount(mode);
                for (int k = 0; k < n; k++) {
                    LIROperand opr = visitor.oprAt(mode, k);
                    if (opr.isVirtualRegister()) {
                        opr.assignPhysicalRegister(colorLirOpr(opr, opId, mode));
                    }
                }
            }

            if (visitor.infoCount() > 0) {
                // exception handling
                if (compilation().hasExceptionHandlers()) {
                    List<ExceptionHandler> xhandlers = visitor.allXhandler();
                    int n = xhandlers.size();
                    for (int k = 0; k < n; k++) {
                        ExceptionHandler handler = xhandlers.get(k);
                        if (handler.entryCode() != null) {
                            assignRegNum(handler.entryCode().instructionsList(), null);
                        }
                    }
                } else {
                    assert visitor.allXhandler().size() == 0 : "missed exception handler";
                }

                // compute oop map
                assert iw != null : "needed for computeOopMap";
                computeOopMap(iw, visitor, op);

                // compute debug information
                if (!useFpuStackAllocation()) {
                    // compute debug information if fpu stack allocation is not needed.
                    // when fpu stack allocation is needed, the debug information can not
                    // be computed here because the exact location of fpu operands is not known
                    // . debug information is created inside the fpu stack allocator
                    int n = visitor.infoCount();
                    for (int k = 0; k < n; k++) {
                        computeDebugInfo(visitor.infoAt(k), opId);
                    }
                }
            }

            // make sure we haven't made the op invalid.
            assert op.verify();

            // remove useless moves
            if (op.code() == LIROpcode.Move) {
                LIROp1 move = (LIROp1) op;
                LIROperand src = move.inOpr();
                LIROperand dst = move.resultOpr();
                if (dst == src || !dst.isPointer() && !src.isPointer() && src.isSameRegister(dst)) {
                    instructions.set(j, null);
                    hasDead = true;
                }
            }
        }

        if (hasDead) {
            // iterate all instructions of the block and remove all null-values.
            int insertPoint = 0;
            for (int j = 0; j < numInst; j++) {
                LIRInstruction op = instructions.get(j);
                if (op != null) {
                    if (insertPoint != j) {
                        instructions.set(insertPoint, op);
                    }
                    insertPoint++;
                }
            }
            Util.truncate(instructions, insertPoint);
        }
    }

    private boolean useFpuStackAllocation() {
        // TODO Auto-generated method stub
        return false;
    }

    void assignRegNum() {
        // TIMELINEARSCAN(timerAssignRegNum);

        initComputeDebugInfo();
        IntervalWalker iw = initComputeOopMaps();

        int numBlocks = blockCount();
        for (int i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            assignRegNum(block.lir().instructionsList(), iw);
        }
    }

    @Override
    public void allocate() {
        // NOTPRODUCT(totalTimer.beginMethod());

        numberInstructions();

        printLir(1, "Before X86Register Allocation", true);

        computeLocalLiveSets();
        computeGlobalLiveSets();

        buildIntervals();
        sortIntervalsBeforeAllocation();

        printIntervals("Before X86Register Allocation");
        // TODO: Compute stats
        // LinearScanStatistic.compute(this, statBeforeAlloc);

        allocateRegisters();

        resolveDataFlow();
        if (compilation().hasExceptionHandlers()) {
            resolveExceptionHandlers();
        }
        // fill in number of spill slots into frameMap
        propagateSpillSlots();

        printIntervals("After X86Register Allocation");
        printLir(2, "LIR after register allocation:", true);

        sortIntervalsAfterAllocation();

        assert verify();

        eliminateSpillMoves();
        assignRegNum();

        printLir(2, "LIR after assignment of register numbers:", true);

        // TODO: Check if we want to do statistics!
        // LinearScanStatistic.compute(this, statAfterAsign);

        if (useFpuStackAllocation()) {
            allocateFpuStack(); // Only has effect on Intel
            printLir(2, "LIR after FPU stack allocation:", true);
        }
        EdgeMoveOptimizer.optimize(ir().linearScanOrder());
        ControlFlowOptimizer.optimize(ir().linearScanOrder());
        // check that cfg is still correct after optimizations
        assert ir().verify();
        printLir(1, "Before Code Generation", false);
        // NOTPRODUCT(LinearScanStatistic.compute(this, statFinal));
        // NOTPRODUCT(totalTimer.endMethod(this));
    }

    // * Printing functions

    private void allocateFpuStack() {
        Util.unimplemented();
    }

    void printTimers(double total) {
        // totalTimer.print(total);
    }

    void printStatistics() {
        // TODO: Gather & print stats
    }

    void printIntervals(String label) {
        if (C1XOptions.TraceLinearScanLevel >= 1) {
            int i;
            TTY.cr();
            TTY.println("%s", label);

            for (i = 0; i < intervalCount(); i++) {
                Interval interval = intervalAt(i);
                if (interval != null) {
                    interval.print(TTY.out, this);
                }
            }

            TTY.cr();
            TTY.println("--- Basic Blocks ---");
            for (i = 0; i < blockCount(); i++) {
                BlockBegin block = blockAt(i);
                TTY.print("B%d [%d, %d, %d, %d] ", block.blockID(), block.firstLirInstructionId(), block.lastLirInstructionId(), block.loopIndex(), block.loopDepth());
            }
            TTY.cr();
            TTY.cr();
        }

        if (C1XOptions.PrintCFGToFile) {
            compilation.cfgPrinter().printIntervals(this, intervals, label);
        }
    }

    void printLir(int level, String label, boolean hirValid) {
        if (C1XOptions.TraceLinearScanLevel >= level) {
            TTY.cr();
            TTY.println("%s", label);
            LIRList.printLIR(ir().linearScanOrder());
            TTY.cr();
        }

        if (level == 1 && C1XOptions.PrintCFGToFile) {
            compilation.cfgPrinter().printCFG(compilation().hir().startBlock, label, hirValid, true);
        }
    }

    // * verification functions for allocation
    // (check that all intervals have a correct register and that no registers are overwritten)

    boolean verify() {
        Util.traceLinearScan(2, " verifying intervals *");
        verifyIntervals();

        Util.traceLinearScan(2, " verifying that no oops are in fixed intervals *");
        verifyNoOopsInFixedIntervals();

        Util.traceLinearScan(2, " verifying that unpinned constants are not alive across block boundaries");
        verifyConstants();

        Util.traceLinearScan(2, " verifying register allocation *");
        verifyRegisters();

        Util.traceLinearScan(2, " no errors found *");

        return true;
    }

    private void verifyRegisters() {
        RegisterVerifier verifier = new RegisterVerifier(this);
        verifier.verify(blockAt(0));
    }

    void verifyIntervals() {
        int len = intervalCount();
        boolean hasError = false;

        for (int i = 0; i < len; i++) {
            Interval i1 = intervalAt(i);
            if (i1 == null) {
                continue;
            }

            i1.checkSplitChildren();

            if (i1.regNum() != i) {
                TTY.println("Interval %d is on position %d in list", i1.regNum(), i);
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            if (i1.regNum() >= Register.vregBase && i1.type() == BasicType.Illegal) {
                TTY.println("Interval %d has no type assigned", i1.regNum());
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            if (i1.assignedReg() == getAnyreg()) {
                TTY.println("Interval %d has no register assigned", i1.regNum());
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            if (i1.assignedReg() == i1.assignedRegHi()) {
                TTY.println("Interval %d: low and high register equal", i1.regNum());
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            if (!isProcessedRegNum(i1.assignedReg())) {
                TTY.println("Can not have an Interval for an ignored register");
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            if (i1.first() == Range.end()) {
                TTY.println("Interval %d has no Range", i1.regNum());
                i1.print(TTY.out, this);
                TTY.cr();
                hasError = true;
            }

            for (Range r = i1.first(); r != Range.end(); r = r.next()) {
                if (r.from() >= r.to()) {
                    TTY.println("Interval %d has zero length range", i1.regNum());
                    i1.print(TTY.out, this);
                    TTY.cr();
                    hasError = true;
                }
            }

            for (int j = i + 1; j < len; j++) {
                Interval i2 = intervalAt(j);
                if (i2 == null) {
                    continue;
                }

                // special intervals that are created in MoveResolver
                // . ignore them because the range information has no meaning there
                if (i1.from() == 1 && i1.to() == 2) {
                    continue;
                }
                if (i2.from() == 1 && i2.to() == 2) {
                    continue;
                }

                int r1 = i1.assignedReg();
                int r1Hi = i1.assignedRegHi();
                int r2 = i2.assignedReg();
                int r2Hi = i2.assignedRegHi();
                if (i1.intersects(i2) && (r1 == r2 || r1 == r2Hi || (r1Hi != getAnyreg() && (r1Hi == r2 || r1Hi == r2Hi)))) {
                    TTY.println("Intervals %d and %d overlap and have the same register assigned", i1.regNum(), i2.regNum());
                    i1.print(TTY.out, this);
                    TTY.cr();
                    i2.print(TTY.out, this);
                    TTY.cr();
                    hasError = true;
                }
            }
        }

        assert hasError == false : "register allocation invalid";
    }

    void verifyNoOopsInFixedIntervals() {
        Interval fixedIntervals;
        Interval otherIntervals;
        Interval[] result = createUnhandledLists(isPrecoloredCpuInterval, null);
        fixedIntervals = result[0];
        otherIntervals = result[1];
        // to ensure a walking until the last instruction id, add a dummy interval
        // with a high operation id
        otherIntervals = new Interval(getAnyreg());
        otherIntervals.addRange(Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1);
        IntervalWalker iw = new IntervalWalker(this, fixedIntervals, otherIntervals);

        LIRVisitState visitor = new LIRVisitState();
        for (int i = 0; i < blockCount(); i++) {
            BlockBegin block = blockAt(i);

            List<LIRInstruction> instructions = block.lir().instructionsList();

            for (int j = 0; j < instructions.size(); j++) {
                LIRInstruction op = instructions.get(j);
                int opId = op.id();

                visitor.visit(op);

                if (visitor.infoCount() > 0) {
                    iw.walkBefore(op.id());
                    boolean checkLive = true;
                    if (op.code() == LIROpcode.Move) {
                        LIROp1 move = (LIROp1) op;
                        checkLive = (move.patchCode() == LIRPatchCode.PatchNone);
                    }
                    LIRBranch branch = null;
                    if (op instanceof LIRBranch) {
                        branch = (LIRBranch) op;
                    }
                    if (branch != null && branch.stub() != null && branch.stub().isExceptionThrowStub()) {
                        // Don't bother checking the stub in this case since the
                        // exception stub will never return to normal control flow.
                        checkLive = false;
                    }

                    // Make sure none of the fixed registers is live across an
                    // oopmap since we can't handle that correctly.
                    if (checkLive) {
                        for (Interval interval = iw.activeFirst(IntervalKind.fixedKind); interval != Interval.end(); interval = interval.next()) {
                            if (interval.currentTo() > op.id() + 1) {
                                // This interval is live out of this op so make sure
                                // that this interval represents some value that's
                                // referenced by this op either as an input or output.
                                boolean ok = false;
                                for (LIRVisitState.OperandMode mode : LIRVisitState.OperandMode.values()) {
                                    int n = visitor.oprCount(mode);
                                    for (int k = 0; k < n; k++) {
                                        LIROperand opr = visitor.oprAt(mode, k);
                                        if (opr.isFixedCpu()) {
                                            if (intervalAt(regNum(opr)) == interval) {
                                                ok = true;
                                                break;
                                            }
                                            int hi = regNumHi(opr);
                                            if (hi != -1 && intervalAt(hi) == interval) {
                                                ok = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                                assert ok : "fixed intervals should never be live across an oopmap point";
                            }
                        }
                    }
                }

                // oop-maps at calls do not contain registers, so check is not needed
                if (!visitor.hasCall()) {

                    for (LIRVisitState.OperandMode mode : LIRVisitState.OperandMode.values()) {
                        int n = visitor.oprCount(mode);
                        for (int k = 0; k < n; k++) {
                            LIROperand opr = visitor.oprAt(mode, k);

                            if (opr.isFixedCpu() && opr.isOop()) {
                                // operand is a non-virtual cpu register and contains an oop
                                if (C1XOptions.TraceLinearScanLevel >= 4) {
                                    op.printOn(TTY.out);
                                    TTY.print("checking operand ");
                                    opr.print(TTY.out);
                                    TTY.println();
                                }

                                Interval interval = intervalAt(regNum(opr));
                                assert interval != null : "no interval";

                                if (mode == LIRVisitState.OperandMode.InputMode) {
                                    if (interval.to() >= opId + 1) {
                                        assert interval.to() < opId + 2 || interval.hasHoleBetween(opId, opId + 2) : "oop input operand live after instruction";
                                    }
                                } else if (mode == LIRVisitState.OperandMode.OutputMode) {
                                    if (interval.from() <= opId - 1) {
                                        assert interval.hasHoleBetween(opId - 1, opId) : "oop input operand live after instruction";
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    void verifyConstants() {
        int size = liveSetSize();
        int numBlocks = blockCount();

        for (int i = 0; i < numBlocks; i++) {
            BlockBegin block = blockAt(i);
            BitMap liveAtEdge = block.liveIn();

            // visit all registers where the liveAtEdge bit is set
            for (int r = liveAtEdge.getNextOneOffset(0, size); r < size; r = liveAtEdge.getNextOneOffset(r + 1, size)) {
                Util.traceLinearScan(4, "checking interval %d of block B%d", r, block.blockID());

                Instruction value = gen().instructionForVreg(r);

                assert value != null : "all intervals live across block boundaries must have Value";
                assert value.operand().isRegister() && value.operand().isVirtual() : "value must have virtual operand";
                assert value.operand().vregNumber() == r : "register number must match";
                // TKR assert value.asConstant() == null || value.isPinned() :
                // "only pinned constants can be alive accross block boundaries";
            }
        }
    }

    public int numberOfSpillSlots(BasicType type) {
        switch (type) {
            case Boolean:
                return 1;
            case Byte:
                return 1;
            case Char:
                return 1;
            case Short:
                return 1;
            case Int:
                return 1;
            case Long:
                return 2;
            case Float:
                return 1;
            case Double:
                return 2;
            case Object:
                return (compilation.target.arch.is64bit()) ? 2 : 1;
            case Word:
                return (compilation.target.arch.is64bit()) ? 2 : 1;
        }
        throw new IllegalArgumentException("invalid BasicType " + this + " for .sizeInBytes()");
    }

    // TODO: Platform specific!!
    public int numPhysicalRegs(BasicType type) {
        if (type == BasicType.Double && compilation.target.arch.is32bit()) {
            return 2;
        } else {
            return 1;
        }
    }

    // TODO: Platform specific!!
    public boolean requiresAdjacentRegs(BasicType type) {
        // TODO Auto-generated method stub
        return false;
    }

    static int getAnyreg() {
        return Register.anyReg.number;
    }
}