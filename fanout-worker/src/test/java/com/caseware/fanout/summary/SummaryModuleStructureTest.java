package com.caseware.fanout.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryModuleStructureTest {
  private static final Set<String> LOADERS =
      Set.of(
          "com.caseware.fanout.capture.EfLoader",
          "com.caseware.fanout.capture.EfCatalog",
          "com.caseware.fanout.adapter.memory.InMemoryEfLoader",
          "com.caseware.fanout.adapter.memory.InMemoryEfCatalog");

  @Test
  @DisplayName("NFR-21 NFR-35: no summary class references an EF loader or catalog type")
  void noLoader() throws Exception {
    List<Class<?>> classes = summaryClasses();
    assertThat(classes).extracting(Class::getSimpleName).contains("SummaryValidator", "SummaryRecord", "ModelClient");
    List<String> offenders = new ArrayList<>();
    for (Class<?> c : classes) {
      for (Class<?> t : types(c)) {
        if (t != null && LOADERS.contains(t.getName())) {
          offenders.add(c.getName() + " -> " + t.getName());
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("Part 2: summary package has no Spring dependency")
  void springFree() throws Exception {
    for (Class<?> c : summaryClasses()) {
      for (Class<?> t : types(c)) {
        if (t != null) {
          assertThat(t.getName()).as(c.getName()).doesNotStartWith("org.springframework");
        }
      }
      assertThat(c.getAnnotations()).noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
    }
  }

  private static List<Class<?>> types(Class<?> c) {
    List<Class<?>> types = new ArrayList<>();
    types.add(c.getSuperclass());
    types.addAll(List.of(c.getInterfaces()));
    for (Field f : c.getDeclaredFields()) {
      types.add(f.getType());
    }
    for (Constructor<?> k : c.getDeclaredConstructors()) {
      types.addAll(List.of(k.getParameterTypes()));
    }
    for (Method m : c.getDeclaredMethods()) {
      types.add(m.getReturnType());
      types.addAll(List.of(m.getParameterTypes()));
    }
    return types;
  }

  private static List<Class<?>> summaryClasses() throws Exception {
    File dir = new File(SummaryValidator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File pkg = new File(dir, "com/caseware/fanout/summary");
    List<Class<?>> out = new ArrayList<>();
    for (String name : pkg.list()) {
      if (name.endsWith(".class")) {
        out.add(Class.forName("com.caseware.fanout.summary." + name.substring(0, name.length() - 6)));
      }
    }
    return out;
  }
}
