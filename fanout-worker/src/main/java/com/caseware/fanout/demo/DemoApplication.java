package com.caseware.fanout.demo;

import com.caseware.fanout.config.FanoutConfiguration;
import com.caseware.fanout.config.StatusConfiguration;
import com.caseware.fanout.config.SummaryConfiguration;
import com.caseware.fanout.web.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * DEV ONLY demo: the web layer over the in-memory beans with seeded data. Run with
 * {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo}. Component scanning covers only
 * this package (DemoSeed); the configurations are imported explicitly, and the capture beans come
 * with {@link FanoutConfiguration} (nested configuration, conditional on fanout.capture.enabled).
 */
@SpringBootApplication
@Import({
  FanoutConfiguration.class,
  StatusConfiguration.class,
  SummaryConfiguration.class,
  WebConfiguration.class
})
public class DemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
