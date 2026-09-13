package net.onelitefeather.ploceus.ui;

import java.util.List;

public record Page<T>(List<T> items, int pageNumber, int pageSize, int totalItems) {

    public static <T> Page<T> of(List<T> all, int pageNumber, int pageSize) {
        int from = Math.min(pageNumber * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new Page<>(List.copyOf(all.subList(from, to)), pageNumber, pageSize, all.size());
    }

    public int totalPages() {
        return Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
    }

    public boolean hasPrevious() {
        return pageNumber > 0;
    }

    public boolean hasNext() {
        return pageNumber < totalPages() - 1;
    }
}
