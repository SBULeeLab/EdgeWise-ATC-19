package lee.cs.vt.fog.runtime;

public interface RuntimePolicy {
    public BoltRuntimeUnit getUnitAndSet();
    public void unitReset(BoltRuntimeUnit unit);
}
