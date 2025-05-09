package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DependencyResolutionException;
import org.e2immu.analyzer.shallow.analyzer.Composer;
import org.e2immu.analyzer.shallow.analyzer.DecoratorImpl;
import org.e2immu.language.cst.api.element.Comment;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.MethodInfo;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.api.output.Qualification;
import org.e2immu.language.cst.api.runtime.Runtime;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = WriteAnnotatedAPIsMojo.WRITE_AAPI_GOAL, defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class WriteAnnotatedAPIsMojo extends CommonMojo {
    public static final String WRITE_AAPI_GOAL = "write-annotated-apis";

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/annotatedAPI")
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
            ParseSourcesResult psr = parseSources();
            Map<MethodInfo, Integer> methodCallFrequencies = methodCallFrequencies(psr.parseResult());
            getLog().info("Have method call frequencies for " + methodCallFrequencies.size() + " methods");

            Composer composer = new Composer(psr.javaInspector(),
                    set -> packagePrefixGenerator(packagePrefix, set),
                    w -> true);
            Set<TypeInfo> primaryTypes = psr.javaInspector().compiledTypesManager()
                    .typesLoaded().stream().map(TypeInfo::primaryType)
                    .collect(Collectors.toUnmodifiableSet());
            getLog().info("Have " + primaryTypes + " primary types loaded");

            Collection<TypeInfo> apiTypes = composer.compose(primaryTypes);
            Map<Info, Info> dollarMap = composer.translateFromDollarToReal();
            Qualification.Decorator decorator = new DecoratorWithComments(getLog(), psr.javaInspector().runtime(),
                    dollarMap, methodCallFrequencies);
            composer.write(apiTypes, outputDirectory, decorator);

        } catch (RuntimeException | IOException | DependencyResolutionException e) {
            throw new MojoExecutionException("Failed to write input configuration", e);
        }
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

}
