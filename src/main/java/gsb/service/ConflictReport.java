package gsb.service;

import gsb.model.Polygon;

import java.util.List;

/** Returned when an edit races with a concurrent edit on the same base version. */
public record ConflictReport(
        long serverVersion,
        String conflictingEventId,
        String conflictingAuthor,
        List<Polygon> conflictPolygons,
        long conflictingPixels,
        List<AutoMergedRegion> autoMerged,
        String message) {

    /** Non-overlapping portions of the submitted edit that were merged automatically. */
    public record AutoMergedRegion(Polygon polygon, long pixels) {}
}
