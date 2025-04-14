import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;
import java.util.stream.Collectors;

public class SysYSemanticsChecker extends SysYParserBaseVisitor<SysYSemanticsChecker.ValueWithType> {
    public static final ValueWithType RIGHT_BOOL = new ValueWithType(ValueType.RIGHT, BasicType.BOOL);
    public static final ValueWithType RIGHT_INT = new ValueWithType(ValueType.RIGHT, BasicType.INT);
    public static final ValueWithType RIGHT_VOID = new ValueWithType(ValueType.RIGHT, VoidType.VOID);
    public static final ValueWithType LEFT_ERROR = new ValueWithType(ValueType.LEFT, ErroneousType.ERROR);
    public boolean hasError = false;
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

    private void reportError(int line, SemanticError error) {
        hasError = true;
        System.err.printf("Error type %d at Line %d: %s\n", error.id, line, error.message);
    }

    @Override
    public ValueWithType visit(ParseTree tree) {
        if (tree == null) return RIGHT_VOID;
        return super.visit(tree);
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

        var newContext = new Context(context);
        pushContext(newContext);

        List<Type> paramTypes = new ArrayList<>();
        for (var param : ctx.funcParam()) {
            var paramName = param.name.getText();
            if (newContext.containsLocal(paramName)) {
                reportError(param.name.getLine(), SemanticError.VARIABLE_REDECLARATION);
                continue;
            }
            var paramType = TypeUtil.typeFromFuncParam(param);
            newContext.define(paramName, new ValueWithType(ValueType.LEFT, paramType));
            paramTypes.add(paramType);
        }
        currentFunctionType = new FunctionType(TypeUtil.typeFromRetType(ctx.retType()), paramTypes);
        context.define(name, new ValueWithType(ValueType.RIGHT, currentFunctionType));

        var result = super.visitFuncDef(ctx);
        popContext();
        currentFunctionType = null;
        return result;
    }

    @Override
    public ValueWithType visitArrayPostfixSingle(SysYParser.ArrayPostfixSingleContext ctx) {
        var index = visit(ctx.expr());
        if (!index.convertibleTo(RIGHT_INT))
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
        boolean flag = false;
        for (var single : ctx.arrayPostfix().arrayPostfixSingle()) {
            if (type instanceof AbstractArrayType) type = ((AbstractArrayType) type).elementType;
            else {
                reportError(single.start.getLine(), SemanticError.ILLEGAL_INDEXING);
                flag = true;
            }
        }
        if (flag) return LEFT_ERROR;

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
        boolean flag = false;
        if (functionType.parameters.size() != realParams.size()) {
            reportError(ctx.start.getLine(), SemanticError.FUNCTION_PARAM_MISMATCH);
            flag = true;
        } else {
            for (int i = 0; i < realParams.size(); ++i)
                if (!realParams.get(i)
                        .convertibleTo(new ValueWithType(ValueType.RIGHT, functionType.parameters.get(i)))) {
                    reportError(ctx.funcRealParam().get(i).start.getLine(), SemanticError.FUNCTION_PARAM_MISMATCH);
                    flag = true;
                }
        }
        if (flag) return LEFT_ERROR;

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
        return RIGHT_BOOL;
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
        return RIGHT_BOOL;
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

    public abstract static class AbstractArrayType extends DummyType {
        public final Type elementType;

        public AbstractArrayType(Type elementType) {
            this.elementType = elementType;
        }

        @Override
        public boolean convertibleTo(Type other) {
            if (super.convertibleTo(other)) return true;
            if (other instanceof AbstractArrayType) return elementType.convertibleTo(((AbstractArrayType) other).elementType);
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AbstractArrayType)) return false;
            AbstractArrayType that = (AbstractArrayType) o;
            return Objects.equals(elementType, that.elementType);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(elementType);
        }
    }

    public static class ArrayType extends AbstractArrayType {
        public final int length;

        public ArrayType(Type elementType, int length) {
            super(elementType);
            this.length = length;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ArrayType)) return false;
            if (!super.equals(o)) return false;
            ArrayType arrayType = (ArrayType) o;
            return length == arrayType.length;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), length);
        }
    }

    public enum BasicType implements Type {
        INT, BOOL;

        @Override
        public boolean convertibleTo(Type other) {
            if (other instanceof ErroneousType) return true;
            if (equals(other)) return true;
            if (this == INT && other == BOOL) return true;
            return this == BOOL && other == INT;
        }
    }

    public static class Context {
        private final Context parent;
        private final Map<String, ValueWithType> symbols = new HashMap<>();

        public Context() {
            this.parent = null;
        }

        public Context(Context parent) {
            this.parent = parent;
        }

        public boolean containsLocal(String name) {
            return symbols.containsKey(name);
        }

        public ValueWithType lookup(String name) {
            if (containsLocal(name)) return symbols.get(name);
            if (parent != null) return parent.lookup(name);
            return null;
        }

        public boolean contains(String name) {
            if (containsLocal(name)) return true;
            if (parent != null) return parent.contains(name);
            return false;
        }

        public boolean define(String name, ValueWithType value) {
            if (containsLocal(name)) return false;
            symbols.put(name, value);
            return true;
        }
    }

    public abstract static class DummyType implements Type {
    }

    public enum ErroneousType implements Type {
        ERROR;

        @Override
        public boolean convertibleTo(Type other) {
            return true;
        }
    }

    public static class FunctionType extends DummyType {
        public final Type returnType;
        public final List<Type> parameters;

        public FunctionType(Type returnType, List<Type> parameters) {
            this.returnType = returnType;
            this.parameters = List.copyOf(parameters);
        }

        @Override
        public boolean convertibleTo(Type other) {
            if (super.convertibleTo(other)) return true;
            if (other instanceof FunctionType) {
                var functionType = (FunctionType) other;

                if (!returnType.convertibleTo(functionType.returnType)) return false;

                var parameterTypes = functionType.parameters;
                if (parameterTypes.size() != parameters.size()) return false;
                for (int i = 0; i < parameters.size(); ++i)
                    if (!parameterTypes.get(i).convertibleTo(parameters.get(i))) return false;
                return true;
            }
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FunctionType)) return false;
            FunctionType that = (FunctionType) o;
            return Objects.equals(returnType, that.returnType) && Objects.equals(parameters, that.parameters);
        }

        @Override
        public int hashCode() {
            return Objects.hash(returnType, parameters);
        }
    }

    public static class IncompleteArrayType extends AbstractArrayType {
        public IncompleteArrayType(Type elementType) {
            super(elementType);
        }
    }

    public enum SemanticError {
        UNDEFINED_VARIABLE(1, "Using undefined variable"),
        UNDEFINED_FUNCTION(2, "Using undefined function"),
        VARIABLE_REDECLARATION(3, "Redeclaration of variable"),
        FUNCTION_REDECLARATION(4, "Redeclaration of function"),
        ASSIGN_TYPE_MISMATCH(5, "SysYSemanticsChecker.Type of assignment mismatch"),
        OPERATOR_TYPE_MISMATCH(6, "SysYSemanticsChecker.Type of operator mismatch"),
        RETURN_TYPE_MISMATCH(7, "Return type mismatch"),
        FUNCTION_PARAM_MISMATCH(8, "Provided parameters mismatch with function signature"),
        ILLEGAL_INDEXING(9, "Indexing on illegal type"),
        ILLEGAL_FUNCTION_CALL(10, "Calling on illegal type"),
        ILLEGAL_ASSIGN(11, "Assigning on illegal value type");

        public final int id;
        public final String message;
        SemanticError(int id, String message) {
            this.id = id;
            this.message = message;
        }
    }

    public interface Type {
        default boolean convertibleTo(Type other) {
            return other instanceof ErroneousType || equals(other);
        }
    }

    public static class TypeUtil {
        public static Type applyArrayPostfix(Type type, SysYParser.ArrayPostfixContext postfix) {
            var dimension = postfix.arrayPostfixSingle().size();
            // TODO: Calculate length from postfix
            for (int i = 0; i < dimension; ++i) type = new ArrayType(type, 1);
            return type;
        }

        public static Type applyIncompleteArray(Type type, SysYParser.IncompleteArrayContext incompleteArray) {
            return new IncompleteArrayType(type);
        }

        public static Type applyVarDefEntry(Type type, SysYParser.VarDefEntryContext varDefEntry) {
            return applyArrayPostfix(type, varDefEntry.arrayPostfix());
        }

        public static Type typeFromBasicType(SysYParser.BasicTypeContext basicType) {
            return BasicType.INT;
        }

        public static Type typeFromRetType(SysYParser.RetTypeContext retType) {
            if (retType.basicType() != null) return typeFromBasicType(retType.basicType());
            return VoidType.VOID;
        }

        public static Type typeFromFuncParam(SysYParser.FuncParamContext funcParam) {
            var result = typeFromBasicType(funcParam.type);
            if (funcParam.arrayPostfix() != null) result = applyArrayPostfix(result, funcParam.arrayPostfix());
            if (funcParam.incompleteArray() != null) result = applyIncompleteArray(result, funcParam.incompleteArray());
            return result;
        }

        public static ValueType valueTypeFromConstPrefix(SysYParser.ConstPrefixContext constPrefix) {
            if (constPrefix.CONST() == null) return ValueType.LEFT;
            return ValueType.RIGHT;
        }
    }

    public enum ValueType {
        LEFT, RIGHT;

        public boolean convertibleTo(ValueType type) {
            if (type == RIGHT) return true;
            return this == LEFT;
        }
    }

    public static class ValueWithType {
        public final ValueType valueType;
        public final Type type;

        public ValueWithType(ValueType valueType, Type type) {
            this.valueType = valueType;
            this.type = type;
        }

        public boolean convertibleTo(ValueWithType other) {
            return valueType.convertibleTo(other.valueType) && type.convertibleTo(other.type);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ValueWithType)) return false;
            ValueWithType that = (ValueWithType) o;
            return valueType == that.valueType && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(valueType, type);
        }
    }

    public enum VoidType implements Type {
        VOID
    }
}