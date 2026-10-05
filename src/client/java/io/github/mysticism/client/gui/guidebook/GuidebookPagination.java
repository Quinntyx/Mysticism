package io.github.mysticism.client.gui.guidebook;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public final class GuidebookPagination {
    private GuidebookPagination() {}
    /** Pack already-wrapped rows. Oversized rows are rejected, never silently clipped. */
    public static <T> List<List<T>> pack(List<T> rows, int capacity, ToIntFunction<T> height) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid page height");
        List<List<T>> pages = new ArrayList<>();
        List<T> page = new ArrayList<>();
        int used = 0;
        for (T row : rows) {
            int h = height.applyAsInt(row);
            if (h < 1 || h > capacity) throw new IllegalArgumentException("Invalid row height");
            if (h > capacity - used) { pages.add(List.copyOf(page)); page.clear(); used = 0; }
            page.add(row); used += h;
        }
        if (!page.isEmpty() || pages.isEmpty()) pages.add(List.copyOf(page));
        return List.copyOf(pages);
    }
    public static int clamp(int page, int count) { return Math.clamp(page, 0, Math.max(0, count - 1)); }
}
