public enum BasicType implements Type {
    INT, BOOL;

    @Override
    public boolean convertibleTo(Type other) {
        return equals(other);
    }
}