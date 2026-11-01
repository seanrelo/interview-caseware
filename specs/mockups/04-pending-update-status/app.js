(function () {
  "use strict";
  var pristine = JSON.stringify(window.MOCK);
  var M, state, filter, refreshing, page;
  var $ = function (id) { return document.getElementById(id); };

  function init() {
    M = JSON.parse(pristine); state = "normal"; filter = "all"; refreshing = {}; page = 0;
    $("withdraw").disabled = false;
    $("state").value = "normal";
    render();
  }

  function esc(t) { return String(t == null ? "" : t).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }

  function ver(ef, n) { return M.templates[ef.template].versions.filter(function (v) { return v.n === n; })[0]; }
  // Version n and every ancestor reached along parent links (the version graph, not numeric order).
  function lineage(ef, n, seen) {
    seen = seen || {}; if (seen[n]) return seen; seen[n] = true;
    var v = ver(ef, n); if (v) v.parents.forEach(function (p) { lineage(ef, p, seen); });
    return seen;
  }
  // FR-11: published, non-withdrawn versions in the EF's market branch reachable from the baseline.
  function eligible(ef) {
    return M.templates[ef.template].versions.filter(function (v) {
      return !v.withdrawn && v.branch === ef.branch && v.n !== ef.baseline && lineage(ef, v.n)[ef.baseline];
    }).map(function (v) { return v.n; });
  }
  // FR-12: the baseline is neither in the EF's branch nor an ancestor of any version in it: no path.
  function hasPath(ef) {
    return M.templates[ef.template].versions.some(function (v) { return v.branch === ef.branch && lineage(ef, v.n)[ef.baseline]; });
  }

  // Derives the one status per EF (FR-22). Anything undeterminable is unknown, never up to date (FR-25).
  function status(ef) {
    if (state === "failed" || ef.lookupFailed) return { s: "unknown", why: "Status could not be determined. This is not a confirmation that the EF is up to date." }; // FR-25
    if (ef.deadLettered) return { s: "unknown", why: "Status could not be determined. This is not a confirmation that the EF is up to date.", req: "FR-19" }; // FR-19: shown as plain unknown
    if (!ef.record) return { s: "unknown", why: "No template-version record yet (waiting for backfill)." }; // FR-24
    if (!hasPath(ef)) return { s: "unknown", why: "No path from baseline v" + ef.baseline + " to a later version in the " + ef.branch + " branch.", req: "FR-12" };
    var el = eligible(ef);
    if (!el.length) return { s: "ok" };
    var latest = Math.max.apply(null, el);
    var declined = (ef.declines || []).some(function (d) { return d.version === latest; }); // FR-28
    if (declined) return { s: "ok", declinedLatest: latest };
    return { s: "pending", from: ef.baseline, to: latest, count: el.length }; // FR-23, FR-26, FR-30
  }

  var LABEL = { ok: "✔ Up to date", pending: "● Update pending", unknown: "? Unknown" };

  function render() {
    $("firmName").textContent = M.firm + " (sample firm)";
    var list = $("list"), filters = $("filters");
    var off = state === "flagoff";
    $("main").hidden = off; // NFR-41/42: nothing is shown in the product frame
    $("mockNote").hidden = !off;
    $("stateNote").textContent = state === "failed" ? "Mock state: the status lookup failed for every EF." : "";
    if (off) return;
    if (state === "loading") {
      list.setAttribute("aria-busy", "true");
      list.innerHTML = '<li class="skeleton" data-req="NFR-14"></li>'.repeat(5);
      filters.innerHTML = ""; $("pager").hidden = true;
      $("liveMsg").textContent = "Loading EF statuses…";
      return;
    }
    list.removeAttribute("aria-busy"); $("pager").hidden = false;
    var rows = M.efs.map(function (ef) { return { ef: ef, st: status(ef) }; });
    var counts = { all: rows.length, ok: 0, pending: 0, unknown: 0 };
    rows.forEach(function (r) { counts[r.st.s]++; });
    filters.innerHTML = "";
    [["all", "All"], ["pending", "Update pending"], ["ok", "Up to date"], ["unknown", "Unknown"]].forEach(function (f) {
      var b = document.createElement("button");
      b.type = "button"; b.setAttribute("aria-pressed", String(filter === f[0]));
      b.textContent = f[1] + " (" + counts[f[0]] + ")";
      b.onclick = function () { filter = f[0]; page = 0; render(); };
      filters.appendChild(b);
    });
    list.innerHTML = "";
    var shown = rows.filter(function (r) { return filter === "all" || r.st.s === filter; });
    var pages = Math.max(1, Math.ceil(shown.length / M.pageSize));
    page = Math.min(page, pages - 1);
    shown.slice(page * M.pageSize, (page + 1) * M.pageSize).forEach(function (r) {
      list.appendChild(card(r.ef, r.st));
    });
    if (!list.children.length) list.innerHTML = '<li class="note">No EFs with this status.</li>';
    $("pageInfo").textContent = "Page " + (page + 1) + " of " + pages + " (" + M.pageSize + " EFs per page)";
    $("prev").disabled = page === 0; $("next").disabled = page >= pages - 1;
  }

  function card(ef, st) {
    var li = document.createElement("li"); li.className = "card";
    var busy = refreshing[ef.id];
    var sub;
    if (!ef.record) sub = "Template: not recorded";
    else sub = esc(ef.template) + " (" + esc(ef.branch) + " branch), baseline v" + ef.baseline;
    var line2 = "";
    if (busy) line2 = "Refreshing status…"; // FR-32
    else if (st.s === "pending") {
      line2 = "Update v" + st.from + " → v" + st.to; // FR-26
      if (st.count > 1) line2 += " (" + st.count + " versions in the cumulative range, shown as one)";
    } else if (st.s === "ok" && st.declinedLatest) line2 = "v" + st.declinedLatest + " declined; baseline unchanged"; // FR-28, FR-29
    else if (st.s === "unknown") line2 = esc(st.why);
    var lineReq = busy ? "FR-32" : st.s === "pending" ? "FR-26" : st.s === "unknown" ? "FR-24 FR-25" : st.declinedLatest ? "FR-28 FR-29" : "";
    var badgeReq = st.s === "pending" ? "FR-23" : st.s === "unknown" ? "FR-24 FR-25" + (st.req ? " " + st.req : "") : st.declinedLatest ? "FR-28" : "";
    li.innerHTML =
      '<div><h3>' + esc(ef.name) + '</h3><p class="meta">' + esc(ef.id) + " · " + sub + '</p>' +
      (line2 ? '<p class="meta"' + (lineReq ? ' data-req="' + lineReq + '"' : "") + '>' + line2 + '</p>' : "") + '</div>' +
      '<span class="badge s-' + st.s + '" data-req="' + ("FR-22 " + badgeReq).trim() + '">' + LABEL[st.s] + '</span>';
    if (st.s === "pending" && !busy) {
      var a = document.createElement("div"); a.className = "actions";
      var b = document.createElement("button"); b.type = "button"; b.className = "primary";
      b.textContent = "Review update"; b.setAttribute("aria-label", "Review update for " + ef.name);
      b.onclick = function () { openDetail(ef); };
      a.appendChild(b); li.appendChild(a);
    } else if (st.s !== "pending" && !busy && (ef.declines || ef.accepts)) {
      var a2 = document.createElement("div"); a2.className = "actions";
      var b2 = document.createElement("button"); b2.type = "button";
      b2.textContent = "Decision history"; b2.setAttribute("aria-label", "Decision history for " + ef.name);
      b2.onclick = function () { openDetail(ef); };
      a2.appendChild(b2); li.appendChild(a2);
    }
    return li;
  }

  function openDetail(ef) {
    var st = status(ef), d = $("detail");
    var h = '<h2 id="dTitle">' + esc(ef.name) + '</h2><p class="meta">' + esc(ef.id) + " · " + esc(ef.template || "template not recorded") + '</p>';
    h += '<p><span class="badge s-' + st.s + '" data-req="FR-22">' + LABEL[st.s] + '</span></p>';
    if (st.s === "pending") {
      h += '<div class="ver" data-req="FR-26"><span>Baseline v' + st.from + '</span><span class="arrow" aria-hidden="true">→</span><span>Latest eligible v' + st.to + '</span></div>';
      if (st.count > 1) h += '<p class="meta" data-req="FR-26">' + st.count + ' versions in the cumulative range; presented as one update from the baseline.</p>';
      h += '<div class="tbd">Summary of changes: shown by spec 05 (FR-33, FR-43), not part of this mockup. TBD.</div>';
    } else if (st.s === "unknown") {
      h += '<div class="warn" data-req="FR-24 FR-25">' + esc(st.why) + '</div>';
    } else {
      h += '<p class="meta">Baseline v' + ef.baseline + '. No pending update.</p>';
    }
    var log = [];
    (ef.declines || []).forEach(function (x) { log.push({ t: x.time, h: "Declined v" + x.version + " by " + x.user }); });
    (ef.accepts || []).forEach(function (x) { log.push({ t: x.time, h: "Accepted v" + x.version + " by " + x.user }); });
    log.sort(function (a, b) { return a.t < b.t ? -1 : 1; });
    if (log.length) {
      h += '<h3>Decisions</h3><ul class="log" data-req="FR-27 FR-31">' + log.map(function (l) { return "<li>" + esc(l.t) + ": " + esc(l.h) + "</li>"; }).join("") + "</ul>";
    }
    h += '<div class="btns">';
    if (st.s === "pending") {
      h += '<button type="button" class="primary" id="acc" data-req="FR-4 FR-31">Accept v' + st.to + '</button>' +
           '<button type="button" id="dec" data-req="FR-27">Decline v' + st.to + '</button>';
    }
    h += '<button type="button" id="cls">Close</button></div>';
    d.innerHTML = h; d.showModal();
    $("cls").onclick = function () { d.close(); };
    if (st.s === "pending") {
      $("acc").onclick = function () { decide(ef, "accept", st.to); };
      $("dec").onclick = function () { decide(ef, "decline", st.to); };
    }
  }

  function decide(ef, kind, version) {
    var rec = { version: version, user: M.user, time: M.now };
    if (kind === "decline") { (ef.declines = ef.declines || []).push(rec); } // FR-27, FR-29: baseline untouched
    else { (ef.accepts = ef.accepts || []).push(rec); ef.baseline = version; } // FR-31
    $("detail").close();
    refreshing[ef.id] = true; render();
    $("liveMsg").textContent = "Refreshing status for " + ef.name + "…"; // FR-32
    setTimeout(function () {
      delete refreshing[ef.id]; render();
      $("liveMsg").textContent = ef.name + ": " + kind + " of v" + version + " recorded; status refreshed.";
    }, 700);
  }

  $("state").onchange = function () { state = this.value; render(); };
  $("showReq").onchange = function () { document.body.classList.toggle("show-req", this.checked); };
  $("reset").onclick = function () { var r = $("showReq").checked; init(); $("showReq").checked = r; };
  $("withdraw").onclick = function () {
    // Mock of FR-10/FR-13: v9 (EU) of Audit Standard is withdrawn; EFs whose pending version it was are re-evaluated.
    var affected = M.efs.filter(function (ef) { var st = status(ef); return st.s === "pending" && ef.template === "Audit Standard" && st.to === 9; });
    M.templates["Audit Standard"].versions.forEach(function (v) { if (v.n === 9) v.withdrawn = true; });
    this.disabled = true;
    affected.forEach(function (ef) { refreshing[ef.id] = true; });
    render();
    $("liveMsg").textContent = "Audit Standard v9 withdrawn; re-evaluating " + affected.length + " EF(s) whose pending version it was…";
    setTimeout(function () {
      affected.forEach(function (ef) { delete refreshing[ef.id]; });
      render();
      $("liveMsg").textContent = "Status re-evaluated for " + affected.length + " EF(s) after the withdrawal.";
    }, 700);
  };
  $("prev").onclick = function () { page = Math.max(0, page - 1); render(); };
  $("next").onclick = function () { page++; render(); };
  $("publish").onclick = function () {
    // Mock of a publish event: adds the next version to every template, then statuses are re-derived.
    Object.keys(M.templates).forEach(function (k) {
      var vs = M.templates[k].versions;
      var g = vs.filter(function (v) { return v.branch === "Global"; });
      vs.push({ n: Math.max.apply(null, vs.map(function (v) { return v.n; })) + 1, parents: [Math.max.apply(null, g.map(function (v) { return v.n; }))], branch: "Global" });
    });
    render();
    $("liveMsg").textContent = "A new Global-branch version was published for each template; statuses re-derived (EFs without a record stay unknown).";
  };
  init();
})();
