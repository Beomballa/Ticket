package com.portfolio.fanevent.admin.application;

import java.util.List;

public record CursorPage<T>(List<T> content, String nextCursor, boolean hasNext) {

    public CursorPage {
        content = List.copyOf(content);
    }
}
