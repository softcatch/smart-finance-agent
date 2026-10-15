package com.softcatch.smart.transfer.dto.response;

import java.util.List;

// I-04: 기간 내 합계(Spring이 계산)와 내역. 합계·건수는 페이지가 아니라 기간 전체 기준이다.
public record TransferSummaryResponse(
    Long sentTotal,
    Long receivedTotal,
    int count,
    List<TransferHistoryItem> items,
    Long nextCursor,
    boolean hasNext) {}
