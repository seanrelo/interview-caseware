package com.caseware.fanout.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatusModuleStructureTest {

  private static final Set<String> LOADERS =
      Set.of(
          "com.caseware.fanout.capture.EfLoader",
          "com.caseware.fanout.capture.EfCatalog",
          "com.caseware.fanout.adapter.memory.InMemoryEfLoader",
          "com.caseware.fanout.adapter.memory.InMemoryEfCatalog");

  @Test
  @DisplayName("U-21 NFR-21: no status class references an EF loader type")
  void noLoaderDependency() throws Exception {
    List<Class<?>> classes = statusClasses();
    assertThat(classes).extracting(Class::getSimpleName).contains("StatusService", "StatusDeriver", "StatusRefreshingMarker");
    List<String> offenders = new ArrayList<>();
    for (Class<?> c : classes) {
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
      for (Class<?> t : types) {
        if (t != null && LOADERS.contains(t.getName())) {
          offenders.add(c.getName() + " -> " + t.getName());
        }
      }
    }
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("NFR-28: status package has no Spring dependency")
  void springFree() throws Exception {
    for (Class<?> c : statusClasses()) {
      for (Field f : c.getDeclaredFields()) {
        assertThat(f.getType().getName()).doesNotStartWith("org.springframework");
      }
      assertThat(c.getAnnotations())
          .noneMatch(a -> a.annotationType().getName().startsWith("org.springframework"));
    }
  }

  private static List<Class<?>> statusClasses() throws URISyntaxException, ClassNotFoundException {
    File dir = new File(StatusService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File pkg = new File(dir, "com/caseware/fanout/status");
    List<Class<?>> out = new ArrayList<>();
    for (String name : pkg.list()) {
      if (name.endsWith(".class")) {
        out.add(Class.forName("com.caseware.fanout.status." + name.substring(0, name.length() - 6)));
      }
    }
    return out;
  }
}
