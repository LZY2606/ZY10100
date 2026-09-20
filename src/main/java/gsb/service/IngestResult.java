package gsb.service;

import gsb.model.Source;

import java.util.List;

/** Outcome of ingesting a source. */
public record IngestResult(Source source, boolean resampled, List<String> warnings) {
}
