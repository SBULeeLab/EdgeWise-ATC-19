package lee.cs.vt.fog.runtime.policy;

import lee.cs.vt.fog.runtime.FogRuntime;
import lee.cs.vt.fog.runtime.misc.BoltReceiveDisruptorQueue;
import lee.cs.vt.fog.runtime.misc.ExecutorCallback;
import lee.cs.vt.fog.runtime.unit.BoltRuntimeUnit;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class WaitSignalRuntimePolicy implements RuntimePolicy {

    private final Set<BoltRuntimeUnit> bolts;
    private final Set<BoltRuntimeUnit> availableBolts;

    private final Lock lock = FogRuntime.LOCK;
    private final Condition condition = FogRuntime.CONDITION;

    public WaitSignalRuntimePolicy(List<ExecutorCallback.CallbackProvider> list,
                                   Map<Object, BoltReceiveDisruptorQueue> map) {
        this.bolts = new HashSet<BoltRuntimeUnit>();

        for (ExecutorCallback.CallbackProvider provider : list) {
            ExecutorCallback callback = provider.getCallback();
            if (callback == null)
                continue;

            Object executorId = callback.getExecutorId();
            BoltReceiveDisruptorQueue queue = map.get(executorId);
            assert(queue != null);

            String componentId = callback.getComponentId();

            ExecutorCallback.ExecutorType type = callback.getType();
            switch (type) {
                case bolt:
                    this.bolts.add(new BoltRuntimeUnit(componentId, queue, callback));
                    break;
                default:
                    // Never comes here
                    assert(false);
            }
        }

        this.availableBolts = new HashSet<BoltRuntimeUnit>(bolts);

        System.out.println("Policy: WaitSignalRuntimePolicy");
    }

    @Override
    public BoltRuntimeUnit getUnitAndSet(){
        lock.lock();
        BoltRuntimeUnit ret = null;
        try {
           while ((ret = getUnit()) == null) {
                condition.await();
            }

            availableBolts.remove(ret);

        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }

        return ret;
    }

    @Override
    public void unitReset(BoltRuntimeUnit unit) {
        lock.lock();
        if (unit.getNumInQ() == 0)
            unit.resetReadyTime();
        else
            unit.setReadyTime();
        availableBolts.add(unit);
        lock.unlock();
    }

    @Override
    public void print() {
        System.out.println("Policy: WaitSignalRuntimePolicy");
        for (BoltRuntimeUnit bolt : bolts) {
            bolt.print();
        }
    }

    private BoltRuntimeUnit getUnit() {
        BoltRuntimeUnit ret_waitted = null;
        long max_waited = 0;

        for (BoltRuntimeUnit bolt : availableBolts) {
            long  waited_time = bolt.getWaitedTime();
            if (waited_time > max_waited) {
                max_waited = waited_time;
                ret_waitted = bolt;
            }
        }

        return ret_waitted;
    }
}
