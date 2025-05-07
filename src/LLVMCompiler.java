import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.bytedeco.llvm.global.LLVM;
import org.llvm4j.llvm4j.Module;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class LLVMCompiler {
    private static final Register RETURN_REGISTER = new Register(10);
    private static final Map<Integer, String> ARITHMETIC_INSTRUCTIONS = Map.of(LLVM.LLVMSub, "sub", LLVM.LLVMMul, "mul",
            LLVM.LLVMAdd, "add", LLVM.LLVMSDiv, "div", LLVM.LLVMSRem, "rem");
    private static final Map<Integer, String> COMPARE_PREDICATES = Map.of(LLVM.LLVMIntEQ, "beq", LLVM.LLVMIntNE, "bne",
            LLVM.LLVMIntSLE, "ble", LLVM.LLVMIntSLT, "blt", LLVM.LLVMIntSGE, "bge", LLVM.LLVMIntSGT, "bgt");

    private final Module module;
    private final LLVMTypeRef VOID_TYPE;
    private final File outputFile;

    private final HashMap<String, Global> globals = new HashMap<>();

    public LLVMCompiler(Module module, File outputFile) {
        this.module = module;
        this.VOID_TYPE = module.getContext().getVoidType().getRef();
        this.outputFile = outputFile;
    }

    public void compile() throws IOException {
        var ref = module.getRef();
        StringBuilder result = new StringBuilder();
        for (var global = LLVM.LLVMGetFirstGlobal(ref); global != null; global = LLVM.LLVMGetNextGlobal(global))
            result.append(compileGlobal(global));
        for (var func = LLVM.LLVMGetFirstFunction(ref); func != null; func = LLVM.LLVMGetNextFunction(func))
            result.append(compileFunction(func));
        Files.write(outputFile.toPath(), result.toString().getBytes());
    }

    private String compileGlobal(LLVMValueRef global) {
        var initializer = LLVM.LLVMGetInitializer(global);
        int value = 0;
        if (!initializer.isNull()) {
            var constant = LLVM.LLVMIsAConstantInt(initializer);
            if (constant != null) value = Math.toIntExact(LLVM.LLVMConstIntGetSExtValue(constant));
        }
        var name = LLVM.LLVMGetValueName(global).getString();
        globals.put(name, new Global(name));
        return String.format(".data\n%s:\n.word %d\n", name, value);
    }

    private String compileFunction(LLVMValueRef function) {
        var funcName = LLVM.LLVMGetValueName(function).getString();
        StringBuilder result = new StringBuilder();
        var allocator = new Allocator();
        for (var block = LLVM.LLVMGetFirstBasicBlock(function); block != null;
                block = LLVM.LLVMGetNextBasicBlock(block))
            result.append(compileBasicBlock(block, allocator));
        var stackSize = allocator.stackSize();
        result.insert(0, String.format(".text\n.globl %s\n%s:\naddi sp, sp, -%d\n", funcName, funcName, stackSize));
        result.append(String.format("FuncEnd:\naddi sp, sp, %d\nli a7, 93\necall", stackSize));

        return result.toString();
    }

    private int findLastUse(LLVMBasicBlockRef basicBlock, LLVMValueRef value) {
        int result = -1;
        int index = 0;
        for (var inst = LLVM.LLVMGetFirstInstruction(basicBlock); inst != null;
                inst = LLVM.LLVMGetNextInstruction(inst), ++index) {
            var num = LLVM.LLVMGetNumOperands(inst);
            for (int i = 0; i < num; ++i)
                if (value.equals(LLVM.LLVMGetOperand(inst, i))) result = index;
        }
        return result;
    }

    private int findLastUseInParent(LLVMValueRef inst) {
        return findLastUse(LLVM.LLVMGetInstructionParent(inst), inst);
    }

    private boolean isCrossBlock(LLVMValueRef value) {
        var basicBlock = LLVM.LLVMGetInstructionParent(value);
        var function = LLVM.LLVMGetBasicBlockParent(basicBlock);
        for (var block = LLVM.LLVMGetFirstBasicBlock(function); block != null;
                block = LLVM.LLVMGetNextBasicBlock(block)) {
            if (block.equals(basicBlock)) continue;
            if (findLastUse(block, value) != -1) return true;
        }
        return false;
    }

    private String compileBasicBlock(LLVMBasicBlockRef basicBlock, Allocator allocator) {
        StringBuilder result = new StringBuilder();
        result.append(LLVM.LLVMGetBasicBlockName(basicBlock).getString());
        result.append(":\n");
        int index = 0;
        for (var inst = LLVM.LLVMGetFirstInstruction(basicBlock); inst != null;
                inst = LLVM.LLVMGetNextInstruction(inst), ++index)
            compileInstruction(index, inst, result, allocator);
        return result.toString();
    }

    private DataLocation valueToDataLocation(LLVMValueRef value, Allocator allocator) {
        var constant = LLVM.LLVMIsAConstantInt(value);
        if (constant != null) return new Constant(Math.toIntExact(LLVM.LLVMConstIntGetSExtValue(constant)));
        var name = LLVM.LLVMGetValueName(value).getString();
        if (LLVM.LLVMIsAGlobalVariable(value) != null) return globals.get(name);
        return allocator.getLocation(name);
    }

    private void binaryInst(String instType, DataLocation dest, DataLocation[] operands, StringBuilder builder,
            Allocator allocator) {
        var a = operands[0].load(builder, allocator);
        var b = operands[1].load(builder, allocator);
        allocator.freeTemporaries();
        var tmp = dest instanceof Register ? (Register) dest : allocator.allocateTemporary();
        builder.append(String.format("%s %s, %s, %s\n", instType, tmp.name(), a.name(), b.name()));
        if (!(dest instanceof Register)) dest.store(tmp, builder, allocator);
        allocator.freeTemporaries();
    }

    private void compareInst(LLVMValueRef inst, DataLocation dest, DataLocation[] operands, StringBuilder builder,
            Allocator allocator) {
        var predicate = LLVM.LLVMGetICmpPredicate(inst);
        var a = operands[0].load(builder, allocator);
        var b = operands[1].load(builder, allocator);
        var name = LLVM.LLVMGetValueName(inst).getString();
        var trueLabel = name + "_True";
        var mergeLabel = name + "_Merge";
        builder.append(
                String.format("%s %s, %s, %s\n", COMPARE_PREDICATES.get(predicate), a.name(), b.name(), trueLabel));
        allocator.freeTemporaries();
        dest.store(new Constant(0).load(builder, allocator), builder, allocator);
        allocator.freeTemporaries();
        builder.append(String.format("j %s\n", mergeLabel));
        builder.append(String.format("%s:\n", trueLabel));
        dest.store(new Constant(1).load(builder, allocator), builder, allocator);
        allocator.freeTemporaries();
        builder.append(String.format("%s:\nnop\n", mergeLabel));
    }

    private void compileInstruction(int index, LLVMValueRef inst, StringBuilder builder, Allocator allocator) {
        var name = LLVM.LLVMGetValueName(inst);
        var operands = new DataLocation[LLVM.LLVMGetNumOperands(inst)];
        for (int i = 0; i < operands.length; ++i)
            operands[i] = valueToDataLocation(LLVM.LLVMGetOperand(inst, i), allocator);
        allocator.freeUntil(index);
        DataLocation loc = null;
        if (!VOID_TYPE.equals(LLVM.LLVMTypeOf(inst))) {
            var live = isCrossBlock(inst) ? Integer.MAX_VALUE : findLastUseInParent(inst);
            loc = allocator.allocate(name.getString(), live, builder);
        }

        var opcode = LLVM.LLVMGetInstructionOpcode(inst);
        switch (opcode) {
            case LLVM.LLVMAlloca:
            case LLVM.LLVMUnreachable:
                break;
            case LLVM.LLVMLoad:
            case LLVM.LLVMZExt:
                Objects.requireNonNull(loc).store(operands[0].load(builder, allocator), builder, allocator);
                allocator.freeTemporaries();
                break;
            case LLVM.LLVMStore:
                operands[1].store(operands[0].load(builder, allocator), builder, allocator);
                allocator.freeTemporaries();
                break;
            case LLVM.LLVMRet:
                RETURN_REGISTER.store(operands[0].load(builder, allocator), builder, allocator);
                allocator.freeTemporaries();
                builder.append("j FuncEnd\n");
                break;
            case LLVM.LLVMAdd:
            case LLVM.LLVMSub:
            case LLVM.LLVMMul:
            case LLVM.LLVMSDiv:
            case LLVM.LLVMSRem:
                binaryInst(ARITHMETIC_INSTRUCTIONS.get(opcode), loc, operands, builder, allocator);
                break;
            case LLVM.LLVMICmp:
                compareInst(inst, Objects.requireNonNull(loc), operands, builder, allocator);
                break;
            case LLVM.LLVMBr:
                if (LLVM.LLVMIsConditional(inst) != 0) {
                    var cond = operands[0].load(builder, allocator);
                    builder.append(String.format("beqz %s, %s\n", cond.name(),
                            LLVM.LLVMGetBasicBlockName(LLVM.LLVMGetSuccessor(inst, 1)).getString()));
                    allocator.freeTemporaries();
                }
                builder.append(String.format("j %s\n", LLVM.LLVMGetBasicBlockName(LLVM.LLVMGetSuccessor(inst, 0)).getString()));
                break;
            default:
                throw new IllegalArgumentException("Unknown opcode: " + opcode);
        }
    }

    private static abstract class DataLocation {
        public abstract Register load(StringBuilder builder, Allocator allocator);

        public abstract void store(Register value, StringBuilder builder, Allocator allocator);
    }

    private static class Constant extends DataLocation {
        private final int value;

        public Constant(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }

        @Override
        public Register load(StringBuilder builder, Allocator allocator) {
            var tmp = allocator.allocateTemporary();
            builder.append(String.format("li %s, %d\n", tmp.name(), value));
            return tmp;
        }

        @Override
        public void store(Register value, StringBuilder builder, Allocator allocator) {
            throw new UnsupportedOperationException();
        }
    }

    private static class Register extends DataLocation {
        private final int register;

        public Register(int register) {
            this.register = register;
        }

        public String name() {
            return String.format("x%d", register);
        }

        @Override
        public Register load(StringBuilder builder, Allocator allocator) {
            return this;
        }

        @Override
        public void store(Register value, StringBuilder builder, Allocator allocator) {
            builder.append(String.format("mv %s, %s\n", name(), value.name()));
        }
    }

    private static class Stack extends DataLocation {
        public final int position;

        public Stack(int position) {
            this.position = position;
        }

        @Override
        public Register load(StringBuilder builder, Allocator allocator) {
            var tmp = allocator.allocateTemporary();
            builder.append(String.format("lw %s, %d(sp)\n", tmp.name(), position));
            return tmp;
        }

        @Override
        public void store(Register value, StringBuilder builder, Allocator allocator) {
            builder.append(String.format("sw %s, %d(sp)\n", value.name(), position));
        }
    }

    private static class Global extends DataLocation {
        private final String name;

        public Global(String name) {
            this.name = name;
        }

        @Override
        public Register load(StringBuilder builder, Allocator allocator) {
            var tmp = allocator.allocateTemporary();
            builder.append(String.format("la %s, %s\n", tmp.name(), name));
            builder.append(String.format("lw %s, 0(%s)\n", tmp.name(), tmp.name()));
            return tmp;
        }

        @Override
        public void store(Register value, StringBuilder builder, Allocator allocator) {
            var tmp = allocator.allocateTemporary();
            builder.append(String.format("la %s, %s\n", tmp.name(), name));
            builder.append(String.format("sw %s, 0(%s)\n", value.name(), tmp.name()));
        }
    }

    private static class Allocator {
        private static final int[] TEMPORARY_REGISTERS = new int[]{5, 6, 7};
        private static final int[] AVAILABLE_REGISTERS = new int[]{8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
                22, 23, 24, 25, 26, 27, 28, 29, 30, 31};
        private final boolean[] temporaries = new boolean[TEMPORARY_REGISTERS.length];
        private final String[] registers = new String[AVAILABLE_REGISTERS.length];
        private final ArrayList<String> stack = new ArrayList<>();

        private final HashMap<String, DataLocation> locations = new HashMap<>();
        private final HashMap<String, Integer> liveRange = new HashMap<>();

        private void spill(String name, StringBuilder builder) {
            var cur = locations.get(name);
            var sp = allocateStack(name);
            sp.store(cur.load(builder, this), builder, this);
            freeTemporaries();
            locations.put(name, sp);
        }

        private Register tryAllocateRegister(String name, int end, StringBuilder builder) {
            for (int i = 0; i < registers.length; ++i) {
                if (registers[i] != null) continue;
                registers[i] = name;
                var res = new Register(AVAILABLE_REGISTERS[i]);
                locations.put(name, res);
                return res;
            }

            int reg = 0;
            int maxEnd = -1;
            for (int i = 0; i < registers.length; ++i) {
                int otherEnd = liveRange.get(registers[i]);
                if (otherEnd > maxEnd) {
                    reg = i;
                    maxEnd = otherEnd;
                }
            }

            if (maxEnd > end) {
                spill(registers[reg], builder);
                registers[reg] = name;
                var res = new Register(AVAILABLE_REGISTERS[reg]);
                locations.put(name, res);
                return res;
            }

            return null;
        }

        private Stack allocateStack(String name) {
            for (int i = 0; i < stack.size(); ++i) {
                if (stack.get(i) != null) continue;
                stack.set(i, name);
                var res = new Stack(i * 4);
                locations.put(name, res);
                return res;
            }

            var res = new Stack(stack.size() * 4);
            stack.add(name);
            locations.put(name, res);
            return res;
        }

        public DataLocation allocate(String name, int end, StringBuilder builder) {
            liveRange.put(name, end);

            var reg = tryAllocateRegister(name, end, builder);
            if (reg != null) return reg;

            return allocateStack(name);
        }

        public DataLocation getLocation(String name) {
            return locations.get(name);
        }

        public int stackSize() {
            int res = stack.size() * 4;
            if (res % 16 != 0) {
                res -= res % 16;
                res += 16;
            }
            return res;
        }

        public void freeUntil(int end) {
            for (int i = 0; i < registers.length; ++i) {
                var name = registers[i];
                if (name == null) continue;
                int range = liveRange.get(name);
                if (range <= end) {
                    registers[i] = null;
                    locations.remove(name);
                    liveRange.remove(name);
                }
            }

            for (int i = 0; i < stack.size(); ++i) {
                var name = stack.get(i);
                if (name == null) continue;
                int range = liveRange.get(name);
                if (range <= end) {
                    stack.set(i, null);
                    locations.remove(name);
                    liveRange.remove(name);
                }
            }
        }

        public Register allocateTemporary() {
            for (int i = 0; i < temporaries.length; ++i) {
                if (temporaries[i]) continue;
                temporaries[i] = true;
                return new Register(TEMPORARY_REGISTERS[i]);
            }

            throw new RuntimeException("No available temporary registers");
        }

        public void freeTemporaries() {
            Arrays.fill(temporaries, false);
        }
    }
}