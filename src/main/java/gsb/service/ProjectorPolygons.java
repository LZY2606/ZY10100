package gsb.service;

import gsb.store.Model;

import java.util.ArrayList;
import java.util.List;

final class ProjectorPolygons {
    private ProjectorPolygons() {}

    @SuppressWarnings("unchecked")
    static List<List<Double>> from(List<Object> raw) {
        List<List<Double>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            List<Object> pt = (List<Object>) o;
            out.add(List.of(((Number) pt.get(0)).doubleValue(),
                    ((Number) pt.get(1)).doubleValue()));
        }
        return out;
    }
}
