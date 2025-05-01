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
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

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

    @Component
    private ProjectDependenciesResolver dependenciesResolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // Create output directory if it doesn't exist
            outputFile.getParentFile().mkdirs();

            // Collect and export dependency information
            collectAndExportDependencies();

            getLog().info("Dependency tree exported to: " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to export dependency tree", e);
        }
    }

    private void collectAndExportDependencies() throws DependencyResolutionException, IOException {
        // Get all scopes you want to analyze
        List<String> scopes = Arrays.asList(
                JavaScopes.COMPILE,
                JavaScopes.RUNTIME,
                JavaScopes.TEST,
                JavaScopes.PROVIDED
        );

        // Create result object for JSON output
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> scopeData = new HashMap<>();
        result.put("project", project.getId());
        result.put("dependencies", scopeData);

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

        // Write result to file as JSON
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, result);
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
