package com.softcatch.smart.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.softcatch.smart.transfer.dto.response.TransferCreateResponse;

// transferCard는 송금 확인 카드가 있을 때만 응답에 포함한다(E-11). I-03 응답과 같은 모양이라 재사용.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageResponse(
    String reply, String guard, TransferCreateResponse transferCard) {}
