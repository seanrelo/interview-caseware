package com.caseware.fanout.web;

import com.caseware.fanout.adapter.memory.InMemoryDiffSource;
import com.caseware.fanout.adapter.memory.InMemoryEfFirmLookup;
import com.caseware.fanout.adapter.memory.InMemoryTemplateVersionRecordStore;
import com.caseware.fanout.adapter.memory.InMemoryVersionRegistry;
import com.caseware.fanout.capture.CaptureSource;
import com.caseware.fanout.config.FanoutConfiguration;
import com.caseware.fanout.config.StatusConfiguration;
import com.caseware.fanout.config.SummaryConfiguration;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.MarketBranch;
import com.caseware.fanout.graph.TemplateVersion;
import com.caseware.fanout.graph.VersionStatus;
import com.caseware.fanout.status.StatusService;
import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffItem;
import com.caseware.fanout.summary.PairKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real in-memory services behind standalone MockMvc. No network, no sleeps. */
final class WebRig {
  static final TemplateId T = new TemplateId("TPL-A");
  static final FirmId F = new FirmId("firm-a");
  static final FirmId G = new FirmId("firm-b");

  static final String[] PROPS = {
    "fanout.capture.enabled=true",
    "fanout.status.enabled=true",
    "fanout.summary.enabled=true",
    "fanout.summary.stub-model=true",
    "fanout.summary.pinned-model=sample-model-2026-01-15",
    "fanout.summary.prompt-version=summary-prompt-v3",
    "fanout.web.enabled=true"
  };

  static ApplicationContextRunner runner(String... extra) {
    String[] all = new String[PROPS.length + extra.length];
    System.arraycopy(PROPS, 0, all, 0, PROPS.length);
    System.arraycopy(extra, 0, all, PROPS.length, extra.length);
    return new ApplicationContextRunner()
        .withUserConfiguration(
            FanoutConfiguration.class,
            StatusConfiguration.class,
            SummaryConfiguration.class,
            WebConfiguration.class)
        .withPropertyValues(all);
  }

  /** v1 -> v2 -> v3 chain. ef1 and ef2 (firm A) on v1, ef3 (firm A) on v3, ef9 (firm B) on v1. */
  static void seed(ApplicationContext ctx) {
    var reg = ctx.getBean(InMemoryVersionRegistry.class);
    reg.register(v("v1", 1));
    reg.register(v("v2", 2, "v1"));
    reg.register(v("v3", 3, "v2"));
    var lookup = ctx.getBean(InMemoryEfFirmLookup.class);
    var records = ctx.getBean(InMemoryTemplateVersionRecordStore.class);
    var status = ctx.getBean(StatusService.class);
    add(lookup, records, status, "ef1", F, "v1");
    add(lookup, records, status, "ef2", F, "v1");
    add(lookup, records, status, "ef3", F, "v3");
    add(lookup, records, status, "ef9", G, "v1");
    var diffs = ctx.getBean(InMemoryDiffSource.class);
    PairKey p = new PairKey(T, new VersionId("v1"), new VersionId("v3"));
    diffs.register(
        new Diff(
            p,
            List.of(
                new DiffItem("d1", "$.sections[0].secret.path", "Revenue", "title renamed"),
                new DiffItem("d2", "$.sections[1].other", "Leases", "paragraph added"))));
  }

  private static void add(
      InMemoryEfFirmLookup lookup,
      InMemoryTemplateVersionRecordStore records,
      StatusService status,
      String ef,
      FirmId firm,
      String version) {
    EfRef ref = new EfRef(new EfId(ef), firm);
    lookup.add(ref);
    records.upsertVersion(
        Region.EU, ref, T, new VersionId(version), Instant.parse("2026-01-10T00:00:00Z"), CaptureSource.CREATE);
    status.refresh(Region.EU, ref);
  }

  static TemplateVersion v(String id, int day, String... parents) {
    return new TemplateVersion(
        T,
        new VersionId(id),
        Set.of(java.util.Arrays.stream(parents).map(VersionId::new).toArray(VersionId[]::new)),
        new MarketBranch("main"),
        VersionStatus.PUBLISHED,
        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(86400L * day));
  }

  static MockMvc mvc(Object... controllers) {
    return MockMvcBuilders.standaloneSetup(controllers)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private WebRig() {}
}
