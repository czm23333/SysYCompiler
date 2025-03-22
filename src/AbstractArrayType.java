import java.util.Objects;

public abstract class AbstractArrayType extends DummyType {
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