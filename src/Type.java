public interface Type {
    default boolean convertibleTo(Type other) {
        return other instanceof ErroneousType || equals(other);
    }
}