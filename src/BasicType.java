public enum BasicType implements Type {
    INT, BOOL;

    @Override
    public boolean convertibleTo(Type other) {
        if (equals(other)) return true;
        if (this == INT && other == BOOL) return true;
        return this == BOOL && other == INT;
    }
}