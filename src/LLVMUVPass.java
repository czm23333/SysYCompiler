import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

import java.util.HashMap;
import java.util.HashSet;

public class LLVMUVPass extends LLVMPass {
    private final LLVMTypeRef VOID_TYPE;
    private final HashMap<LLVMValueRef, HashSet<LLVMValueRef>> allocasStore = new HashMap<>();
    private final HashSet<LLVMValueRef> variables = new HashSet<>();
    private final HashSet<LLVMValueRef> used = new HashSet<>();

    public LLVMUVPass(Module module) {
        super(module);
        VOID_TYPE = module.getContext().getVoidType().getRef();
    }

    @Override
    protected void prepare() {
        super.prepare();
        calculateInst();

        allInstructions.forEach(inst -> {
            if (!VOID_TYPE.equals(LLVM.LLVMTypeOf(inst))) variables.add(inst);
            if (LLVM.LLVMGetInstructionOpcode(inst) == LLVM.LLVMAlloca) allocasStore.put(inst, new HashSet<>());
        });
    }

    private void mark(LLVMValueRef value) {
        if (LLVM.LLVMIsAConstantInt(value) != null) return;
        used.add(value);
    }

    private void markUsage(LLVMValueRef inst) {
        var opcode = LLVM.LLVMGetInstructionOpcode(inst);
        if (opcode == LLVM.LLVMStore) {
            mark(LLVM.LLVMGetOperand(inst, 0));
            var stores = allocasStore.get(LLVM.LLVMGetOperand(inst, 1));
            if (stores != null) stores.add(inst);
        } else {
            var operandCnt = LLVM.LLVMGetNumOperands(inst);
            for (var i = 0; i < operandCnt; ++i) mark(LLVM.LLVMGetOperand(inst, i));
        }
    }

    private void remove(LLVMValueRef inst) {
        if (allocasStore.containsKey(inst)) allocasStore.get(inst).forEach(this::remove);
        LLVM.LLVMInstructionEraseFromParent(inst);
    }

    @Override
    public boolean run() {
        prepare();

        allInstructions.forEach(this::markUsage);
        boolean flag = false;
        for (var variable : variables) {
            if (used.contains(variable)) continue;
            flag = true;
            remove(variable);
        }
        return flag;
    }
}