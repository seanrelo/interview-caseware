package com.caseware.fanout.domain;

/** Total items created by a publish and whether the scan is sealed (FR-21). */
public record PublishTotals(String publishId, long total, boolean sealed) {}
