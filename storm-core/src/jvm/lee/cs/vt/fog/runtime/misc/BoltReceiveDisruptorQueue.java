package lee.cs.vt.fog.runtime.misc;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.dsl.ProducerType;
import lee.cs.vt.fog.runtime.FogRuntime;
import lee.cs.vt.fog.runtime.unit.BoltRuntimeUnit;
import lee.cs.vt.fog.runtime.unit.WeightBoltRuntimeUnit;
import org.apache.storm.metric.internal.MultiCountStatAndMetric;
import org.apache.storm.tuple.AddressedTuple;
import org.apache.storm.tuple.MessageId;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.DisruptorQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class BoltReceiveDisruptorQueue extends DisruptorQueue {
    private final Lock lock = FogRuntime.LOCK;
    private final Condition condition = FogRuntime.CONDITION;

    private boolean isBolt = false;

    private long waitStartTime = -1;
    private long totalWaitTime = 0;
    private long totalTupleConsumed = 0;

    private long emptyStartTime = -1;
    private long totalEmptyTime = 0;

    private MultiCountStatAndMetric waitLatencyMetric = null;
    private MultiCountStatAndMetric emptyTimeMetric = null;

    private BoltRuntimeUnit unit = null;

    public BoltReceiveDisruptorQueue(String queueName,
                                     ProducerType type,
                                     int size,
                                     long readTimeout,
                                     int inputBatchSize,
                                     long flushInterval) {
        super(queueName, type, size, readTimeout, inputBatchSize, flushInterval);
    }

    @Override
    protected void publishDirectSingle(Object obj, boolean block) throws InsufficientCapacityException {
        long at;
        if (block) {
            at = _buffer.next();
        } else {
            at = _buffer.tryNext();
        }

        boolean isEmpty = _metrics.population() == 1;

        if (isBolt &&
                FogRuntime.getWaitTime &&
                isEmpty) {
            setWaitStartTime();
        }

        if (isBolt &&
                FogRuntime.getEmptyTime &&
                isEmpty) {
            addEmptyTime();
        }

        AtomicReference<Object> m = _buffer.get(at);
        m.set(obj);
        _buffer.publish(at);
        _metrics.notifyArrivals(1);

        if (isBolt && isEmpty) {
            lock.lock();
            if (unit != null) {
                unit.updateEmptyQueue();
            }
            condition.signalAll();
            lock.unlock();
        }
    }

    @Override
    protected void publishDirect(ArrayList<Object> objs, boolean block) throws InsufficientCapacityException {
        int size = objs.size();
        if (size > 0) {
            long end;
            if (block) {
                end = _buffer.next(size);
            } else {
                end = _buffer.tryNext(size);
            }

            boolean isEmpty = _metrics.population() == size;

            if (isBolt &&
                    FogRuntime.getWaitTime &&
                    isEmpty) {
                setWaitStartTime();
            }

            if (isBolt &&
                    FogRuntime.getEmptyTime &&
                    isEmpty) {
                addEmptyTime();
            }

            long begin = end - (size - 1);
            long at = begin;
            for (Object obj: objs) {
                AtomicReference<Object> m = _buffer.get(at);
                m.set(obj);
                at++;
            }
            _buffer.publish(begin, end);
            _metrics.notifyArrivals(size);

            if (isBolt && isEmpty) {
                lock.lock();
                if (unit != null) {
                    unit.updateEmptyQueue();
                }
                condition.signalAll();
                lock.unlock();
            }
        }
    }

    @Override
    protected void consumeBatchToCursor(long cursor, EventHandler<Object> handler) {
        if (isBolt &&
                FogRuntime.getWaitTime) {
            addWaitTime();
        }

        for (long curr = _consumer.get() + 1; curr <= cursor; curr++) {
            try {
                AtomicReference<Object> mo = _buffer.get(curr);
                Object o = mo.getAndSet(null);
                if (o == INTERRUPT) {
                    throw new InterruptedException("Disruptor processing interrupted");
                } else if (o == null) {
                    LOG.error("NULL found in {}:{}", this.getName(), cursor);
                } else {
                    handler.onEvent(o, curr, curr == cursor);

                    if (FogRuntime.getWaitTime ||
                            FogRuntime.getEmptyTime) {
                        List list = (List) o;
                        totalTupleConsumed += list.size();
                    }

                    if (_enableBackpressure && _cb != null && (_metrics.writePos() - curr + _overflowCount.get()) <= _lowWaterMark) {
                        try {
                            if (_throttleOn) {
                                _throttleOn = false;
                                _cb.lowWaterMark();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Exception during calling lowWaterMark callback!");
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        _consumer.set(cursor);

        if (isBolt &&
                FogRuntime.getWaitTime &&
                _metrics.population() > 0) {
            setWaitStartTime();
        }

        if (isBolt &&
                FogRuntime.getEmptyTime &&
                _metrics.population() == 0) {
            setEmptyStartTime();
        }
    }

    public void setBolt() {
        isBolt = true;
    }
    public boolean isBolt() {
        return isBolt;
    }

    public void setUnit(BoltRuntimeUnit unit) {
        this.unit = unit;
    }

    public void setWaitLatMetric(MultiCountStatAndMetric waitLatencyMetric) {
        this.waitLatencyMetric = waitLatencyMetric;
    }

    public void setEmptyTimeMetric(MultiCountStatAndMetric emptyTimeMetric) {
        this.emptyTimeMetric = emptyTimeMetric;
    }

    private void setWaitStartTime() {
        waitStartTime = System.currentTimeMillis();
    }

    private void addWaitTime() {
        if (waitStartTime == -1) {
            return;
        }
        long delta = System.currentTimeMillis() - waitStartTime;
        totalWaitTime += delta;
        waitStartTime = -1;

        waitLatencyMetric.incBy("default", delta);
    }

    public long getTotalWaitTime() {
        return totalWaitTime;
    }

    public long getTotalTupleConsumed() {
        return totalTupleConsumed;
    }

    private void setEmptyStartTime() {
        emptyStartTime = System.currentTimeMillis();
    }

    private void addEmptyTime() {
        if (emptyStartTime == -1) {
            return;
        }
        long delta = System.currentTimeMillis() - emptyStartTime;
        totalEmptyTime += delta;
        emptyStartTime = -1;

        emptyTimeMetric.incBy("default", delta);
    }

    public long getTotalEmptyTime() {
        return totalEmptyTime;
    }

    public long getChainTimestamp() {
        long curr = _consumer.get() + 1;
        int i = 0;
        Object o;;
        while ((o =_buffer.get(curr).get()) == null && i < 2) {
            curr += ++i;
        }

        if (o == null) {
            System.out.println("getChainTimestamp null here");
            return 0;
        }

        List<AddressedTuple> list = (List<AddressedTuple>) o;

        for (AddressedTuple addressedTuple : list) {
            Tuple tuple = addressedTuple.getTuple();
            if (tuple.contains("CHAINSTAMP")) {
                return tuple.getLongByField("CHAINSTAMP");
            }
        }

        System.out.println("not CHAINSTAMP here");

        return 0;
    }
}

