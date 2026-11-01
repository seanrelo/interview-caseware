package com.caseware.fanout.domain;

import java.time.Instant;

public record DeadLetter(WorkKey key, FirmId firm, int attempts, String lastError, Instant at) {}
