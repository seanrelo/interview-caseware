package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GraphFixtures {
  public static final TemplateId T = new TemplateId("T");
  public static final MarketBranch MAIN = new MarketBranch("main");
  public static final MarketBranch CA = new MarketBranch("CA");
  private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

  private GraphFixtures() {}

  public static VersionId v(String s) {
    return new VersionId(s);
  }

  public static Instant at(long day) {
    return EPOCH.plusSeconds(day * 86_400);
  }

  public static TemplateVersion node(String id, long day, MarketBranch branch, VersionStatus st, String... parents) {
    Set<VersionId> ps = Stream.of(parents).map(GraphFixtures::v).collect(Collectors.toSet());
    return new TemplateVersion(T, v(id), ps, branch, st, at(day));
  }

  public static TemplateVersion pub(String id, long day, String... parents) {
    return node(id, day, MAIN, VersionStatus.PUBLISHED, parents);
  }
}
