import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

import java.util.HashSet;

public class LLVMDCEPass extends LLVMPass {
    private final HashSet<LLVMBasicBlockRef> reachable = new HashSet<>();
    private final HashSet<LLVMBasicBlockRef> worklist = new HashSet<>();

    public LLVMDCEPass(Module module) {
        super(module);
    }

    @Override
    protected void prepare() {
        super.prepare();
        calculateBB();

        var ref = module.getRef();
        for (var func = LLVM.LLVMGetFirstFunction(ref); func != null; func = LLVM.LLVMGetNextFunction(func))
            worklist.add(LLVM.LLVMGetEntryBasicBlock(func));

        while (!worklist.isEmpty()) {
            var begin = worklist.iterator();
            var bb = begin.next();
            begin.remove();
            reachable.add(bb);
            for (var succ : bbSuccessors.get(bb)) {
                if (reachable.contains(succ)) continue;
                worklist.add(succ);
            }
        }
    }

    private boolean removeUnreachable() {
        boolean flag = false;
        for (var bb : allBasicBlocks) {
            if (reachable.contains(bb)) continue;
            flag = true;
            LLVM.LLVMRemoveBasicBlockFromParent(bb);
        }
        return flag;
    }

    private boolean blockMerge() {
        for (var bb : allBasicBlocks) {
            var succs = bbSuccessors.get(bb);
            if (succs.size() != 1) continue;
            var succ = succs.iterator().next();
            if (bb.equals(succ)) continue;
            var preds = bbPredecessors.get(succ);
            if (preds.size() != 1) continue;

            LLVM.LLVMInstructionRemoveFromParent(LLVM.LLVMGetBasicBlockTerminator(bb));
            LLVM.LLVMPositionBuilderAtEnd(builder, bb);
            for (var inst = LLVM.LLVMGetFirstInstruction(succ); inst != null; ) {
                var oldInst = inst;
                inst = LLVM.LLVMGetNextInstruction(inst);
                LLVM.LLVMInstructionRemoveFromParent(oldInst);
                LLVM.LLVMInsertIntoBuilder(builder, oldInst);
            }
            LLVM.LLVMRemoveBasicBlockFromParent(succ);
            calculateBB();
            return true;
        }
        return false;
    }

    @Override
    public boolean run() {
        prepare();

        boolean flag = removeUnreachable();
        while (blockMerge()) flag = true;

        return flag;
    }
}