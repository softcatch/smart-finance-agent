package com.softcatch.smart.chat.dto.response;

import java.util.List;

// 재무 비교 표(E-11 data.table). FastAPI가 만든 표를 그대로 전달한다.
public record ChatTable(List<String> columns, List<List<String>> rows, String note) {}
