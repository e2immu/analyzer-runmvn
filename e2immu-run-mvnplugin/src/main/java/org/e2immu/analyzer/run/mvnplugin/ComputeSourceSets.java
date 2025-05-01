package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.project.MavenProject;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.language.inspection.resource.SourceSetImpl;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ComputeSourceSets {

    public Result compute(MavenProject project,
                          String sourceEncoding,
                          String sourcePackages,
                          String testSourcePackages,
                          Set<String> excludeFromClasspathSet) {
        Map<String, SourceSet> sourceSetsByName = new HashMap<>();
        String projectName = project.getName();
        Charset encoding = Charset.forName(sourceEncoding, Charset.defaultCharset());

        List<Path> sourcePaths = project.getCompileSourceRoots().stream().map(Path::of).toList();
        if (!sourcePaths.isEmpty()) {
            Set<String> restrictToPackages = stringToSet(sourcePackages);
            SourceSet testSourceSet = new SourceSetImpl(projectName + "/main",
                    sourcePaths, sourcePaths.getFirst().toUri(), encoding, true, false, false,
                    false, false, restrictToPackages, null);
            sourceSetsByName.put(testSourceSet.name(), testSourceSet);
        }
        List<Path> testSourcePaths = project.getTestCompileSourceRoots().stream().map(Path::of).toList();
        if (!testSourcePaths.isEmpty()) {
            Set<String> restrictToTestPackages = stringToSet(testSourcePackages);
            SourceSet testSourceSet = new SourceSetImpl("test", testSourcePaths, testSourcePaths.getFirst().toUri(),
                    encoding, true, false, false, false, false,
                    restrictToTestPackages, null);
            sourceSetsByName.put(testSourceSet.name(), testSourceSet);
        }
        return new Result("main", sourceSetsByName, List.of());
    }

    private static Set<String> stringToSet(String sourcePackages) {
        return sourcePackages == null || sourcePackages.isBlank() ? null :
                Arrays.stream(sourcePackages.split("[,;]\\s*"))
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
    }

    public record Result(String mainSourceSetName, Map<String, SourceSet> sourceSetsByName,
                         List<Result> sourceSetDependencies) {
    }
}
