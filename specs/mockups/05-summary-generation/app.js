(function () {
  "use strict";
  var pristine = JSON.stringify(window.MOCK);
  var M, state, selected, generating, retried, timers, opened;
  var $ = function (id) { return document.getElementById(id); };
  function esc(t) { return String(t == null ? "" : t).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  function key(ef) { return ef.template + ":" + ef.from + "-" + ef.to; }

  function init() {
    M = JSON.parse(pristine); state = "normal"; selected = null; generating = {}; retried = {}; opened = {};
    Object.keys(timers || {}).forEach(function (k) { clearTimeout(timers[k]); }); timers = {};
    $("state").value = "normal"; render();
  }

  // Outcome of the summary for an EF: ready | generating | unavailable (FR-42).
  function outcome(ef) {
    var k = key(ef);
    if (M.summaries[k]) return { s: "ready", sum: M.summaries[k] };
    if (generating[k]) return { s: "generating" };
    if (ef.fail && !retried[k]) return { s: "unavailable" };
    return { s: "absent" }; // no summary yet, generated when opened (FR-34)
  }

  function render() {
    $("firmName").textContent = M.firm + " (sample firm)";
    var off = state === "flagoff";
    $("main").hidden = off; // NFR-41/42: nothing is shown in the product frame
    $("mockNote").hidden = !off;
    $("stateNote").textContent = "";
    var list = $("list"); list.innerHTML = "";
    if (off) return;
    M.efs.forEach(function (ef) {
      var li = document.createElement("li"); li.className = "card" + (selected === ef.id ? " sel" : "");
      li.innerHTML = '<div><h3>' + esc(ef.name) + '</h3><p class="meta">' + esc(ef.id) + ' · ' + esc(ef.template) + ' · v' + ef.from + ' → v' + ef.to + '</p><p class="meta">' + esc(ef.note) + '</p></div>' +
        '<span class="badge s-pending" data-req="FR-42">● Update pending</span>';
      var a = document.createElement("div"); a.className = "actions";
      var b = document.createElement("button"); b.type = "button"; b.className = "primary";
      b.textContent = "Open update"; b.setAttribute("aria-label", "Open update for " + ef.name);
      b.onclick = function () { open(ef); };
      a.appendChild(b); li.appendChild(a); list.appendChild(li);
    });
    panel();
  }

  function open(ef) {
    selected = ef.id;
    var k = key(ef);
    if (outcome(ef).s === "absent") { // FR-34: generate when none exists for the pair
      generating[k] = true;
      // No timeout on the generating state: the spec states none (open question). Every pair in the sample data resolves.
      timers[k] = setTimeout(function () {
        var g = M.generatable[k] || M.retryResult[k];
        if (g) M.summaries[k] = g;
        delete generating[k]; render();
      }, 1500);
    }
    render();
    $("panel").focus && $("panel").setAttribute("tabindex", "-1");
    $("panel").focus();
  }

  function panel() {
    var p = $("panel");
    var ef = M.efs.filter(function (e) { return e.id === selected; })[0];
    if (!ef) { p.innerHTML = '<p class="note">Select a pending update to see its summary.</p>'; return; }
    var o = outcome(ef);
    var h = '<h3>' + esc(ef.name) + '</h3><div class="ver" data-req="FR-33"><span>Baseline v' + ef.from + '</span><span class="arrow" aria-hidden="true">→</span><span>Latest eligible v' + ef.to + '</span></div>';
    if (o.s === "generating" || o.s === "absent") {
      h += '<p data-req="FR-34"><span class="spin" aria-hidden="true"></span>Generating summary…</p>';
    } else if (o.s === "unavailable") {
      h += '<div class="unavail" data-req="FR-42"><strong>Summary unavailable.</strong> The update is still pending. A retry is scheduled.</div>';
    } else {
      var s = o.sum;
      h += '<p><span class="ai" data-req="FR-43">✨ AI-generated summary</span></p>'; // FR-43
      h += '<ol class="stmts" data-req="FR-33 FR-38">' + s.statements.map(function (st) {
        return "<li>" + esc(st.text) + '<span class="ref" data-req="FR-39">' + st.refs.map(function (r) { return '<a href="#d-' + r + '" data-diff="d-' + r + '">[' + r + ']</a>'; }).join(" ") + "</span></li>";
      }).join("") + "</ol>";
      h += '<details data-req="FR-40 NFR-16"><summary>Audit details</summary>' +
        '<p class="tbd">TBD: who may see audit details is not specified (not in the index assumptions).</p>' +
        '<dl class="dl"><dt>Summary record</dt><dd data-req="FR-36 NFR-15">' + s.id + ' (immutable, stored record, reused)</dd>' +
        '<dt>Model</dt><dd data-req="NFR-17">' + M.model + '</dd><dt>Prompt version</dt><dd>' + M.promptVersion + '</dd>' +
        '<dt>Generated</dt><dd>' + s.generated + '</dd></dl>' +
        '<table><caption class="note">Diff items (FR-37)</caption><thead><tr><th>Item</th><th>Section</th><th>Change</th><th>Detail</th></tr></thead><tbody>' +
        s.diff.map(function (d) { return '<tr id="d-' + d.id + '"><td>' + esc(d.id) + "</td><td>" + esc(d.section) + "</td><td>" + esc(d.kind) + "</td><td>" + esc(d.text) + "</td></tr>"; }).join("") +
        "</tbody></table></details>";
    }
    p.innerHTML = h;
    p.querySelectorAll("a[data-diff]").forEach(function (a) { // FR-39: make the linked diff item visible
      a.onclick = function (e) {
        e.preventDefault();
        var det = p.querySelector("details"); if (det) det.open = true;
        var row = document.getElementById(a.getAttribute("data-diff"));
        if (row) { row.scrollIntoView({ block: "center" }); row.setAttribute("tabindex", "-1"); row.focus(); }
      };
    });
  }

  $("state").onchange = function () { state = this.value; render(); };
  $("showReq").onchange = function () { document.body.classList.toggle("show-req", this.checked); };
  $("reset").onclick = function () { var r = $("showReq").checked; init(); $("showReq").checked = r; };
  $("retry").onclick = function () { // mock of the scheduled retry (FR-42) succeeding
    M.efs.forEach(function (ef) { if (ef.fail) { var k = key(ef); retried[k] = true; if (M.retryResult[k]) M.summaries[k] = M.retryResult[k]; } });
    render();
  };
  init();
})();
