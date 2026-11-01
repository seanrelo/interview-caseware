package com.caseware.fanout.summary.eval;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.summary.Diff;
import com.caseware.fanout.summary.DiffItem;
import com.caseware.fanout.summary.PairKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line format, '#' comments and blank lines ignored:
 * <pre>
 * case NAME
 * pair TEMPLATE BASELINE TARGET
 * item ID | PATH | SECTION | CHANGE
 * support LABEL | ID,ID
 * expect ID,ID
 * end
 * </pre>
 */
public final class EvalCaseLoader {
  private EvalCaseLoader() {}

  public static List<EvalCase> loadResource(String resource) throws IOException {
    try (InputStream in = EvalCaseLoader.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("resource not found: " + resource);
      }
      return load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }

  public static List<EvalCase> load(Reader reader) throws IOException {
    List<EvalCase> cases = new ArrayList<>();
    BufferedReader br = new BufferedReader(reader);
    String name = null;
    PairKey pair = null;
    List<DiffItem> items = new ArrayList<>();
    Map<String, Set<String>> support = new LinkedHashMap<>();
    Set<String> expected = new LinkedHashSet<>();
    String line;
    int n = 0;
    while ((line = br.readLine()) != null) {
      n++;
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int sp = line.indexOf(' ');
      String key = sp < 0 ? line : line.substring(0, sp);
      String rest = sp < 0 ? "" : line.substring(sp + 1).strip();
      switch (key) {
        case "case" -> {
          name = rest;
          pair = null;
          items = new ArrayList<>();
          support = new LinkedHashMap<>();
          expected = new LinkedHashSet<>();
        }
        case "pair" -> {
          String[] p = rest.split("\\s+");
          require(p.length == 3, n, "pair needs TEMPLATE BASELINE TARGET");
          pair = new PairKey(new TemplateId(p[0]), new VersionId(p[1]), new VersionId(p[2]));
        }
        case "item" -> {
          String[] p = rest.split("\\|", -1);
          require(p.length == 4, n, "item needs ID | PATH | SECTION | CHANGE");
          items.add(new DiffItem(p[0].strip(), p[1].strip(), p[2].strip(), p[3].strip()));
        }
        case "support" -> {
          String[] p = rest.split("\\|", -1);
          require(p.length == 2, n, "support needs LABEL | IDS");
          support.put(p[0].strip(), ids(p[1]));
        }
        case "expect" -> expected.addAll(ids(rest));
        case "end" -> {
          require(name != null && pair != null, n, "end without case/pair");
          cases.add(new EvalCase(name, new Diff(pair, items), support, expected));
          name = null;
        }
        default -> throw new IOException("line " + n + ": unknown directive " + key);
      }
    }
    require(name == null, n, "case " + name + " not closed with end");
    return List.copyOf(cases);
  }

  private static Set<String> ids(String s) {
    Set<String> out = new LinkedHashSet<>();
    Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty()).forEach(out::add);
    return out;
  }

  private static void require(boolean ok, int line, String msg) throws IOException {
    if (!ok) {
      throw new IOException("line " + line + ": " + msg);
    }
  }
}
