package org.e2immu.analyzer.run.mvnplugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.*;
import org.e2immu.analyzer.run.config.util.JsonStreaming;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.e2immu.language.inspection.resource.SourceSetImpl;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.op.Linearize;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "export", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class DependencyExporterMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepos;

    @Parameter(property = "outputFile", defaultValue = "${project.build.directory}/dependency-tree.json")
    private File outputFile;

    @Parameter(property = "jre", defaultValue = "")
    private String jre;

    @Parameter(property = "excludeFromClasspath", defaultValue = "")
    private String excludeFromClasspath;

    @Parameter(property = "jmods", defaultValue = "java.base")
    private String jmods;


    @Parameter(property = "testSourcePackages", defaultValue = "")
    private String testSourcePackages;


    @Parameter(property = "sourcePackages", defaultValue = "")
    private String sourcePackages;

    @Parameter(property = "sourceEncoding", defaultValue = "UTF-8")
    private String sourceEncoding;

    @Component
    private ProjectDependenciesResolver dependenciesResolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // Create output directory if it doesn't exist
            if (outputFile.getParentFile().mkdirs()) {
                getLog().info("Created directories for " + outputFile.getAbsolutePath());
            }
            InputConfiguration inputConfiguration = makeInputConfiguration();
            ObjectMapper mapper = JsonStreaming.objectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, inputConfiguration);
            getLog().info("Dependency tree exported to: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to export dependency tree", e);
        }
    }

    private InputConfiguration makeInputConfiguration() throws DependencyResolutionException {
        InputConfiguration.Builder builder = new InputConfigurationImpl.Builder();
        builder.setAlternativeJREDirectory(jre);

        Set<String> excludeFromClasspathSet = excludeFromClasspath == null || excludeFromClasspath.isBlank() ? Set.of() :
                Arrays.stream(excludeFromClasspath.split("[;,]\\s*")).collect(Collectors.toUnmodifiableSet());
        ComputeSourceSets.Result result = new ComputeSourceSets().compute(project, sourceEncoding, sourcePackages,
                testSourcePackages, excludeFromClasspathSet);

        makeJavaModules(jmods).forEach(set -> result.sourceSetsByName().put(set.name(), set));

        G<String> graph = new ComputeDependencies().go(result);
        List<String> linearization = Linearize.linearize(graph).asList(String::compareToIgnoreCase);
        getLog().info("Graph: " + graph);
        getLog().info("Linearization:\n  " + String.join("\n  ", linearization) + "\n");
        for (String name : linearization) {
            Map<V<String>, Long> edges = graph.edges(new V<>(name));
            Set<SourceSet> dependencies = edges == null ? Set.of() : edges.keySet()
                    .stream().map(v -> result.sourceSetsByName().get(v.t()))
                    .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
            SourceSet sourceSet = result.sourceSetsByName().get(name);
            if (sourceSet == null) {
                getLog().warn("Don't know source set " + name);
            } else {
                SourceSet set = sourceSet.withDependencies(dependencies);
                if (!set.externalLibrary()) builder.addSourceSets(set);
                else builder.addClassPathParts(set);
            }
        }
        return builder.build();
    }

    private List<SourceSet> makeJavaModules(String jmodsString) {
        List<SourceSet> sets = new ArrayList<>();
        Set<String> jmods = new HashSet<>();
        Collections.addAll(jmods, "java.base");
        if (jmodsString != null && !jmodsString.isBlank()) {
            String[] split = jmodsString.split("[,;]\\s*");
            Collections.addAll(jmods, split);
        }
        for (String jmod : jmods) {
            if (!jmod.isBlank()) {
                SourceSet set = new SourceSetImpl(jmod, null,
                        URI.create("jmod:" + jmod),
                        null, false, true, true, true, false,
                        null, null);
                sets.add(set);
            }
        }
        return sets;
    }


    private void computeDependencies() throws DependencyResolutionException {
        // Get all scopes you want to analyze
        List<String> scopes = Arrays.asList(
                JavaScopes.COMPILE,
                JavaScopes.TEST,
                JavaScopes.RUNTIME,
                JavaScopes.PROVIDED
        );


        // Create result object for JSON output
        Map<String, Object> scopeData = new HashMap<>();


        // Process each scope
        for (String scope : scopes) {
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
            List<Map<String, Object>> scopeDependencies = processDependencyNodes(resolutionResult.getDependencyGraph());
            scopeData.put(scope, scopeDependencies);
        }

    }

    private List<Map<String, Object>> processDependencyNodes(DependencyNode node) {
        List<Map<String, Object>> dependencies = new ArrayList<>();

        for (DependencyNode child : node.getChildren()) {
            Map<String, Object> dependency = new HashMap<>();
            Artifact artifact = child.getArtifact();

            dependency.put("groupId", artifact.getGroupId());
            dependency.put("artifactId", artifact.getArtifactId());
            dependency.put("version", artifact.getVersion());
            dependency.put("type", artifact.getExtension());
            dependency.put("scope", child.getDependency().getScope());
            dependency.put("optional", child.getDependency().isOptional());

            if (!child.getChildren().isEmpty()) {
                dependency.put("dependencies", processDependencyNodes(child));
            }

            dependencies.add(dependency);
        }

        return dependencies;
    }
}
