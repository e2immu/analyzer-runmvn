package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.e2immu.analyzer.modification.linkedvariables.ModAnalyzer;
import org.e2immu.analyzer.modification.linkedvariables.ModAnalyzerImpl;
import org.e2immu.analyzer.modification.prepwork.PrepAnalyzer;
import org.e2immu.analyzer.modification.prepwork.callgraph.ComputeAnalysisOrder;
import org.e2immu.analyzer.shallow.analyzer.ToolChain;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.parser.Summary;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;
import org.e2immu.util.internal.graph.G;

import java.util.List;
import java.util.Set;

@Mojo(name = RunAnalyzerMojo.RUN_ANALYZER_GOAL,
        defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class RunAnalyzerMojo extends CommonMojo {
    public static final String RUN_ANALYZER_GOAL = "run";

    @Parameter(property = "modificationAnalysis", defaultValue = "true")
    private boolean modificationAnalysis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            InputConfiguration inputConfiguration = makeInputConfiguration();
            JavaInspector javaInspector = new JavaInspectorImpl();

            InputConfiguration withJavaModules = inputConfiguration.withE2ImmuSupportFromClasspath().withDefaultModules();
            getLog().info("Working directory: " + withJavaModules.workingDirectory());
            javaInspector.initialize(withJavaModules);

            Runtime runtime = javaInspector.runtime();

            String jdkSpec = ToolChain.extractLibraryName(javaInspector.compiledTypesManager().typesLoaded(),
                    false);
            String mapped = ToolChain.mapJreShortNameToAnalyzedPackageShortName(jdkSpec);
            getLog().info("Resolved analyzed package files for " + jdkSpec + " -> " + mapped);

            /*
            AnnotatedAPIConfiguration annotatedAPIConfiguration = new AnnotatedAPIConfigurationImpl.Builder()
                    .addAnalyzedAnnotatedApiDirs(ToolChain.jdkAnalyzedPackages(mapped))
                    .addAnalyzedAnnotatedApiDirs(ToolChain.commonLibsAnalyzedPackages())
                    .build();
            Codec codec = new LinkedVariablesCodec(runtime).codec();
            new LoadAnalyzedPackageFiles().go(codec, annotatedAPIConfiguration);
            */
            PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
            JavaInspector.ParseOptions parseOptions = new JavaInspectorImpl.ParseOptionsBuilder()
                    .setFailFast(true).setDetailedSources(true).build();
            Summary summary = javaInspector.parse(parseOptions);
            prepAnalyzer.initialize(javaInspector.compiledTypesManager().typesLoaded());
            G<Info> dependencyGraph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.copyOf(summary.types()));
            ComputeAnalysisOrder cao = new ComputeAnalysisOrder();
            List<Info> order = cao.go(dependencyGraph);
            if (getLog().isDebugEnabled()) {
                getLog().debug("Analysis order: " + order);
            }
            if (modificationAnalysis) {
                ModAnalyzer analyzer = new ModAnalyzerImpl(runtime, false);
                analyzer.go(order);
            } else {
                getLog().info("Skip modification analysis");
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run analyzer", e);
        }
    }
}
