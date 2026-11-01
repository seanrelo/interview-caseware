package com.caseware.fanout.web;

/** result is RECORDED or DUPLICATE; row is the refreshed detail, null when that refresh failed. */
public record DecisionResponse(String result, RowDto row) {}
