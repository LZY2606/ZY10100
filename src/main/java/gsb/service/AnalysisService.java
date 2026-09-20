package gsb.service;

import gsb.analysis.Analysis;
import gsb.json.Json;
import gsb.mask.Mask;
import gsb.store.ApiException;
import gsb.store.Model;
import gsb.store.Workspace;

import java.util.HashMap;
import java.util.Map;

/** Threshold changes and (re)computation of the derived consensus raster. */
public final class AnalysisService {
    private AnalysisService() {}

    public static void setThreshold(Workspace ws, double threshold, String actor) {
        if (!(threshold >= 0 && threshold <= 1)) {
            throw ApiException.badRequest("BAD_THRESHOLD",
                    "confidence threshold must be within [0,1], got " + threshold);
        }
        ws.append("threshold.changed", Json.obj("threshold", threshold), actor);
        MappingService.proposeForActiveLabels(ws);
    }

    public static Analysis compute(Workspace ws) {
        Model m = ws.model();
        if (m.image == null) {
            throw ApiException.badRequest("NO_IMAGE", "create the base image first");
        }
        Map<String, Mask> masks = new HashMap<>();
        for (Model.SourceVersion v : m.activeVersions()) {
            ws.auditEvidence(v);
            masks.put(v.id, ws.alignedMask(v));
        }
        return Analysis.compute(m, masks);
    }
}
