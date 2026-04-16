package com.sboxmarket.dto.response

/**
 * Generic page wrapper for list endpoints. Clients always get
 * total count + limit/offset back with the data, so paginated
 * clients never have to guess.
 */
class PageResponse<T> {
    List<T> items
    long total
    int limit
    int offset

    static <T> PageResponse<T> of(List<T> items, long total, int limit, int offset) {
        new PageResponse<T>(items: items, total: total, limit: limit, offset: offset)
    }
}
