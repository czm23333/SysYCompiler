import java.util.List;
import java.util.Objects;

public class FunctionType implements Type {
    public final Type returnType;
    public final List<Type> parameters;

    public FunctionType(Type returnType, List<Type> parameters) {
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
    }

    @Override
    public boolean convertibleTo(Type other) {
        if (other instanceof FunctionType) {
            var functionType = (FunctionType) other;

            if (!returnType.convertibleTo(functionType.returnType)) return false;

            var parameterTypes = functionType.parameters;
            if (parameterTypes.size() != parameters.size()) return false;
            for (int i = 0; i < parameters.size(); ++i)
                if (!parameterTypes.get(i).convertibleTo(parameters.get(i))) return false;
            return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FunctionType)) return false;
        FunctionType that = (FunctionType) o;
        return Objects.equals(returnType, that.returnType) && Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnType, parameters);
    }
}