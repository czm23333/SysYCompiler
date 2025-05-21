import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

public class LLVMCPPass extends LLVMPass {
    private static final Map<Integer, BiFunction<Integer, Integer, Integer>> BINARY_OPS = Map.ofEntries(
            Map.entry(LLVM.LLVMAdd, Integer::sum), Map.entry(LLVM.LLVMMul, (x, y) -> x * y),
            Map.entry(LLVM.LLVMSub, (x, y) -> x - y), Map.entry(LLVM.LLVMSDiv, (x, y) -> x / y),
            Map.entry(LLVM.LLVMSRem, (x, y) -> x % y), Map.entry(LLVM.LLVMIntEQ, (x, y) -> x == y ? 1 : 0),
            Map.entry(LLVM.LLVMIntNE, (x, y) -> x != y ? 1 : 0), Map.entry(LLVM.LLVMIntSLE, (x, y) -> x <= y ? 1 : 0),
            Map.entry(LLVM.LLVMIntSLT, (x, y) -> x < y ? 1 : 0), Map.entry(LLVM.LLVMIntSGE, (x, y) -> x >= y ? 1 : 0),
            Map.entry(LLVM.LLVMIntSGT, (x, y) -> x > y ? 1 : 0));

    private final HashSet<LLVMValueRef> worklist = new HashSet<>();
    private final HashMap<LLVMValueRef, DataFacts> ins = new HashMap<>();
    private final HashMap<LLVMValueRef, DataFacts> outs = new HashMap<>();

    public LLVMCPPass(Module module) {
        super(module);
    }

    @Override
    protected void prepare() {
        super.prepare();
        calculateInst();
        instSuccessors.clear();
        instPredecessors.clear();
        allInstructions.forEach(inst -> instSuccessors.put(inst, new HashSet<>()));
        allInstructions.forEach(inst -> instPredecessors.put(inst, new HashSet<>()));
        calculateBB();

        var ref = module.getRef();
        LLVMValueRef entry = null;
        for (var func = LLVM.LLVMGetFirstFunction(ref); func != null; func = LLVM.LLVMGetNextFunction(func))
            entry = LLVM.LLVMGetFirstInstruction(LLVM.LLVMGetEntryBasicBlock(func));

        allInstructions.forEach(inst -> {
            ins.put(inst, new DataFacts());
            outs.put(inst, new DataFacts());
        });

        for (var global = LLVM.LLVMGetFirstGlobal(ref); global != null; global = LLVM.LLVMGetNextGlobal(global)) {
            var initializer = LLVM.LLVMGetInitializer(global);
            int value = 0;
            if (!initializer.isNull()) {
                var constant = LLVM.LLVMIsAConstantInt(initializer);
                if (constant != null) value = Math.toIntExact(LLVM.LLVMConstIntGetSExtValue(constant));
            }

            ins.get(entry).put(global, new Ref(new Constant(value)));
        }

        worklist.addAll(allInstructions);
    }

    private CPValue valueToCPValue(LLVMValueRef value, DataFacts factsIn) {
        var constant = LLVM.LLVMIsAConstantInt(value);
        if (constant != null) return new Constant(Math.toIntExact(LLVM.LLVMConstIntGetSExtValue(constant)));
        return factsIn.get(value);
    }

    private CPValue operandValue(LLVMValueRef value, DataFacts factsIn, int index) {
        return valueToCPValue(LLVM.LLVMGetOperand(value, index), factsIn);
    }

    private DataFacts transfer(DataFacts in, LLVMValueRef inst) {
        var newOut = in.clone();

        var opcode = LLVM.LLVMGetInstructionOpcode(inst);
        switch (opcode) {
            case LLVM.LLVMAlloca:
                newOut.put(inst, Ref.REF_UNDEF);
                break;
            case LLVM.LLVMUnreachable:
            case LLVM.LLVMRet:
            case LLVM.LLVMBr:
                break;
            case LLVM.LLVMLoad:
                newOut.put(inst, operandValue(inst, in, 0).deref());
                break;
            case LLVM.LLVMZExt:
                newOut.put(inst, operandValue(inst, in, 0));
                break;
            case LLVM.LLVMStore:
                newOut.put(LLVM.LLVMGetOperand(inst, 1), new Ref(operandValue(inst, in, 0)));
                break;
            case LLVM.LLVMAdd:
            case LLVM.LLVMSub:
            case LLVM.LLVMMul:
            case LLVM.LLVMSDiv:
            case LLVM.LLVMSRem:
                newOut.put(inst, operandValue(inst, in, 0).binaryOp(operandValue(inst, in, 1), BINARY_OPS.get(opcode)));
                break;
            case LLVM.LLVMICmp:
                newOut.put(inst, operandValue(inst, in, 0).binaryOp(operandValue(inst, in, 1),
                        BINARY_OPS.get(LLVM.LLVMGetICmpPredicate(inst))));
                break;
            default:
                throw new IllegalArgumentException("Unknown opcode: " + opcode);
        }

        return newOut;
    }

    private boolean updateSuccessors(DataFacts in, LLVMValueRef inst) {
        var newSucc = new HashSet<LLVMValueRef>();
        if (LLVM.LLVMGetInstructionOpcode(inst) == LLVM.LLVMBr) {
            if (LLVM.LLVMIsConditional(inst) != 0) {
                var cond = valueToCPValue(LLVM.LLVMGetCondition(inst), in);
                if (cond instanceof Constant) newSucc.add(LLVM.LLVMGetFirstInstruction(
                        LLVM.LLVMGetSuccessor(inst, ((Constant) cond).value == 0 ? 1 : 0)));
                else if (cond instanceof NonConstant) {
                    newSucc.add(LLVM.LLVMGetFirstInstruction(LLVM.LLVMGetSuccessor(inst, 0)));
                    newSucc.add(LLVM.LLVMGetFirstInstruction(LLVM.LLVMGetSuccessor(inst, 1)));
                }
            } else newSucc.add(LLVM.LLVMGetFirstInstruction(LLVM.LLVMGetSuccessor(inst, 0)));
        } else {
            var next = LLVM.LLVMGetNextInstruction(inst);
            if (next != null) newSucc.add(next);
        }

        return newSucc.stream().map(next -> addInstFlow(inst, next)).reduce(Boolean::logicalOr).orElse(false);
    }

    private void solveCP() {
        prepare();

        while (!worklist.isEmpty()) {
            var begin = worklist.iterator();
            var inst = begin.next();
            begin.remove();

            var preds = instPredecessors.get(inst);
            if (!preds.isEmpty()) {
                var newIn = new DataFacts();
                preds.forEach(pred -> newIn.meet(outs.get(pred)));
                ins.put(inst, newIn);
            }

            var factsIn = ins.get(inst);
            var newOut = transfer(factsIn, inst);
            if (!updateSuccessors(factsIn, inst) && newOut.equals(outs.get(inst))) continue;
            outs.put(inst, newOut);
            worklist.addAll(instSuccessors.get(inst));
        }
    }

    private boolean replaceConstant() {
        boolean flag = false;
        for (var iter = allInstructions.iterator(); iter.hasNext(); ) {
            var inst = iter.next();
            var out = outs.get(inst);
            var cur = out.get(inst);
            if (cur instanceof Constant) {
                LLVM.LLVMReplaceAllUsesWith(inst, LLVM.LLVMConstInt(LLVM.LLVMTypeOf(inst), ((Constant) cur).value, 1));
                iter.remove();
                LLVM.LLVMInstructionEraseFromParent(inst);
                flag = true;
            }
        }
        for (var inst : allInstructions) {
            var in = ins.get(inst);
            var operandCnt = LLVM.LLVMGetNumOperands(inst);
            for (var i = 0; i < operandCnt; ++i) {
                var original = LLVM.LLVMGetOperand(inst, i);
                if (LLVM.LLVMIsAConstantInt(original) != null) continue;
                var value = operandValue(inst, in, i);
                if (value instanceof Constant) {
                    LLVM.LLVMSetOperand(inst, i,
                            LLVM.LLVMConstInt(LLVM.LLVMTypeOf(original), ((Constant) value).value, 1));
                    flag = true;
                }
            }
        }
        return flag;
    }

    private boolean foldConstBr() {
        boolean flag = false;
        for (var bb : allBasicBlocks) {
            var exitInst = LLVM.LLVMGetBasicBlockTerminator(bb);
            if (exitInst != null && LLVM.LLVMGetInstructionOpcode(exitInst) == LLVM.LLVMBr &&
                LLVM.LLVMIsConditional(exitInst) != 0) {
                var cond = LLVM.LLVMGetCondition(exitInst);
                if (LLVM.LLVMIsAConstantInt(cond) == null) continue;
                var condConst = Math.toIntExact(LLVM.LLVMConstIntGetSExtValue(cond)) == 0 ? 1 : 0;
                var constSucc = LLVM.LLVMGetSuccessor(exitInst, condConst);
                LLVM.LLVMInstructionEraseFromParent(exitInst);

                LLVM.LLVMPositionBuilderAtEnd(builder, bb);
                LLVM.LLVMBuildBr(builder, constSucc);

                flag = true;
            }
        }
        return flag;
    }

    @Override
    public boolean run() {
        solveCP();
        boolean flag = replaceConstant();
        flag |= foldConstBr();
        return flag;
    }

    private static class DataFacts implements Cloneable {
        private HashMap<LLVMValueRef, CPValue> facts = new HashMap<>();

        public void put(LLVMValueRef var, CPValue value) {
            facts.put(var, value);
        }

        public CPValue get(LLVMValueRef var) {
            return facts.getOrDefault(var, Undef.INSTANCE);
        }

        public void meet(DataFacts other) {
            other.facts.forEach((key, value) -> facts.merge(key, value, CPValue::meet));
        }

        @Override
        public DataFacts clone() {
            try {
                DataFacts clone = (DataFacts) super.clone();
                //noinspection unchecked
                clone.facts = (HashMap<LLVMValueRef, CPValue>) facts.clone();
                return clone;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DataFacts)) return false;
            DataFacts dataFacts = (DataFacts) o;
            return Objects.equals(facts, dataFacts.facts);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(facts);
        }
    }

    private static abstract class CPValue {
        public abstract CPValue meet(CPValue other);

        public abstract CPValue deref();

        public abstract CPValue binaryOp(CPValue other, BiFunction<Integer, Integer, Integer> op);
    }

    private static class Ref extends CPValue {
        public static final Ref REF_UNDEF = new Ref(Undef.INSTANCE);

        public final CPValue referenced;

        public Ref(CPValue referenced) {
            this.referenced = referenced;
        }

        @Override
        public CPValue meet(CPValue other) {
            if (other instanceof Undef) return this;
            if (other instanceof Value) throw new IllegalArgumentException("Meet ref and defined value");
            return new Ref(referenced.meet(((Ref) other).referenced));
        }

        @Override
        public CPValue deref() {
            return referenced;
        }

        @Override
        public CPValue binaryOp(CPValue other, BiFunction<Integer, Integer, Integer> op) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Ref)) return false;
            Ref ref = (Ref) o;
            return Objects.equals(referenced, ref.referenced);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(referenced);
        }
    }

    private static abstract class Value extends CPValue {
    }

    private static class Undef extends Value {
        public static final Undef INSTANCE = new Undef();

        private Undef() {}

        @Override
        public CPValue meet(CPValue other) {
            return other;
        }

        @Override
        public CPValue deref() {
            return INSTANCE;
        }

        @Override
        public CPValue binaryOp(CPValue other, BiFunction<Integer, Integer, Integer> op) {
            if (other instanceof Ref) throw new UnsupportedOperationException();
            return INSTANCE;
        }
    }

    private static class Constant extends Value {
        public final int value;

        public Constant(int value) {
            this.value = value;
        }

        @Override
        public CPValue meet(CPValue other) {
            if (other instanceof Ref) throw new UnsupportedOperationException("Meet ref and defined value");
            if (other instanceof Undef) return this;
            if (other instanceof Constant) {
                if (value == ((Constant) other).value) return this;
                return NonConstant.INSTANCE;
            }
            return other;
        }

        @Override
        public CPValue deref() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CPValue binaryOp(CPValue other, BiFunction<Integer, Integer, Integer> op) {
            if (other instanceof Ref) throw new UnsupportedOperationException();
            if (other instanceof Undef) return Undef.INSTANCE;
            if (other instanceof Constant) return new Constant(op.apply(value, ((Constant) other).value));
            return other;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Constant)) return false;
            Constant constant = (Constant) o;
            return value == constant.value;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }
    }

    private static class NonConstant extends Value {
        public static final NonConstant INSTANCE = new NonConstant();

        private NonConstant() {}

        @Override
        public CPValue meet(CPValue other) {
            if (other instanceof Ref) throw new UnsupportedOperationException("Meet ref and defined value");
            return this;
        }

        @Override
        public CPValue deref() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CPValue binaryOp(CPValue other, BiFunction<Integer, Integer, Integer> op) {
            if (other instanceof Ref) throw new UnsupportedOperationException();
            if (other instanceof Undef) return Undef.INSTANCE;
            return INSTANCE;
        }
    }
}