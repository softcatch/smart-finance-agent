package com.softcatch.smart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MetricsExposureDefaultTest {

  @Autowired private MockMvc mockMvc;

  // 지표는 측정 환경(loadtest 프로파일)에서만 연다. 기본 설정에서는 밖에 노출하지 않는다.
  @Test
  void 기본_설정에서는_prometheus_지표를_열지_않는다() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/actuator/prometheus"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
