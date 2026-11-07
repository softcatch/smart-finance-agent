package com.softcatch.smart.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameMaskTest {

  @Test
  void 처음과_끝만_보이고_가운데는_가린다() {
    assertThat(NameMask.mask("김영희")).isEqualTo("김*희");
    assertThat(NameMask.mask("남궁민수")).isEqualTo("남**수");
  }

  @Test
  void 두_글자는_끝을_가리고_한_글자는_그대로다() {
    assertThat(NameMask.mask("이수")).isEqualTo("이*");
    assertThat(NameMask.mask("김")).isEqualTo("김");
  }
}
