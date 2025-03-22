import java.util.HashMap;
import java.util.Map;

public class Context {
    private final Context parent;
    private final Map<String, ValueWithType> symbols = new HashMap<>();

    public Context() {
        this.parent = null;
    }

    public Context(Context parent) {
        this.parent = parent;
    }

    public boolean containsLocal(String name) {
        return symbols.containsKey(name);
    }

    public ValueWithType lookup(String name) {
        if (containsLocal(name)) return symbols.get(name);
        if (parent != null) return parent.lookup(name);
        return null;
    }

    public boolean contains(String name) {
        if (containsLocal(name)) return true;
        if (parent != null) return parent.contains(name);
        return false;
    }

    public boolean define(String name, ValueWithType value) {
        if (containsLocal(name)) return false;
        symbols.put(name, value);
        return true;
    }
}