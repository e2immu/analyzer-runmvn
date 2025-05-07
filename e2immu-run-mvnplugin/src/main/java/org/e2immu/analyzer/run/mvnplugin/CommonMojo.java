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
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.e2immu.analyzer.run.config.util.JavaModules;
import org.e2immu.analyzer.run.config.util.JsonStreaming;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.resource.InputConfigurationImpl;
import org.e2immu.language.inspection.resource.SourceSetImpl;
import org.e2immu.util.internal.graph.G;
import org.e2immu.util.internal.graph.V;
import org.e2immu.util.internal.graph.op.Linearize;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;


public abstract class CommonMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "jre", defaultValue = "")
    private String jre;

    @Parameter(property = "workingDirectory", defaultValue = "${project.basedir}")
    private String workingDirectory;

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

    protected InputConfiguration makeInputConfiguration() throws DependencyResolutionException {
        InputConfiguration.Builder builder = new InputConfigurationImpl.Builder();
        builder.setAlternativeJREDirectory(jre);
        builder.setWorkingDirectory(workingDirectory);
        File absWorkingDirectory = workingDirectory == null || workingDirectory.isBlank()
                ? project.getBasedir().getAbsoluteFile() : new File(workingDirectory).getAbsoluteFile();

        Set<String> excludeFromClasspathSet = excludeFromClasspath == null || excludeFromClasspath.isBlank() ? Set.of() :
                Arrays.stream(excludeFromClasspath.split("[;,]\\s*")).collect(Collectors.toUnmodifiableSet());
        ComputeSourceSets.Result result = new ComputeSourceSets(absWorkingDirectory, dependenciesResolver, project,
                session, getLog()).compute(sourceEncoding, sourcePackages, testSourcePackages, excludeFromClasspathSet);

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
        Set<String> jmods = JavaModules.jmodsFromString(jmodsString);
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
}
