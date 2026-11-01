package com.caseware.fanout.demo;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.adapter.memory.InMemoryBaselinesInUse;
import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryFirmFlags;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.MarketBranch;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.port.FirmFlags;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffItem;
import com.caseware.fanout.summary.JobReason;
import com.caseware.fanout.summary.ModelOutput;
import com.caseware.fanout.summary.ModelStatement;
import com.caseware.fanout.summary.PairKey;
import com.caseware.fanout.summary.SummaryOutcome;
import com.caseware.fanout.summary.SummaryService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DEV ONLY. Fills the in-memory beans so the demo firm {@value #DEMO_FIRM} shows one row per
 * case. Invented identifiers only; EF ids, no EF names. Active only under profile {@code demo}.
 *
 * <p>Summary cases (all driven through the real {@link SummaryService}, nothing is stored by hand):
 * v5 to v6 is generated (READY); v3 to v6 is queued and never drained (GENERATING); v4 to v6 has a
 * model that always fails and, with {@code fanout.summary.max-attempts=1}, is dead-lettered
 * (UNAVAILABLE). Only the status and decisions change when a user accepts or declines.
 */
@Component
@Profile("demo")
public class DemoSeed implements InitializingBean {

  public static final String DEMO_FIRM = "firm-demo";
  public static final String OTHER_FIRM = "firm-other";

  private static final Logger LOG = LoggerFactory.getLogger(DemoSeed.class);
  private static final TemplateId TEMPLATE = new TemplateId("TPL-ANNUAL-REVIEW");
  private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

  private final InMemoryVersionRegistry registry;
  private final InMemoryEfFirmLookup lookup;
  private final InMemoryTemplateVersionRecordStore records;
  private final StatusService status;
  private final InMemoryDiffSource diffs;
  private final InMemoryBaselinesInUse baselines;
  private final FakeModelClient model;
  private final SummaryService summaries;
  private final FirmFlags flags;

  public DemoSeed(
      InMemoryVersionRegistry registry,
      InMemoryEfFirmLookup lookup,
      InMemoryTemplateVersionRecordStore records,
      StatusService status,
      InMemoryDiffSource diffs,
      InMemoryBaselinesInUse baselines,
      FakeModelClient model,
      SummaryService summaries,
      FirmFlags flags) {
    this.registry = registry;
    this.lookup = lookup;
    this.records = records;
    this.status = status;
    this.diffs = diffs;
    this.baselines = baselines;
    this.model = model;
    this.summaries = summaries;
    this.flags = flags;
  }

  @Override
  public void afterPropertiesSet() {
    FirmId demo = new FirmId(DEMO_FIRM);
    FirmId other = new FirmId(OTHER_FIRM);
    if (flags instanceof InMemoryFirmFlags f) {
      f.setEnabled(demo, true);
      f.setEnabled(other, true);
    }
    // Linear graph v1..v6; the latest eligible version is v6 for every baseline.
    registry.register(version("v1", 0));
    for (int i = 2; i <= 6; i++) {
      registry.register(version("v" + i, i - 1, "v" + (i - 1)));
    }

    efWithBaseline(demo, "ef-1001", "v6"); // up to date
    efWithBaseline(demo, "ef-1002", "v5"); // pending v5 -> v6, summary READY
    efWithBaseline(demo, "ef-1003", "v3"); // pending v3 -> v6 (accumulated), summary GENERATING
    lookup.add(new EfRef(new EfId("ef-1004"), demo)); // no record: unknown
    status.refresh(Region.EU, new EfRef(new EfId("ef-1004"), demo));
    EfRef deadLettered = efWithBaseline(demo, "ef-1005", "v5");
    status.markUnknown(deadLettered, TEMPLATE); // unknown after fan-out dead letter (FR-19)
    efWithBaseline(demo, "ef-1006", "v4"); // pending v4 -> v6, summary generation fails
    efWithBaseline(demo, "ef-1007", "v5"); // pending, for accept
    efWithBaseline(demo, "ef-1008", "v5"); // pending, for decline
    efWithBaseline(other, "ef-2001", "v5"); // another firm: 404 for the demo firm

    PairKey ready = pair("v5");
    PairKey generating = pair("v3");
    PairKey failing = pair("v4");
    diffs.register(diff(ready, "Revenue", "recognition wording clarified", "Leases", "disclosure paragraph added"));
    diffs.register(diff(generating, "Revenue", "recognition wording clarified", "Subsequent events", "checklist reordered"));
    diffs.register(diff(failing, "Going concern", "assessment step added"));
    for (String b : List.of("v3", "v4", "v5")) {
      baselines.register(TEMPLATE, new VersionId(b));
    }
    model.outputOverride(
        d -> {
          if (d.pair().equals(failing)) {
            throw new IllegalStateException("demo: model unavailable for this pair");
          }
          return new ModelOutput(
              d.items().stream()
                  .map(i -> new ModelStatement("In section " + i.sectionName() + ": " + i.change() + ".", List.of(i.id())))
                  .toList());
        });

    summaries.ensure(ready, JobReason.ON_DEMAND);
    summaries.processNext();
    summaries.ensure(failing, JobReason.ON_DEMAND);
    summaries.processNext();
    if (!(summaries.ensure(failing, JobReason.ON_DEMAND) instanceof SummaryOutcome.Failed)) {
      LOG.warn("demo: the failing pair did not dead-letter; set fanout.summary.max-attempts=1");
    }
    summaries.ensure(generating, JobReason.ON_DEMAND); // queued, never drained
    LOG.info("Demo seeded. Firm {} (headers X-Dev-Firm: {}, X-Dev-User: demo-user)", DEMO_FIRM, DEMO_FIRM);
  }

  private EfRef efWithBaseline(FirmId firm, String ef, String baseline) {
    EfRef ref = new EfRef(new EfId(ef), firm);
    lookup.add(ref);
    records.upsertVersion(Region.EU, ref, TEMPLATE, new VersionId(baseline), Instant.now(), CaptureSource.CREATE);
    status.refresh(Region.EU, ref);
    return ref;
  }

  private static TemplateVersion version(String id, int day, String... parents) {
    return new TemplateVersion(
        TEMPLATE,
        new VersionId(id),
        Set.of(java.util.Arrays.stream(parents).map(VersionId::new).toArray(VersionId[]::new)),
        new MarketBranch("main"),
        VersionStatus.PUBLISHED,
        EPOCH.plusSeconds(86_400L * (day + 1)));
  }

  private static PairKey pair(String baseline) {
    return new PairKey(TEMPLATE, new VersionId(baseline), new VersionId("v6"));
  }

  /** Alternating section and change texts. The path stays in the stored diff only (FR-38). */
  private static Diff diff(PairKey pair, String... sectionAndChange) {
    java.util.ArrayList<DiffItem> items = new java.util.ArrayList<>();
    for (int i = 0; i < sectionAndChange.length; i += 2) {
      int n = i / 2 + 1;
      items.add(new DiffItem("d" + n, "$.sections[" + n + "].body", sectionAndChange[i], sectionAndChange[i + 1]));
    }
    return new Diff(pair, items);
  }
}
