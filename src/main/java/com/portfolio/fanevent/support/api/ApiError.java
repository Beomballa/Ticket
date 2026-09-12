package com.portfolio.fanevent.support.api;

import java.util.List;

public record ApiError(
        String code,
        String message,
        String traceId,
        List<String> details
) {
}
