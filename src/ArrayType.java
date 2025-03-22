import java.util.Objects;

public class ArrayType extends AbstractArrayType {
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