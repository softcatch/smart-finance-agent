package com.softcatch.smart.alias.dto.response;

import com.softcatch.smart.common.NameMask;

// 등록(E-07)·목록(E-08). 계좌번호만으로 실명을 모으지 못하게 예금주 이름은 가려서 내보낸다.
public record AliasResponse(Long aliasId, String alias, String accountNumber, String ownerName) {

  public AliasResponse {
    ownerName = NameMask.mask(ownerName);
  }
}
