public enum VoidType implements Type {
    VOID;

    @Override
    public boolean convertibleTo(Type other) {
        return equals(other);
    }
}