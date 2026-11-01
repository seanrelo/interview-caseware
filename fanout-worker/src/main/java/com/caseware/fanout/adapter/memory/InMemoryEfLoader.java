package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.EfLoadException;
import com.caseware.fanout.capture.EfLoader;
import com.caseware.fanout.capture.TemplateVersionRef;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fake EF loader with failure and latency hooks and a spy (load counts per EF, in-flight and its
 * maximum). The latency hook runs inside the load, after it is counted in flight.
 */
public class InMemoryEfLoader implements EfLoader {

  private final Map<String, TemplateVersionRef> versions = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
  private final Set<String> failAlways = ConcurrentHashMap.newKeySet();
  private final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();
  private final AtomicInteger total = new AtomicInteger();
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger maxInFlight = new AtomicInteger();
  private volatile Consumer<EfRef> latencyHook = ef -> {};

  private static String key(Region region, EfRef ef) {
    return region + "/" + ef.ef().value();
  }

  public void seed(Region region, EfRef ef, TemplateVersionRef ref) {
    versions.put(key(region, ef), ref);
  }

  public void failTimes(Region region, EfRef ef, int n) {
    failuresLeft.put(key(region, ef), new AtomicInteger(n));
  }

  public void failAlways(Region region, EfRef ef) {
    failAlways.add(key(region, ef));
  }

  public void latencyHook(Consumer<EfRef> hook) {
    this.latencyHook = hook;
  }

  public int loadCount(Region region, EfRef ef) {
    AtomicInteger c = loads.get(key(region, ef));
    return c == null ? 0 : c.get();
  }

  public int totalLoads() {
    return total.get();
  }

  public int inFlight() {
    return inFlight.get();
  }

  public int maxInFlight() {
    return maxInFlight.get();
  }

  public void resetMaxInFlight() {
    maxInFlight.set(inFlight.get());
  }

  @Override
  public TemplateVersionRef load(Region region, EfRef ef) throws EfLoadException {
    String k = key(region, ef);
    total.incrementAndGet();
    loads.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
    int now = inFlight.incrementAndGet();
    maxInFlight.accumulateAndGet(now, Math::max);
    try {
      latencyHook.accept(ef);
      if (failAlways.contains(k)) {
        throw new EfLoadException("load failed: " + k);
      }
      AtomicInteger left = failuresLeft.get(k);
      if (left != null && left.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
        throw new EfLoadException("load failed (transient): " + k);
      }
      TemplateVersionRef ref = versions.get(k);
      if (ref == null) {
        throw new EfLoadException("EF not found: " + k);
      }
      return ref;
    } finally {
      inFlight.decrementAndGet();
    }
  }
}
