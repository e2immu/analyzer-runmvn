package org.e2immu.analyzer.run.mvnplugin;

import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.util.internal.graph.G;

import java.util.List;
import java.util.Map;

public class ComputeDependencies {
    public G<String> go(ComputeSourceSets.Result result) {
        G.Builder<String> builder = new G.Builder<>(Long::sum);
        return builder.build();
    }


}
