import kotlin.Pair;
import org.llvm4j.llvm4j.*;
import org.llvm4j.llvm4j.Module;
import org.llvm4j.optional.Option;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SysYTranslator extends SysYParserBaseVisitor<Value> {
    private final Context context = new Context();
    private final IRBuilder irBuilder = context.newIRBuilder();
    public final Module module = context.newModule("module");
    private final IntegerType INT_TYPE = context.getInt32Type();
    private final ConstantInt ZERO = INT_TYPE.getConstant(0, false);
    private final IntegerType BOOL_TYPE = context.getInt1Type();
    private final ConstantInt TRUE = BOOL_TYPE.getConstant(1, false);
    private final ConstantInt FALSE = BOOL_TYPE.getConstant(0, false);
    private final VoidType VOID_TYPE = context.getVoidType();
    private final TypeHelper typeHelper = new TypeHelper();
    private final CastHelper castHelper = new CastHelper();
    private final DerefHelper derefHelper = new DerefHelper();

    private final AggregateBuilder<ConstantOrInitializer> nonConstAggregate = new AggregateBuilder<>(
            new InitializerAggregateHelper());
    private final AggregateBuilder<Constant> constAggregate = new AggregateBuilder<>(new ConstantAggregateHelper());
    private final Deque<SymbolContext> contextStack = new ArrayDeque<>();
    private AggregateBuilder<?> currentAggregate;

    private Function currentFunction;
    private BasicBlock currentBlock;
    private boolean blockEnded;

    private BasicBlock currentCondBlock;
    private BasicBlock currentMergeBlock;

    private Value lookupSymbol(String name) {
        Value res = null;

        var local = contextStack.peek();
        if (local != null) res = local.lookup(name);
        if (res != null) return res;

        res = module.getFunction(name).toNullable();
        if (res != null) return res;

        res = module.getGlobalVariable(name).toNullable();
        return res;
    }

    private SymbolContext newSymbolContext() {
        var symbols = new SymbolContext(contextStack.peek());
        contextStack.push(symbols);
        return symbols;
    }

    private void switchBlock(BasicBlock block) {
        irBuilder.positionAfter(block);
        currentBlock = block;
        blockEnded = false;
    }

    private void markBlockEnd() {
        blockEnded = true;
    }

    @Override
    protected Value aggregateResult(Value aggregate, Value nextResult) {
        if (nextResult == null) return aggregate;
        return nextResult;
    }

    @Override
    public Value visitProgram(SysYParser.ProgramContext ctx) {
        return super.visitProgram(ctx);
    }

    @Override
    public Value visitSingle(SysYParser.SingleContext ctx) {
        currentAggregate.addValue(visit(ctx.expr()));
        return null;
    }

    @Override
    public Value visitArray(SysYParser.ArrayContext ctx) {
        ctx.eqInitializeVal().forEach(child -> {
            if (child instanceof SysYParser.ArrayContext) {
                currentAggregate.beginAggregate();
                visit(child);
                currentAggregate.endAggregate();
            } else visit(child);
        });
        return null;
    }

    @Override
    public Value visitVarDef(SysYParser.VarDefContext ctx) {
        try (var base = typeHelper.fromBasicType(ctx.basicType())) {
            ctx.varDefEntry().forEach(entry -> {
                var type = typeHelper.applyArray(base, entry.arrayPostfix());
                var name = entry.name.getText();

                var symbols = contextStack.peek();
                if (symbols == null) currentAggregate = constAggregate;
                else currentAggregate = nonConstAggregate;

                currentAggregate.begin(type);
                if (entry.init != null) visit(entry.init);

                if (symbols == null) {
                    var global = module.addGlobalVariable(name, type, Option.empty()).unwrap();
                    global.setInitializer(constAggregate.end());
                    global.close();
                } else {
                    var local = irBuilder.buildAlloca(type, Option.of(name));
                    symbols.define(name, local);
                    nonConstAggregate.end().toInitializer().accept(local);
                }
            });
        }
        return null;
    }

    @Override
    public Value visitFuncDef(SysYParser.FuncDefContext ctx) {
        var funcType = typeHelper.fromFuncDef(ctx);
        var func = module.addFunction(ctx.name.getText(), funcType);
        var symbols = newSymbolContext();
        switchBlock(context.newBasicBlock(func.getName() + "Entry"));
        func.addBasicBlock(currentBlock);
        currentFunction = func;
        var params = ctx.funcParam();
        for (int i = 0; i < params.size(); ++i) {
            var param = params.get(i).name.getText();
            var arg = func.getParameter(i).unwrap();
            var paramVar = irBuilder.buildAlloca(arg.getType(), Option.of(param));
            irBuilder.buildStore(paramVar, arg);
            symbols.define(param, paramVar);
        }

        visit(ctx.stmtBlock());
        if (!blockEnded) {
            if (func.getName().equals("main")) irBuilder.buildReturn(Option.of(INT_TYPE.getConstant(0, false)));
            else if (funcType.getReturnType().isVoidType()) irBuilder.buildReturn(Option.empty());
            else irBuilder.buildUnreachable();
        }
        currentBlock = null;
        currentFunction = null;
        contextStack.pop();
        return null;
    }

    @Override
    public Value visitStmtBlock(SysYParser.StmtBlockContext ctx) {
        newSymbolContext();
        var res = super.visitStmtBlock(ctx);
        contextStack.pop();
        return res;
    }

    @Override
    public Value visitConst(SysYParser.ConstContext ctx) {
        return INT_TYPE.getConstant(Integer.decode(ctx.value.getText()), false);
    }

    @Override
    public Value visitVarAccess(SysYParser.VarAccessContext ctx) {
        var res = lookupSymbol(ctx.IDENT().getText());
        var postfix = ctx.arrayPostfix();
        if (postfix.arrayPostfixSingle().isEmpty()) return res;
        derefHelper.begin(res);
        postfix.arrayPostfixSingle().forEach(single -> derefHelper.deref(visit(single.expr())));
        return derefHelper.end();
    }

    @Override
    public Value visitAccess(SysYParser.AccessContext ctx) {
        var ptr = visit(ctx.varAccess());
        var ptrType = typeHelper.ensurePointerType(ptr.getType());
        if (ptrType.getElementType().isArrayType())
            return irBuilder.buildGetElementPtr(ptr, new Value[]{ZERO, ZERO}, Option.of("ArrayDecay"), true);
        return irBuilder.buildLoad(ptr, Option.of("AccessLoad"));
    }

    @Override
    public Value visitFuncRealParam(SysYParser.FuncRealParamContext ctx) {
        return visit(ctx.expr());
    }

    @Override
    public Value visitFunctionCall(SysYParser.FunctionCallContext ctx) {
        return irBuilder.buildCall((Function) lookupSymbol(ctx.func.getText()),
                ctx.funcRealParam().stream().map(this::visit).toArray(Value[]::new), Option.empty());
    }

    @Override
    public Value visitUnary(SysYParser.UnaryContext ctx) {
        var x = visit(ctx.expr());
        var xType = typeHelper.ensureIntegerType(x.getType());
        switch (ctx.op.getType()) {
            case SysYLexer.PLUS:
                return castHelper.elevate(x);
            case SysYLexer.MINUS:
                return irBuilder.buildIntSub(ZERO, castHelper.elevate(x), WrapSemantics.Unspecified, Option.of("Neg"));
            case SysYLexer.NOT:
                return castHelper.convertTo(
                        irBuilder.buildIntCompare(IntPredicate.Equal, x, xType.getConstant(0, false), Option.of("Not")),
                        xType);
            default:
                throw new IllegalArgumentException("Unsupported operator: " + ctx.op.getText());
        }
    }

    @Override
    public Value visitMuls(SysYParser.MulsContext ctx) {
        var l = castHelper.elevate(visit(ctx.l));
        var r = castHelper.elevate(visit(ctx.r));
        switch (ctx.op.getType()) {
            case SysYLexer.MUL:
                return irBuilder.buildIntMul(l, r, WrapSemantics.Unspecified, Option.of("Mul"));
            case SysYLexer.DIV:
                return irBuilder.buildSignedDiv(l, r, false, Option.of("Div"));
            case SysYLexer.MOD:
                return irBuilder.buildSignedRem(l, r, Option.of("Mod"));
            default:
                throw new IllegalArgumentException("Unsupported operator: " + ctx.op.getText());
        }
    }

    @Override
    public Value visitAdds(SysYParser.AddsContext ctx) {
        var l = castHelper.elevate(visit(ctx.l));
        var r = castHelper.elevate(visit(ctx.r));
        switch (ctx.op.getType()) {
            case SysYLexer.PLUS:
                return irBuilder.buildIntAdd(l, r, WrapSemantics.Unspecified, Option.of("Add"));
            case SysYLexer.MINUS:
                return irBuilder.buildIntSub(l, r, WrapSemantics.Unspecified, Option.of("Sub"));
            default:
                throw new IllegalArgumentException("Unsupported operator: " + ctx.op.getText());
        }
    }

    @SuppressWarnings("unchecked")
    private Value buildShortcut(SysYParser.ExprContext lExp, SysYParser.ExprContext rExp, boolean shortcutOn) {
        var l = castHelper.convertTo(visit(lExp), BOOL_TYPE);
        var oldBlock = currentBlock;
        var rightBlock = context.newBasicBlock("RightPath");
        var mergeBlock = context.newBasicBlock("Merge");
        currentFunction.addBasicBlock(rightBlock);
        currentFunction.addBasicBlock(mergeBlock);
        if (shortcutOn) irBuilder.buildConditionalBranch(l, mergeBlock, rightBlock);
        else irBuilder.buildConditionalBranch(l, rightBlock, mergeBlock);
        switchBlock(rightBlock);
        var r = castHelper.convertTo(visit(rExp), BOOL_TYPE);
        rightBlock = currentBlock;
        irBuilder.buildBranch(mergeBlock);
        switchBlock(mergeBlock);
        var res = irBuilder.buildPhi(BOOL_TYPE, Option.of("ShortcutMerge"));
        res.addIncoming(new Pair<>(oldBlock, shortcutOn ? TRUE : FALSE), new Pair<>(rightBlock, r));
        return res;
    }

    @Override
    public Value visitOr(SysYParser.OrContext ctx) {
        return buildShortcut(ctx.l, ctx.r, true);
    }

    @Override
    public Value visitAnd(SysYParser.AndContext ctx) {
        return buildShortcut(ctx.l, ctx.r, false);
    }

    @Override
    public Value visitEqs(SysYParser.EqsContext ctx) {
        var l = castHelper.elevate(visit(ctx.l));
        var r = castHelper.elevate(visit(ctx.r));
        switch (ctx.op.getType()) {
            case SysYLexer.EQ:
                return irBuilder.buildIntCompare(IntPredicate.Equal, l, r, Option.of("Eq"));
            case SysYLexer.NEQ:
                return irBuilder.buildIntCompare(IntPredicate.NotEqual, l, r, Option.of("Neq"));
            default:
                throw new IllegalArgumentException("Unsupported operator: " + ctx.op.getText());
        }
    }

    @Override
    public Value visitRels(SysYParser.RelsContext ctx) {
        var l = castHelper.elevate(visit(ctx.l));
        var r = castHelper.elevate(visit(ctx.r));
        switch (ctx.op.getType()) {
            case SysYLexer.LT:
                return irBuilder.buildIntCompare(IntPredicate.SignedLessThan, l, r, Option.of("LT"));
            case SysYLexer.GT:
                return irBuilder.buildIntCompare(IntPredicate.SignedGreaterThan, l, r, Option.of("GT"));
            case SysYLexer.LE:
                return irBuilder.buildIntCompare(IntPredicate.SignedLessEqual, l, r, Option.of("LE"));
            case SysYLexer.GE:
                return irBuilder.buildIntCompare(IntPredicate.SignedGreaterEqual, l, r, Option.of("GE"));
            default:
                throw new IllegalArgumentException("Unsupported operator: " + ctx.op.getText());
        }
    }

    @Override
    public Value visitAssignment(SysYParser.AssignmentContext ctx) {
        var ptr = visit(ctx.lvalue);
        var value = visit(ctx.value);
        var ptrType = typeHelper.ensurePointerType(ptr.getType()).getElementType();
        if (!typeHelper.convertibleTo(value.getType(), ptrType)) throw new IllegalArgumentException("Type mismatch");
        return irBuilder.buildStore(ptr, castHelper.convertTo(value, ptrType));
    }

    @Override
    public Value visitIf(SysYParser.IfContext ctx) {
        var cond = castHelper.convertTo(visit(ctx.cond), BOOL_TYPE);
        var oldBlock = currentBlock;
        var trueBlock = context.newBasicBlock("IfTrue");
        var mergeBlock = context.newBasicBlock("IfMerge");
        currentFunction.addBasicBlock(trueBlock);
        currentFunction.addBasicBlock(mergeBlock);
        switchBlock(trueBlock);
        visit(ctx.stmtTrue);
        if (!blockEnded) irBuilder.buildBranch(mergeBlock);

        var falseBlock = mergeBlock;
        if (ctx.stmtFalse != null) {
            falseBlock = context.newBasicBlock("IfFalse");
            currentFunction.addBasicBlock(falseBlock);
            switchBlock(falseBlock);
            visit(ctx.stmtFalse);
            if (!blockEnded) irBuilder.buildBranch(mergeBlock);
        }

        switchBlock(oldBlock);
        irBuilder.buildConditionalBranch(cond, trueBlock, falseBlock);
        switchBlock(mergeBlock);
        return null;
    }

    @Override
    public Value visitWhile(SysYParser.WhileContext ctx) {
        var condBlock = context.newBasicBlock("WhileCond");
        currentFunction.addBasicBlock(condBlock);
        irBuilder.buildBranch(condBlock);
        switchBlock(condBlock);
        var cond = castHelper.convertTo(visit(ctx.cond), BOOL_TYPE);
        var loopBlock = context.newBasicBlock("WhileLoop");
        var mergeBlock = context.newBasicBlock("WhileMerge");
        currentFunction.addBasicBlock(loopBlock);
        currentFunction.addBasicBlock(mergeBlock);
        irBuilder.buildConditionalBranch(cond, loopBlock, mergeBlock);
        switchBlock(loopBlock);

        var lastCond = currentCondBlock;
        var lastMerge = currentMergeBlock;
        currentCondBlock = condBlock;
        currentMergeBlock = mergeBlock;
        visit(ctx.stmtTrue);
        currentCondBlock = lastCond;
        currentMergeBlock = lastMerge;

        if (!blockEnded) irBuilder.buildBranch(condBlock);
        switchBlock(mergeBlock);
        return null;
    }

    @Override
    public Value visitBreak(SysYParser.BreakContext ctx) {
        markBlockEnd();
        return irBuilder.buildBranch(Objects.requireNonNull(currentMergeBlock));
    }

    @Override
    public Value visitContinue(SysYParser.ContinueContext ctx) {
        markBlockEnd();
        return irBuilder.buildBranch(Objects.requireNonNull(currentCondBlock));
    }

    @Override
    public Value visitReturn(SysYParser.ReturnContext ctx) {
        markBlockEnd();
        return irBuilder.buildReturn(ctx.ret == null ? Option.empty() : Option.of(visit(ctx.ret)));
    }

    private interface AggregateHelper<R> {
        int aggregateCount(Type type);

        Type aggregateType(Type type, int index);

        R fromValue(Value value);

        boolean isAggregateAtom(Type type);

        R aggregate(Type type, List<R> content);
    }

    private static class SymbolContext {
        private final SymbolContext parent;
        private final Map<String, Value> symbols = new HashMap<>();

        public SymbolContext(SymbolContext parent) {
            this.parent = parent;
        }

        public boolean containsLocal(String name) {
            return symbols.containsKey(name);
        }

        public Value lookup(String name) {
            if (containsLocal(name)) return symbols.get(name);
            if (parent != null) return parent.lookup(name);
            return null;
        }

        public void define(String name, Value value) {
            if (containsLocal(name)) return;
            symbols.put(name, value);
        }
    }

    private class CastHelper {
        public Constant asConstant(Value value) {
            return new Constant(value.getRef());
        }

        public Value convertTo(Value value, Type target) {
            if (typeHelper.sameType(value.getType(), target)) return value;
            if (!target.isIntegerType()) throw new UnsupportedOperationException();
            var type = typeHelper.asIntegerType(target);
            var vType = typeHelper.ensureIntegerType(value.getType());
            var vWidth = vType.getTypeWidth();
            var tWidth = type.getTypeWidth();
            if (vWidth == tWidth) return value;
            if (typeHelper.sameType(vType, BOOL_TYPE)) return irBuilder.buildZeroExt(value, type, Option.of("BoolExt"));
            if (vWidth < tWidth) return irBuilder.buildSignExt(value, type, Option.of("IntExt"));
            if (typeHelper.sameType(type, BOOL_TYPE))
                return irBuilder.buildIntCompare(IntPredicate.NotEqual, value, vType.getConstant(0, false),
                        Option.of("ToBool"));
            return irBuilder.buildIntTrunc(value, type, Option.of("Trunc"));
        }

        public Value elevate(Value value) {
            return convertTo(value, INT_TYPE);
        }
    }

    private class DerefHelper {
        private final List<Value> cache = new ArrayList<>();
        private Value value;
        private PointerType refType;
        private PointerType cacheType;

        public void begin(Value value) {
            this.value = value;
            this.refType = typeHelper.ensurePointerType(value.getType());
            this.cacheType = refType;
            this.cache.add(ZERO);
        }

        private Type subType(Type type) {
            Type subType = null;
            if (type.isArrayType()) subType = typeHelper.asArrayType(type).getElementType();
            if (type.isPointerType()) subType = typeHelper.asPointerType(type).getElementType();
            if (subType == null) throw new IllegalArgumentException("Cannot deref type: " + type.getAsString());
            return subType;
        }

        private void applyCache() {
            value = irBuilder.buildGetElementPtr(value, cache.toArray(new Value[0]), Option.of("CacheDeref"), true);
            cache.clear();
            refType = cacheType;
        }

        public void deref(Value index) {
            var ref = cacheType.getElementType();
            if (ref.isPointerType()) {
                applyCache();
                value = irBuilder.buildLoad(value, Option.of("LoadPtr"));
                cache.add(index);
                cacheType = typeHelper.asPointerType(ref);
            } else {
                cache.add(index);
                cacheType = context.getPointerType(subType(ref), AddressSpace.Generic.INSTANCE).unwrap();
            }
        }

        public Value end() {
            applyCache();
            var res = value;
            value = null;
            refType = null;
            cacheType = null;
            return res;
        }
    }

    private class AggregateBuilder<R> {
        private final AggregateHelper<R> aggregateHelper;
        private final Deque<Pair<Type, ArrayList<R>>> stack = new ArrayDeque<>();
        private int depth;
        private R result;

        public AggregateBuilder(AggregateHelper<R> aggregateHelper) {
            this.aggregateHelper = aggregateHelper;
        }

        public void begin(Type type) {
            stack.push(new Pair<>(type, new ArrayList<>()));
            depth = 1;
            result = null;
        }

        public R end() {
            depth = 0;
            fold();
            return result;
        }

        private <T> Type nextLayerType(Pair<Type, ArrayList<T>> layer) {
            return aggregateHelper.aggregateType(layer.getFirst(), layer.getSecond().size());
        }

        private void unfoldOnce() {
            stack.push(new Pair<>(nextLayerType(Objects.requireNonNull(stack.peek())), new ArrayList<>()));
        }

        private void unfoldUntil(Type type) {
            if (stack.isEmpty()) return;
            while (true) {
                var next = nextLayerType(Objects.requireNonNull(stack.peek()));
                if (typeHelper.convertibleTo(type, next)) break;
                if (aggregateHelper.isAggregateAtom(next))
                    throw new IllegalArgumentException("Unexpected type: " + type.getAsString());
                unfoldOnce();
            }
        }

        private R foldHead() {
            var layer = stack.pop();
            return aggregateHelper.aggregate(layer.getFirst(), layer.getSecond());
        }

        private void foldOnce() {
            addResult(foldHead());
        }

        public void addValue(Value value) {
            unfoldUntil(value.getType());
            var layer = stack.peek();
            if (layer != null) value = castHelper.convertTo(value, nextLayerType(layer));
            addResult(aggregateHelper.fromValue(value));
        }

        public void addResult(R layerResult) {
            if (stack.isEmpty()) {
                result = layerResult;
                return;
            }
            var layer = Objects.requireNonNull(stack.peek());
            layer.getSecond().add(layerResult);
            if (aggregateHelper.aggregateCount(layer.getFirst()) == layer.getSecond().size()) foldOnce();
        }

        private void fold() {
            while (stack.size() > depth) foldOnce();
        }

        public void beginAggregate() {
            fold();
            ++depth;
            unfoldOnce();
        }

        public void endAggregate() {
            --depth;
            fold();
        }
    }

    private class TypeHelper {
        public Type fromBasicType(SysYParser.BasicTypeContext ignoredContext) {
            return INT_TYPE;
        }

        public Type fromRetType(SysYParser.RetTypeContext context) {
            var basic = context.basicType();
            if (basic != null) return fromBasicType(basic);
            return VOID_TYPE;
        }

        public Type applyArray(Type type, SysYParser.ArrayPostfixContext context) {
            var singles = context.arrayPostfixSingle();
            Collections.reverse(singles);
            for (var single : singles) type = applyArraySingle(type, single);
            return type;
        }

        public Type applyArraySingle(Type type, SysYParser.ArrayPostfixSingleContext single) {
            var length = visit(single.expr());
            if (!(length instanceof ConstantInt)) throw new IllegalArgumentException("Length must be a constant int");
            return context.getArrayType(type, Math.toIntExact(((ConstantInt) length).getZeroExtendedValue())).unwrap();
        }

        public Type fromFuncParam(SysYParser.FuncParamContext param) {
            var res = fromBasicType(param.basicType());
            var postfix = param.arrayPostfix();
            if (postfix != null) res = applyArray(res, postfix);
            if (param.incompleteArray() != null)
                res = context.getPointerType(res, AddressSpace.Generic.INSTANCE).unwrap();
            return res;
        }

        public FunctionType fromFuncDef(SysYParser.FuncDefContext ctx) {
            return context.getFunctionType(fromRetType(ctx.r_type),
                    ctx.funcParam().stream().map(this::fromFuncParam).toArray(Type[]::new), false);
        }

        public boolean sameType(Type a, Type b) {
            return a.getRef().equals(b.getRef());
        }

        public boolean convertibleTo(Type from, Type to) {
            return sameType(from, to) || (from.isIntegerType() && to.isIntegerType());
        }

        public ArrayType asArrayType(Type type) {
            return new ArrayType(type.getRef());
        }

        public IntegerType asIntegerType(Type type) {
            return new IntegerType(type.getRef());
        }

        public IntegerType ensureIntegerType(Type type) {
            if (!type.isIntegerType()) throw new IllegalArgumentException("Type must be an integer type");
            return asIntegerType(type);
        }

        public PointerType asPointerType(Type type) {
            return new PointerType(type.getRef());
        }

        public PointerType ensurePointerType(Type type) {
            if (!type.isPointerType()) throw new IllegalArgumentException("Type must be a pointer type");
            return asPointerType(type);
        }
    }

    private abstract class SimpleAggregateHelper<R> implements AggregateHelper<R> {
        @Override
        public int aggregateCount(Type type) {
            if (typeHelper.sameType(type, INT_TYPE)) return 1;
            if (type.isArrayType()) return typeHelper.asArrayType(type).getElementCount();
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        @Override
        public Type aggregateType(Type type, int index) {
            if (index >= aggregateCount(type)) throw new IndexOutOfBoundsException("Index out of bounds: " + index);
            if (typeHelper.sameType(type, INT_TYPE)) return INT_TYPE;
            if (type.isArrayType()) return typeHelper.asArrayType(type).getElementType();
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        @Override
        public boolean isAggregateAtom(Type type) {
            return typeHelper.sameType(type, INT_TYPE);
        }

        public abstract R aggregateDefault(Type type);

        protected void padContent(Type type, List<R> content) {
            var needed = aggregateCount(type);
            if (content.size() > needed) throw new IllegalArgumentException("Got more content than expected");
            while (content.size() < needed) content.add(aggregateDefault(aggregateType(type, content.size())));
        }
    }

    public class ConstantOrInitializer {
        private final Constant constant;
        private final Consumer<Value> initializer;

        public ConstantOrInitializer(Constant constant) {
            this.constant = constant;
            this.initializer = null;
        }

        public ConstantOrInitializer(Consumer<Value> initializer) {
            this.constant = null;
            this.initializer = initializer;
        }

        public boolean isConstant() {
            return constant != null;
        }

        public Constant getConstant() {
            return constant;
        }

        public Consumer<Value> toInitializer() {
            if (constant != null) return l -> irBuilder.buildStore(l, constant);
            return initializer;
        }
    }

    private class InitializerAggregateHelper extends SimpleAggregateHelper<ConstantOrInitializer> {
        private final ConstantAggregateHelper constantAggregateHelper = new ConstantAggregateHelper();

        @Override
        public ConstantOrInitializer fromValue(Value value) {
            if (value.isConstant()) return new ConstantOrInitializer(castHelper.asConstant(value));
            return new ConstantOrInitializer(l -> irBuilder.buildStore(l, value));
        }

        @Override
        public ConstantOrInitializer aggregateDefault(Type type) {
            return new ConstantOrInitializer(constantAggregateHelper.aggregateDefault(type));
        }

        @Override
        public ConstantOrInitializer aggregate(Type type, List<ConstantOrInitializer> content) {
            padContent(type, content);

            if (typeHelper.sameType(type, INT_TYPE)) return content.get(0);
            if (type.isArrayType()) {
                if (content.stream().allMatch(ConstantOrInitializer::isConstant)) return new ConstantOrInitializer(
                        constantAggregateHelper.aggregate(type,
                                content.stream().map(ConstantOrInitializer::getConstant).collect(Collectors.toList())));

                Consumer<Value> res = l -> {
                };
                for (int i = 0; i < content.size(); ++i) {
                    var finalI = i;
                    var elemInit = content.get(i).toInitializer();
                    res = res.andThen(l -> elemInit.accept(
                            irBuilder.buildGetElementPtr(l, new Value[]{INT_TYPE.getConstant(finalI, false)},
                                    Option.of("InitPtr"), true)));
                }
                return new ConstantOrInitializer(res);
            }
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    private class ConstantAggregateHelper extends SimpleAggregateHelper<Constant> {
        @Override
        public Constant fromValue(Value value) {
            if (value.isConstant()) return castHelper.asConstant(value);
            throw new IllegalArgumentException("Non-constant value: " + value);
        }

        @Override
        public Constant aggregateDefault(Type type) {
            if (typeHelper.sameType(type, INT_TYPE)) return ZERO;
            if (type.isArrayType()) return typeHelper.asArrayType(type).getElementType().getConstantArray(
                    IntStream.range(0, aggregateCount(type)).mapToObj(i -> aggregateDefault(aggregateType(type, i)))
                            .toArray(Constant[]::new));
            throw new IllegalArgumentException("Unsupported type: " + type);
        }

        @Override
        public Constant aggregate(Type type, List<Constant> content) {
            padContent(type, content);

            if (typeHelper.sameType(type, INT_TYPE)) return content.get(0);
            if (type.isArrayType())
                return typeHelper.asArrayType(type).getElementType().getConstantArray(content.toArray(new Constant[0]));
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }
}