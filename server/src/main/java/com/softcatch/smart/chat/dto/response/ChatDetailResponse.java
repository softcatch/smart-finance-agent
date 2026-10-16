package com.softcatch.smart.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

// 상세 보기(E-13). 보고서는 FastAPI가 클릭할 때 만들고, table은 데이터가 없으면 빠진다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatDetailResponse(String detailId, String report, ChatTable table) {}
