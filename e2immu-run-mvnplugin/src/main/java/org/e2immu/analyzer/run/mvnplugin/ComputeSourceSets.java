package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.*;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.language.inspection.resource.SourceSetImpl;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class ComputeSourceSets {

    private final Path absWorkingDirectory;
    private final ProjectDependenciesResolver dependenciesResolver;
    private final MavenProject project;
    private final MavenSession session;
    private final Log log;

    public ComputeSourceSets(File absWorkingDirectory, ProjectDependenciesResolver dependenciesResolver,
                             MavenProject mavenProject, MavenSession mavenSession, Log log) {
        this.absWorkingDirectory = absWorkingDirectory.toPath();
        this.dependenciesResolver = dependenciesResolver;
        this.project = mavenProject;
        this.session = mavenSession;
        this.log = log;
    }

    public Result compute(String sourceEncoding,
                          String sourcePackages,
                          String testSourcePackages,
                          Set<String> excludeFromClasspathSet) throws DependencyResolutionException {
        Map<String, SourceSet> sourceSetsByName = new HashMap<>();
        String projectName = project.getName();
        Charset encoding = Charset.forName(sourceEncoding, Charset.defaultCharset());

        List<Path> sourcePaths = project.getCompileSourceRoots().stream()
                .map(path -> absWorkingDirectory.relativize(Path.of(path))).toList();
        if (!sourcePaths.isEmpty()) {
            Set<String> restrictToPackages = stringToSet(sourcePackages);

            SourceSet testSourceSet = new SourceSetImpl(projectName + "/main",
                    sourcePaths, URI.create("file:" + sourcePaths.getFirst()),
                    encoding, false, false, false,
                    false, false, restrictToPackages, null);
            sourceSetsByName.put(testSourceSet.name(), testSourceSet);
        }
        List<Path> testSourcePaths = project.getTestCompileSourceRoots().stream()
                .map(path -> absWorkingDirectory.relativize(Path.of(path))).toList();
        if (!testSourcePaths.isEmpty()) {
            Set<String> restrictToTestPackages = stringToSet(testSourcePackages);

            SourceSet testSourceSet = new SourceSetImpl(projectName + "/test", testSourcePaths,
                    URI.create("file:" + testSourcePaths.getFirst()),
                    encoding, true, false, false, false, false,
                    restrictToTestPackages, null);
            sourceSetsByName.put(testSourceSet.name(), testSourceSet);
        }

        computeClassPathParts(JavaScopes.COMPILE, false, false, sourceSetsByName);
        computeClassPathParts(JavaScopes.TEST, true, false, sourceSetsByName);
        computeClassPathParts(JavaScopes.PROVIDED, false, true, sourceSetsByName);
        computeClassPathParts(JavaScopes.RUNTIME, false, true, sourceSetsByName);

        return new Result("main", sourceSetsByName, List.of());
    }

    private void computeClassPathParts(String scope, boolean test, boolean runtimeOnly, Map<String, SourceSet> sourceSetsByName)
            throws DependencyResolutionException {

        // Create dependency request for this scope
        DependencyFilter classpathFilter = DependencyFilterUtils.classpathFilter(scope);
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);

        // Resolve the dependencies
        DependencyResolutionRequest resolutionRequest = new DefaultDependencyResolutionRequest();
        resolutionRequest.setMavenProject(project);
        resolutionRequest.setRepositorySession(session.getRepositorySession());

        DependencyResolutionResult resolutionResult = dependenciesResolver.resolve(resolutionRequest);

        // Process resolution result
        processDependencyNodes(resolutionResult.getDependencyGraph(), test, runtimeOnly, sourceSetsByName);


    }

    private void processDependencyNodes(DependencyNode node, boolean test, boolean runtimeOnly, Map<String, SourceSet> sourceSetsByName) {
        for (DependencyNode child : node.getChildren()) {
            Artifact artifact = child.getArtifact();
            String name = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            if (!sourceSetsByName.containsKey(name)) {
                URI uri = URI.create("file:" + artifact.getFile().getPath());
                SourceSet sourceSet = new SourceSetImpl(name, null, uri, null, test,
                        true, true, false, runtimeOnly, null, null);
                sourceSetsByName.put(name, sourceSet);
                log.info("Added class path part " + name);
            }

            if (!child.getChildren().isEmpty()) {
                processDependencyNodes(child, test, runtimeOnly, sourceSetsByName);
            }

        }
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
