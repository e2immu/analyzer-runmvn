package org.e2immu.analyzer.run.mvnplugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DependencyResolutionException;
import org.e2immu.analyzer.shallow.analyzer.ShallowAnalyzer;
import org.e2immu.analyzer.shallow.analyzer.WriteAnalysis;
import org.e2immu.language.cst.api.analysis.Codec;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.e2immu.language.cst.impl.analysis.PropertyProviderImpl;
import org.e2immu.language.cst.io.CodecImpl;
import org.e2immu.util.internal.util.Trie;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Mojo(name = CompileAnnotatedAPIsMojo.COMPILE_AAPI_GOAL, defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class CompileAnnotatedAPIsMojo extends CommonMojo {
    public static final String COMPILE_AAPI_GOAL = "compile-annotated-apis";

    @Parameter(property = "inputDirectory", defaultValue = "${project.build.directory}/annotatedAPI")
    private File inputDirectory;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/compiledAPI")
    private File outputDirectory;

    // only write for these packages
    @Parameter(property = "restrictToPackages", defaultValue = "")
    private String restrictToPackages;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // Create output directory if it doesn't exist
            if (outputDirectory.mkdirs()) {
                getLog().info("Created " + outputDirectory.getAbsolutePath());
            }
            ParseSourcesResult psr = parseSources();

            // 1. load the AAPI files
            // 2. run the shallow analyzer on all the loaded types
            ShallowAnalyzer shallowAnalyzer = new ShallowAnalyzer(annotatedApiParser);
            List<TypeInfo> types = shallowAnalyzer.go();

            // 3. write out the result
            Trie<TypeInfo> typeTrie = new Trie<>();
            types.forEach(ti -> typeTrie.add(ti.packageName().split("\\."), ti));

            WriteAnalysis writeAnalysis = new WriteAnalysis(psr.javaInspector().runtime());
            Codec codec = new CodecImpl(psr.javaInspector().runtime(), PropertyProviderImpl::get,
                    null, null); // we don't have to decode
            writeAnalysis.write(outputDirectory, typeTrie, codec,
                    set -> packagePrefixGenerator("", set));

        } catch (RuntimeException | IOException | DependencyResolutionException e) {
            throw new MojoExecutionException("Failed to write input configuration", e);
        }
    }

}
