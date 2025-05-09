package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DependencyResolutionException;
import org.e2immu.analyzer.shallow.analyzer.Composer;
import org.e2immu.analyzer.shallow.analyzer.DecoratorImpl;
import org.e2immu.analyzer.shallow.analyzer.ToolChain;
import org.e2immu.language.cst.api.element.Comment;
import org.e2immu.language.cst.api.element.SourceSet;
import org.e2immu.language.cst.api.expression.ConstructorCall;
import org.e2immu.language.cst.api.expression.MethodCall;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;
import org.e2immu.language.inspection.api.integration.JavaInspector;
import org.e2immu.language.inspection.api.parser.ParseResult;
import org.e2immu.language.inspection.api.resource.InputConfiguration;
import org.e2immu.language.inspection.integration.JavaInspectorImpl;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = WriteAnnotatedAPIs.WRITE_AAPI_GOAL, defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class WriteAnnotatedAPIs extends CommonMojo {
    public static final String WRITE_AAPI_GOAL = "write-annotated-apis";

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}")
    private File outputDirectory;

    // not part of the output directory
    @Parameter(property = "packagePrefix", defaultValue = "")
    private String packagePrefix;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // Create output directory if it doesn't exist
            if (outputDirectory.mkdirs()) {
                getLog().info("Created " + outputDirectory.getAbsolutePath());
            }

            InputConfiguration inputConfiguration = makeInputConfiguration();
            JavaInspector javaInspector = new JavaInspectorImpl();

            InputConfiguration withJavaModules = inputConfiguration.withE2ImmuSupportFromClasspath().withDefaultModules();
            getLog().info("Working directory: " + withJavaModules.workingDirectory());
            javaInspector.initialize(withJavaModules);

            String jdkSpec = ToolChain.extractLibraryName(javaInspector.compiledTypesManager().typesLoaded(),
                    false);
            String mapped = ToolChain.mapJreShortNameToAnalyzedPackageShortName(jdkSpec);
            getLog().info("Resolved analyzed package files for " + jdkSpec + " -> " + mapped);

            JavaInspector.ParseOptions parseOptions = new JavaInspectorImpl.ParseOptionsBuilder()
                    .setFailFast(true).setDetailedSources(true).build();
            ParseResult parseResult = javaInspector.parse(parseOptions).parseResult();

            Map<MethodInfo, Integer> methodCallFrequencies = methodCallFrequencies(parseResult);
            getLog().info("Have method call frequencies for " + methodCallFrequencies.size() + " methods");

            Composer composer = new Composer(javaInspector, this::packagePrefixGenerator, w -> true);
            Set<TypeInfo> primaryTypes = javaInspector.compiledTypesManager()
                    .typesLoaded().stream().map(TypeInfo::primaryType)
                    .collect(Collectors.toUnmodifiableSet());
            getLog().info("Have " + primaryTypes + " primary types loaded");

            Collection<TypeInfo> apiTypes = composer.compose(primaryTypes);
            Map<Info, Info> dollarMap = composer.translateFromDollarToReal();
            Qualification.Decorator decorator = new DecoratorWithComments(getLog(), javaInspector.runtime(), dollarMap,
                    methodCallFrequencies);
            composer.write(apiTypes, outputDirectory, decorator);

        } catch (RuntimeException | IOException | DependencyResolutionException e) {
            throw new MojoExecutionException("Failed to write input configuration", e);
        }
    }

    private String packagePrefixGenerator(SourceSet sourceSet) {
        String pp = packagePrefix == null || packagePrefix.isBlank() ? "" : packagePrefix;
        if (sourceSet == null || sourceSet.name() == null || sourceSet.name().isBlank()) return pp;
        String name = sourceSet.name().toLowerCase().replaceAll("[.:-]", "_");
        if (name.endsWith("_jar")) name = name.substring(0, name.length() - 4);
        if (pp.isBlank()) return name;
        return pp + "." + name;
    }

    static class DecoratorWithComments extends DecoratorImpl {
        private final Map<MethodInfo, Integer> methodCallFrequencies;
        private final Runtime runtime;
        private final Map<Info, Info> translationMap;
        private final Log log;

        public DecoratorWithComments(Log log,
                                     Runtime runtime,
                                     Map<Info, Info> translationMap,
                                     Map<MethodInfo, Integer> methodCallFrequencies) {
            super(runtime, translationMap);
            this.translationMap = translationMap;
            this.log = log;
            this.runtime = runtime;
            this.methodCallFrequencies = methodCallFrequencies;
        }

        @Override
        public List<Comment> comments(Info infoIn) {
            Info info = translationMap == null ? infoIn : translationMap.getOrDefault(infoIn, infoIn);
            List<Comment> comments = super.comments(info);
            Integer frequency = info instanceof MethodInfo mi ? methodCallFrequencies.get(mi) : null;
            log.info("Frequency of " + info + " (from " + infoIn + ") = " + frequency);
            if (frequency != null) {
                Comment comment = runtime.newSingleLineComment("frequency " + frequency);
                return Stream.concat(Stream.of(comment), comments.stream()).toList();
            }
            return comments;
        }
    }

    private Map<MethodInfo, Integer> methodCallFrequencies(ParseResult parseResult) throws IOException {
        Map<MethodInfo, Integer> methodHistogram = new HashMap<>();
        parseResult.primaryTypes().stream()
                .flatMap(TypeInfo::recursiveSubTypeStream)
                .flatMap(TypeInfo::constructorAndMethodStream)
                .forEach(mi -> {
                    mi.methodBody().visit(e -> {
                        MethodInfo methodInfo = null;
                        if (e instanceof MethodCall mc &&
                            !parseResult.primaryTypes().contains(mc.methodInfo().typeInfo().primaryType())) {
                            methodInfo = mc.methodInfo();
                        } else if (e instanceof ConstructorCall cc && cc.constructor() != null
                                   && !parseResult.primaryTypes().contains(cc.constructor().typeInfo().primaryType())) {
                            methodInfo = cc.constructor();
                        }
                        if (methodInfo != null) {
                            methodHistogram.merge(methodInfo, 1, Integer::sum);
                        }
                        return true;
                    });
                });
        return methodHistogram;
    }

}
