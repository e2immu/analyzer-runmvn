package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.project.MavenProject;
import org.e2immu.language.cst.api.element.SourceSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ComputeSourceSets {

    public Result compute(MavenProject project, String sourcePackages,
                          String testSourcePackages, Set<String> excludeFromClasspathSet) {
        Map<String, SourceSet> sourceSetsByName = new HashMap<>();
        return new Result("main", sourceSetsByName, List.of());
    }

    public record Result(String mainSourceSetName, Map<String, SourceSet> sourceSetsByName,
                         List<Result> sourceSetDependencies) {
    }
}
