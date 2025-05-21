import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

import java.util.HashMap;
import java.util.HashSet;

public abstract class LLVMPass {
    protected final Module module;
    protected final LLVMBuilderRef builder = LLVM.LLVMCreateBuilder();

    protected final HashSet<LLVMBasicBlockRef> allBasicBlocks = new HashSet<>();
    protected final HashMap<LLVMBasicBlockRef, HashSet<LLVMBasicBlockRef>> bbPredecessors = new HashMap<>();
    protected final HashMap<LLVMBasicBlockRef, HashSet<LLVMBasicBlockRef>> bbSuccessors = new HashMap<>();

    protected final HashSet<LLVMValueRef> allInstructions = new HashSet<>();
    protected final HashMap<LLVMValueRef, HashSet<LLVMValueRef>> instPredecessors = new HashMap<>();
    protected final HashMap<LLVMValueRef, HashSet<LLVMValueRef>> instSuccessors = new HashMap<>();

    public LLVMPass(Module module) {
        this.module = module;
    }

    protected boolean addInstFlow(LLVMValueRef from, LLVMValueRef to) {
        var successors = instSuccessors.get(from);
        if (successors.contains(to)) return false;
        successors.add(to);
        instPredecessors.get(to).add(from);
        return true;
    }

    protected boolean addBBFlow(LLVMBasicBlockRef from, LLVMBasicBlockRef to) {
        var successors = bbSuccessors.get(from);
        if (successors.contains(to)) return false;
        successors.add(to);
        bbPredecessors.get(to).add(from);
        return true;
    }

    protected void calculateBB() {
        allBasicBlocks.clear();
        bbPredecessors.clear();
        bbSuccessors.clear();

        var ref = module.getRef();
        for (var func = LLVM.LLVMGetFirstFunction(ref); func != null; func = LLVM.LLVMGetNextFunction(func)) {
            for (var block = LLVM.LLVMGetFirstBasicBlock(func); block != null;
                    block = LLVM.LLVMGetNextBasicBlock(block)) {
                allBasicBlocks.add(block);
                bbPredecessors.put(block, new HashSet<>());
                var exitInst = LLVM.LLVMGetBasicBlockTerminator(block);
                var succ = new HashSet<LLVMBasicBlockRef>();
                if (exitInst != null && LLVM.LLVMGetInstructionOpcode(exitInst) == LLVM.LLVMBr) {
                    var countSucc = LLVM.LLVMGetNumSuccessors(exitInst);
                    for (var i = 0; i < countSucc; ++i)
                        succ.add(LLVM.LLVMGetSuccessor(exitInst, i));
                }
                bbSuccessors.put(block, succ);
            }
        }
        bbSuccessors.forEach((bb, succs) -> succs.forEach(succ -> bbPredecessors.get(succ).add(bb)));
    }

    protected void calculateInst() {
        allInstructions.clear();
        instPredecessors.clear();
        instSuccessors.clear();

        var ref = module.getRef();
        for (var func = LLVM.LLVMGetFirstFunction(ref); func != null; func = LLVM.LLVMGetNextFunction(func)) {
            for (var block = LLVM.LLVMGetFirstBasicBlock(func); block != null;
                    block = LLVM.LLVMGetNextBasicBlock(block))
                for (var inst = LLVM.LLVMGetFirstInstruction(block); inst != null;
                        inst = LLVM.LLVMGetNextInstruction(inst)) {
                    allInstructions.add(inst);
                    instPredecessors.put(inst, new HashSet<>());
                    var succ = new HashSet<LLVMValueRef>();
                    if (LLVM.LLVMGetInstructionOpcode(inst) == LLVM.LLVMBr) {
                        var countSucc = LLVM.LLVMGetNumSuccessors(inst);
                        for (var i = 0; i < countSucc; ++i)
                            succ.add(LLVM.LLVMGetFirstInstruction(LLVM.LLVMGetSuccessor(inst, i)));
                    } else {
                        var nextInst = LLVM.LLVMGetNextInstruction(inst);
                        if (nextInst != null) succ.add(nextInst);
                    }
                    instSuccessors.put(inst, succ);
                }
        }
        instSuccessors.forEach((inst, succs) -> succs.forEach(succ -> instPredecessors.get(succ).add(inst)));
    }

    protected void prepare() {}

    public abstract boolean run();
}