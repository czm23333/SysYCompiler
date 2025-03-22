import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.stream.Collectors;

public class SysYSemanticsChecker extends SysYParserBaseVisitor<ValueWithType> {
    public static final ValueWithType RIGHT_BOOL = new ValueWithType(ValueType.RIGHT, BasicType.BOOL);
    public static final ValueWithType RIGHT_INT = new ValueWithType(ValueType.RIGHT, BasicType.INT);
    public static final ValueWithType RIGHT_VOID = new ValueWithType(ValueType.RIGHT, VoidType.VOID);
    public static final ValueWithType LEFT_ERROR = new ValueWithType(ValueType.LEFT, ErroneousType.ERROR);
    private final Deque<Context> contextStack = new ArrayDeque<>();
    private FunctionType currentFunctionType = null;

    private Context currentContext() {
        return contextStack.peekLast();
    }

    private Context popContext() {
        return contextStack.removeLast();
    }

    private void pushContext(Context context) {
        contextStack.addLast(context);
    }

    private static void reportError(int line, SemanticError error) {
        System.err.printf("Error type %d at Line %d: %s\n", error.id, line, error.message);
    }

    @Override
    public ValueWithType visitVarDef(SysYParser.VarDefContext ctx) {
        var context = currentContext();
        var valueType = TypeUtil.valueTypeFromConstPrefix(ctx.constPrefix());
        var type = TypeUtil.typeFromBasicType(ctx.type);
        for (var entry : ctx.varDefEntry()) {
            visit(entry);
            var varName = entry.name.getText();
            if (context.containsLocal(varName)) {
                reportError(entry.name.getLine(), SemanticError.VARIABLE_REDECLARATION);
                continue;
            }
            context.define(varName, new ValueWithType(valueType, TypeUtil.applyVarDefEntry(type, entry)));
        }
        return defaultResult();
    }

    @Override
    public ValueWithType visitProgram(SysYParser.ProgramContext ctx) {
        currentFunctionType = null;
        pushContext(new Context());
        var result = super.visitProgram(ctx);
        popContext();
        return result;
    }

    @Override
    public ValueWithType visitFuncDef(SysYParser.FuncDefContext ctx) {
        var context = currentContext();
        var name = ctx.name.getText();
        if (context.containsLocal(name)) {
            reportError(ctx.name.getLine(), SemanticError.FUNCTION_REDECLARATION);
            return defaultResult();
        }

        currentFunctionType = new FunctionType(TypeUtil.typeFromRetType(ctx.retType()),
                ctx.funcParam().stream().map(TypeUtil::typeFromFuncParam).collect(Collectors.toList()));
        context.define(name, new ValueWithType(ValueType.RIGHT, currentFunctionType));

        context = new Context(context);
        pushContext(context);

        for (var param : ctx.funcParam()) {
            var paramName = param.name.getText();
            if (context.containsLocal(paramName)) {
                reportError(param.name.getLine(), SemanticError.VARIABLE_REDECLARATION);
                continue;
            }
            context.define(paramName, new ValueWithType(ValueType.LEFT, TypeUtil.typeFromFuncParam(param)));
        }

        var result = super.visitFuncDef(ctx);
        popContext();
        currentFunctionType = null;
        return result;
    }

    @Override
    public ValueWithType visitArrayPostfixSingle(SysYParser.ArrayPostfixSingleContext ctx) {
        var index = visit(ctx.expr());
        if (!index.convertibleTo(new ValueWithType(ValueType.RIGHT, BasicType.INT)))
            reportError(ctx.expr().start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
        return defaultResult();
    }

    @Override
    public ValueWithType visitVarAccess(SysYParser.VarAccessContext ctx) {
        var variableName = ctx.IDENT().getText();
        var context = currentContext();
        if (!context.contains(variableName)) {
            reportError(ctx.IDENT().getSymbol().getLine(), SemanticError.UNDEFINED_VARIABLE);
            return LEFT_ERROR;
        }

        var variable = context.lookup(variableName);
        var type = variable.type;
        visit(ctx.arrayPostfix());
        for (var single : ctx.arrayPostfix().arrayPostfixSingle()) {
            if (type instanceof AbstractArrayType) type = ((AbstractArrayType) type).elementType;
            else {
                reportError(single.start.getLine(), SemanticError.ILLEGAL_INDEXING);
                return LEFT_ERROR;
            }
        }

        return new ValueWithType(variable.valueType, type);
    }

    @Override
    public ValueWithType visitFunctionCall(SysYParser.FunctionCallContext ctx) {
        var functionName = ctx.func.getText();
        var context = currentContext();
        if (!context.contains(functionName)) {
            reportError(ctx.func.getLine(), SemanticError.UNDEFINED_FUNCTION);
            return LEFT_ERROR;
        }

        var function = context.lookup(functionName);
        if (!(function.valueType.convertibleTo(ValueType.RIGHT) && function.type instanceof FunctionType)) {
            reportError(ctx.func.getLine(), SemanticError.ILLEGAL_FUNCTION_CALL);
            return LEFT_ERROR;
        }

        var functionType = (FunctionType) function.type;
        var realParams = ctx.funcRealParam().stream().map(this::visit).collect(Collectors.toList());
        int mismatch = -1;
        if (functionType.parameters.size() != realParams.size()) mismatch = ctx.start.getLine();
        else {
            for (int i = 0; i < realParams.size(); ++i)
                if (!realParams.get(i)
                        .convertibleTo(new ValueWithType(ValueType.RIGHT, functionType.parameters.get(i)))) {
                    mismatch = ctx.funcRealParam().get(i).start.getLine();
                    break;
                }
        }

        if (mismatch != -1) {
            reportError(mismatch, SemanticError.FUNCTION_PARAM_MISMATCH);
            return LEFT_ERROR;
        }

        return new ValueWithType(ValueType.RIGHT, functionType.returnType);
    }

    @Override
    public ValueWithType visitConst(SysYParser.ConstContext ctx) {
        return new ValueWithType(ValueType.RIGHT, BasicType.INT);
    }

    @Override
    public ValueWithType visitUnary(SysYParser.UnaryContext ctx) {
        var operator = ctx.op.getText();
        var operand = visit(ctx.expr());
        if ("!".equals(operator)) {
            if (!operand.convertibleTo(RIGHT_BOOL)) {
                reportError(ctx.expr().start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
                return LEFT_ERROR;
            }
            return RIGHT_BOOL;
        } else {
            if (!operand.convertibleTo(RIGHT_INT)) {
                reportError(ctx.expr().start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
                return LEFT_ERROR;
            }
            return RIGHT_INT;
        }
    }

    @Override
    public ValueWithType visitMuls(SysYParser.MulsContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_INT)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_INT)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_INT;
    }

    @Override
    public ValueWithType visitAdds(SysYParser.AddsContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_INT)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_INT)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_INT;
    }

    @Override
    public ValueWithType visitOr(SysYParser.OrContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_BOOL;
    }

    @Override
    public ValueWithType visitEqs(SysYParser.EqsContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_INT)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_INT)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_INT;
    }

    @Override
    public ValueWithType visitAnd(SysYParser.AndContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_BOOL;
    }

    @Override
    public ValueWithType visitRels(SysYParser.RelsContext ctx) {
        var left = visit(ctx.l);
        var right = visit(ctx.r);
        if (!left.convertibleTo(RIGHT_INT)) {
            reportError(ctx.l.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        if (!right.convertibleTo(RIGHT_INT)) {
            reportError(ctx.r.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return LEFT_ERROR;
        }
        return RIGHT_INT;
    }

    @Override
    public ValueWithType visitStmtBlock(SysYParser.StmtBlockContext ctx) {
        pushContext(new Context(currentContext()));
        var result = super.visitStmtBlock(ctx);
        popContext();
        return result;
    }

    @Override
    public ValueWithType visitAssignment(SysYParser.AssignmentContext ctx) {
        var left = visit(ctx.lvalue);
        var right = visit(ctx.value);
        if (!left.valueType.convertibleTo(ValueType.LEFT)) {
            reportError(ctx.lvalue.start.getLine(), SemanticError.ILLEGAL_ASSIGN);
            return LEFT_ERROR;
        }

        if (!right.convertibleTo(new ValueWithType(ValueType.RIGHT, left.type))) {
            reportError(ctx.value.start.getLine(), SemanticError.ASSIGN_TYPE_MISMATCH);
            return LEFT_ERROR;
        }

        return left;
    }

    @Override
    public ValueWithType visitIf(SysYParser.IfContext ctx) {
        var condition = visit(ctx.cond);
        if (!condition.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.cond.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return defaultResult();
        }

        visit(ctx.stmtTrue);
        if (ctx.stmtFalse != null) visit(ctx.stmtFalse);
        return defaultResult();
    }

    @Override
    public ValueWithType visitWhile(SysYParser.WhileContext ctx) {
        var condition = visit(ctx.cond);
        if (!condition.convertibleTo(RIGHT_BOOL)) {
            reportError(ctx.cond.start.getLine(), SemanticError.OPERATOR_TYPE_MISMATCH);
            return defaultResult();
        }

        visit(ctx.stmtTrue);
        return defaultResult();
    }

    @Override
    public ValueWithType visitReturn(SysYParser.ReturnContext ctx) {
        Objects.requireNonNull(currentFunctionType);
        var result = visit(ctx.ret);
        if (!result.convertibleTo(new ValueWithType(ValueType.RIGHT, currentFunctionType.returnType)))
            reportError(ctx.ret.start.getLine(), SemanticError.RETURN_TYPE_MISMATCH);
        return defaultResult();
    }

    @Override
    protected ValueWithType aggregateResult(ValueWithType aggregate, ValueWithType nextResult) {
        if (defaultResult().equals(nextResult)) return aggregate;
        return nextResult;
    }

    @Override
    protected ValueWithType defaultResult() {
        return RIGHT_VOID;
    }
}