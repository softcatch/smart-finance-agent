package com.softcatch.smart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@Import(TestcontainersConfiguration.class)
// @SpringBootTest는 지표 내보내기를 기본으로 끈다 — Prometheus만 명시해서 켠다.
@SpringBootTest(properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("loadtest")
class MetricsExposureLoadtestTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void loadtest_프로파일에서는_prometheus_지표가_열린다() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/actuator/prometheus"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(
            MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
  }
}
