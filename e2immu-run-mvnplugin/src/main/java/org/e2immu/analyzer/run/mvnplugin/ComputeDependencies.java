package org.e2immu.analyzer.run.mvnplugin;

import org.e2immu.analyzer.run.config.util.JavaModules;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.util.internal.graph.G;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        HashSet<String> seen = new HashSet<>();

        LOGGER.info(" -- now recursing for source sets");
        List<String> mainSourceSets = new ArrayList<>();
        List<String> testSourceSets = new ArrayList<>();
        for (SourceSet sourceSet : result.sourceSetsByName().values()) {
            String name = sourceSet.name();

            recursionForSourceSets(builder, sourceSet, seen, jmods, 1);
            if (!sourceSet.externalLibrary()) {
                if (sourceSet.test()) {
                    testSourceSets.add(name);
                } else {
                    mainSourceSets.add(name);
                }
            }
        }
        for (String testName : testSourceSets) {
            LOGGER.info("ADDING SRC MAIN->TEST {} -> {}", testName, mainSourceSets);
            builder.add(testName, mainSourceSets);
        }

        return builder.build();
    }

    private void recursionForSourceSets(G.Builder<String> builder, SourceSet sourceSet,
                                        Set<String> seen, Set<String> jmods, int indent) {
        if (!seen.add(sourceSet.name())) return;
        LOGGER.info("@@".repeat(indent) + " enter recursion for {}", sourceSet.name());

        String name = sourceSet.name();
        builder.add(name, jmods);
        if (sourceSet.dependencies() != null) {
            for (SourceSet dep : sourceSet.dependencies()) {
                builder.add(name, List.of(dep.name()));
                recursionForSourceSets(builder, dep, seen, jmods, indent + 1);
            }
        }
    }
}
