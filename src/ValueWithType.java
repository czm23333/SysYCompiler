import java.util.Objects;

public class ValueWithType {
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