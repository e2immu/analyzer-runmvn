package org.e2immu.analyzer.run.mvnplugin;

import org.e2immu.analyzer.run.config.util.JavaModules;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.util.internal.graph.G;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ComputeDependencies {
    private static final Logger LOGGER = LoggerFactory.getLogger(ComputeDependencies.class);

    public G<String> go(ComputeSourceSets.Result result) {
        G.Builder<String> builder = new G.Builder<>(Long::sum);

        // jmods are common
        Set<String> jmods = new HashSet<>();
        for (SourceSet sourceSet : result.sourceSetsByName().values()) {
            if (sourceSet.partOfJdk()) {
                String jmod = sourceSet.name();
                Set<String> dependencies = JavaModules.jmodDependency(jmod);
                LOGGER.info("Adding JMOD {} -> {}", jmod, dependencies);
                builder.add(jmod, dependencies);
                jmods.add(jmod);
            }
        }
        Map<String, Boolean> jmodsAndExternalToMain = new HashMap<>();
        jmods.forEach(jmod -> jmodsAndExternalToMain.put(jmod, true));

        return builder.build();
    }


}
