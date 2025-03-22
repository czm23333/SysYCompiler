public enum SemanticError {
    UNDEFINED_VARIABLE(1, "Using undefined variable"),
    UNDEFINED_FUNCTION(2, "Using undefined function"),
    VARIABLE_REDECLARATION(3, "Redeclaration of variable"),
    FUNCTION_REDECLARATION(4, "Redeclaration of function"),
    ASSIGN_TYPE_MISMATCH(5, "Type of assignment mismatch"),
    OPERATOR_TYPE_MISMATCH(6, "Type of operator mismatch"),
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