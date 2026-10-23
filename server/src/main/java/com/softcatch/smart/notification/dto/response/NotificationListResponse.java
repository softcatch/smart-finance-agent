package com.softcatch.smart.notification.dto.response;

import java.util.List;

public record NotificationListResponse(
    List<NotificationItem> items, Long nextCursor, boolean hasNext) {}
