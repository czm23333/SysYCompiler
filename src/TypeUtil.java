public class TypeUtil {
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