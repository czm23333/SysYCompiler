public enum ErroneousType implements Type {
    ERROR;


    @Override
    public boolean convertibleTo(Type other) {
        return true;
    }
}