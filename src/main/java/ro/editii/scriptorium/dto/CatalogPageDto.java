package ro.editii.scriptorium.dto;

import java.util.List;

/** Small, stable page envelope for the online reader catalogue. */
public record CatalogPageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
}
