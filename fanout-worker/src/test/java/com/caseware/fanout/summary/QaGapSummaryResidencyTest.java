package com.caseware.fanout.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** QA gap filler: NFR-35/36 residency. Only SummaryQuery/SummaryView-facing read facade may name EfId/FirmId. */
class QaGapSummaryResidencyTest {
  @Test
  @DisplayName("NFR-35 NFR-36: no summary type except the read facade mentions EfId or FirmId; BaselinesInUse returns bare versions")
  void noEfOrFirmTypes() throws Exception {
    File dir = new File(SummaryValidator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String[] names = new File(dir, "com/caseware/fanout/summary").list();
    List<String> offenders = new ArrayList<>();
    int scanned = 0;
    for (String n : names) {
      if (!n.endsWith(".class")) continue;
      Class<?> c = Class.forName("com.caseware.fanout.summary." + n.substring(0, n.length() - 6));
      if (c.getSimpleName().startsWith("SummaryQuery")) continue;
      scanned++;
      List<Class<?>> types = new ArrayList<>();
      for (Field f : c.getDeclaredFields()) types.add(f.getType());
      for (Constructor<?> k : c.getDeclaredConstructors()) types.addAll(List.of(k.getParameterTypes()));
      for (Method m : c.getDeclaredMethods()) { types.add(m.getReturnType()); types.addAll(List.of(m.getParameterTypes())); }
      if (c.isRecord()) for (RecordComponent rc : c.getRecordComponents()) types.add(rc.getType());
      for (Class<?> t : types) if (t == EfId.class || t == FirmId.class) offenders.add(c.getSimpleName() + " -> " + t.getSimpleName());
    }
    assertThat(scanned).isGreaterThan(30);
    assertThat(offenders).isEmpty();
    assertThat(BaselinesInUse.class.getDeclaredMethods()).hasSize(1);
    assertThat(BaselinesInUse.class.getDeclaredMethods()[0].getParameterTypes()).containsExactly(com.caseware.fanout.domain.TemplateId.class);
    assertThat(BaselinesInUse.class.getDeclaredMethods()[0].getGenericReturnType().getTypeName())
        .isEqualTo("java.util.Set<com.caseware.fanout.domain.VersionId>");
  }
}
