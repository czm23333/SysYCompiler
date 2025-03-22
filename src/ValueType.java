public enum ValueType {
    LEFT, RIGHT;

    public boolean convertibleTo(ValueType type) {
        if (type == RIGHT) return true;
        return this == LEFT;
    }
}