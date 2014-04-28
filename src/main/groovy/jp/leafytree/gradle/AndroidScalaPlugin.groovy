/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jp.leafytree.gradle

import com.google.common.annotations.VisibleForTesting
import org.apache.commons.io.FileUtils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.util.ConfigureUtil

import javax.inject.Inject
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * AndroidScalaPlugin adds scala language support to official gradle android plugin.
 */
public class AndroidScalaPlugin implements Plugin<Project> {
    private final FileResolver fileResolver
    @VisibleForTesting
    final Map<String, SourceDirectorySet> sourceDirectorySetMap = new HashMap<>()
    private final AndroidScalaPluginExtension extension = new AndroidScalaPluginExtension()
    private Project project
    private Object androidExtension
    private File workDir

    /**
     * Creates a new AndroidScalaJavaJointCompiler with given file resolver.
     *
     * @param fileResolver the FileResolver
     */
    @Inject
    public AndroidScalaPlugin(FileResolver fileResolver) {
        this.fileResolver = fileResolver
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    void apply(Project project, Object androidExtension) {
        this.project = project
        this.androidExtension = androidExtension
        this.workDir = new File(project.buildDir, "android-scala")
        updateAndroidExtension()
        updateAndroidSourceSetsExtension()
        project.afterEvaluate {
            addDependencies()
            def isApplication = project.plugins.hasPlugin("android")
            androidExtension.testVariants.each { variant ->
                if (isApplication) {
                    updateTestedVariantProguardTask(variant)
                }
                if (extension.runAndroidTestProguard) {
                    updateTestVariantDexTask(variant)
                }
            }
            def allVariants = androidExtension.testVariants + (isApplication ? androidExtension.applicationVariants : androidExtension.libraryVariants)
            allVariants.each { variant ->
                updateAndroidJavaCompileTask(variant.javaCompile)
            }
        }
        androidExtension.dexOptions.preDexLibraries = false
        project.gradle.taskGraph.whenReady { taskGraph ->
            if (androidExtension.dexOptions.preDexLibraries) {
                throw new GradleException("Currently, android-scala plugin doesn't support enabling dexOptions.preDexLibraries")
            }
        }
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    public void apply(Project project) {
        if (!project.plugins.findPlugin("android") && !project.plugins.findPlugin("android-library")) {
            throw new GradleException("Please apply 'android' or 'android-library' plugin before applying 'android-scala' plugin")
        }
        apply(project, project.extensions.getByName("android"))
    }

    /**
     * Adds dependencies to execute plugin.
     */
    void addDependencies() {
        project.repositories.maven { url "http://repository-dex2jar.forge.cloudbees.com/release/" }
        project.configurations {
            androidScalaPluginProGuard
        }
        project.dependencies.add("androidScalaPluginProGuard", "net.sf.proguard:proguard-anttask:4.11")
    }

    /**
     * Update tested variant's proguard task to keep classes which test classes depend on
     *
     * @param testVariant the TestVariant
     */
    void updateTestedVariantProguardTask(final Object testVariant) {
        final def proguardTask = testVariant.testedVariant.proguard
        if (!proguardTask) {
            return
        }
        def testJavaCompileTask = testVariant.javaCompile
        final def javaCompileDestinationDir = testJavaCompileTask.destinationDir
        proguardTask.dependsOn(testJavaCompileTask)
        proguardTask.injars(javaCompileDestinationDir)
        proguardTask.keepdirectories(javaCompileDestinationDir.toString())
        testVariant.variantData.variantConfiguration.compileClasspath.each {
            proguardTask.libraryjars(it)
        }
        proguardTask.doLast {
            // TODO: More elegant way
            def testFiles = []
            def baseLength = javaCompileDestinationDir.canonicalPath.length()
            javaCompileDestinationDir.traverse { file ->
                if (!file.isDirectory()) {
                    testFiles.add(file.canonicalPath.substring(baseLength))
                }
            }
            proguardTask.outJarFiles.each { file ->
                def fileSystem = FileSystems.newFileSystem(file.toPath(), null)
                try {
                    testFiles.clone().each { testFile ->
                        if (Files.deleteIfExists(fileSystem.getPath(testFile))) {
                            testFiles.remove(testFile)
                        }
                    }
                } finally {
                    fileSystem.close()
                }
            }
        }
    }

    /**
     * Returns scala version from scala-library in given classpath.
     *
     * @param classpath the classpath contains scala-library
     * @return scala version
     */
    static String scalaVersionFromClasspath(String classpath) {
        def urls = new LinkedList<URL>()
        for (String path : classpath.split(File.pathSeparator)) {
            urls.add(new File(path).toURI().toURL())
        }
        def classLoader = new URLClassLoader(urls.toArray(new URL[0]))
        try {
            def propertiesClass
            try {
                propertiesClass = classLoader.loadClass("scala.util.Properties\$")
            } catch (ClassNotFoundException e) {
                return null
            }
            def versionNumber = propertiesClass.MODULE$.scalaProps["maven.version.number"]
            return versionNumber
        } finally {
            classLoader.close()
        }
    }

    /**
     * Updates AndroidPlugin's root extension to work with AndroidScalaPlugin.
     */
    void updateAndroidExtension() {
        androidExtension.metaClass.getScala = { extension }
        androidExtension.metaClass.scala = { configureClosure ->
            ConfigureUtil.configure(configureClosure, extension)
            androidExtension
        }
    }

    /**
     * Updates AndroidPlugin's sourceSets extension to work with AndroidScalaPlugin.
     */
    void updateAndroidSourceSetsExtension() {
        androidExtension.sourceSets.each { sourceSet ->
            sourceSet.convention.plugins.scala = new AndroidScalaSourceSet(sourceSet.displayName, fileResolver)
            def scala = sourceSet.scala
            def defaultSrcDir = ["src", sourceSet.name, "scala"].join(File.separator)
            def include = "**/*.scala"
            scala.srcDir(defaultSrcDir)
            scala.getFilter().include(include);
            sourceSet.allJava.source(scala)
            sourceSet.allSource.source(scala)
            sourceSet.java.srcDir(defaultSrcDir) // for Android Studio
            sourceDirectorySetMap[sourceSet.name] = scala

            // TODO: more elegant way
            sourceSet.java.getFilter().include(include);
            sourceSet.allSource.getFilter().include(include);
        }
    }

    /**
     * Updates AndroidPlugin's compilation task to support scala.
     *
     * @param task the JavaCompile task
     */
    void updateAndroidJavaCompileTask(Task task) {
        def scalaVersion = scalaVersionFromClasspath(task.classpath.asPath)
        if (!scalaVersion) {
            return
        }
        project.logger.info("scala-library version=$scalaVersion detected")
        def options = [target: extension.target]
        def configurationName = "androidScalaPluginScalaCompilerFor" + task.name
        def configuration = project.configurations.findByName(configurationName)
        if (!configuration) {
            configuration = project.configurations.create(configurationName)
            project.dependencies.add(configurationName, "org.scala-lang:scala-compiler:$scalaVersion")
        }
        task.javaCompiler = new AndroidScalaJavaJointCompiler(project, task.javaCompiler, options, configuration.asPath)
    }

    /**
     * Returns the proguard configuration text.
     *
     * @return the proguard configuration text
     */
    String getDefaultProGuardConfig() {
        '''
        -ignorewarnings

        # execute shrinking only
        -dontoptimize
        -dontobfuscate
        -dontpreverify
        #-dontshrink

        # standard libraries
        -dontwarn android.**, java.**, javax.microedition.khronos.**, junit.framework.**, scala.**, **.R$*
        -dontnote android.**, java.**, javax.microedition.khronos.**, junit.framework.**, scala.**, **.R$*

        # test libraries
        -dontwarn com.robotium.solo.**, org.mockito.**, junitx.**, com.google.android.apps.common.testing.**
        -dontnote com.robotium.solo.**, org.mockito.**, junitx.**, com.google.android.apps.common.testing.**
        -keep class com.google.dexmaker.mockito.** { *; }

        # for android. see also http://proguard.sourceforge.net/manual/examples.html#androidapplication
        -keep class * extends android.** { *; }
        -keep class * extends junit.** { *; }
        -keep class * implements android.** { *; }
        -keepclasseswithmembers class * {
            public <init>(android.content.Context, android.util.AttributeSet);
        }
        -keepclasseswithmembers class * {
            public <init>(android.content.Context, android.util.AttributeSet, int);
        }
        -keepclassmembers class * {
            @android.webkit.JavascriptInterface <methods>;
        }
        -keep public class com.google.vending.licensing.ILicensingService
        -keep public class com.android.vending.licensing.ILicensingService
        -dontnote com.google.vending.licensing.ILicensingService
        -dontnote com.android.vending.licensing.ILicensingService
        -keepclassmembers enum * {
            public static **[] values();
            public static ** valueOf(java.lang.String);
        }

        # for scala. see also http://proguard.sourceforge.net/manual/examples.html#scala
        -keep class scala.collection.SeqLike { public protected *; } # https://issues.scala-lang.org/browse/SI-5397
        -keep class scala.reflect.ScalaSignature { *; }
        -keep class scala.reflect.ScalaLongSignature { *; }
        -keep class scala.Predef$** { *; }
        -keepclassmembers class * { ** MODULE$; }
        -keep class * implements org.xml.sax.EntityResolver
        -keepclassmembernames class scala.concurrent.forkjoin.ForkJoinPool {
            long eventCount;
            int  workerCounts;
            int  runControl;
            scala.concurrent.forkjoin.ForkJoinPool$WaitQueueNode syncStack;
            scala.concurrent.forkjoin.ForkJoinPool$WaitQueueNode spareStack;
        }
        -keepclassmembernames class scala.concurrent.forkjoin.ForkJoinWorkerThread {
            int base;
            int sp;
            int runState;
        }
        -keepclassmembernames class scala.concurrent.forkjoin.ForkJoinTask {
            int status;
        }
        -keepclassmembernames class scala.concurrent.forkjoin.LinkedTransferQueue {
            scala.concurrent.forkjoin.LinkedTransferQueue$PaddedAtomicReference head;
            scala.concurrent.forkjoin.LinkedTransferQueue$PaddedAtomicReference tail;
            scala.concurrent.forkjoin.LinkedTransferQueue$PaddedAtomicReference cleanMe;
        }

        # miscellaneous. see also http://proguard.sourceforge.net/index.html#manual
        -keepattributes *Annotation*
        -keepclasseswithmembernames class * { native <methods>; }
        '''
    }

    /**
     * Executes additional proguard before task.Dex
     *
     * @param testVariant the TestVariant
     */
    void updateTestVariantDexTask(final Object testVariant) {
        final def task = testVariant.dex
        task.doFirst {
            project.logger.info("Dex task for TestVariant detected")
            def variantWorkDir = new File([workDir, "variant", testVariant.name].join(File.separator))
            FileUtils.forceMkdir(variantWorkDir)
            def inputs = task.inputFiles + task.libraries
            def outputFile = new File(variantWorkDir, "proguarded-classes.jar")
            if (outputFile.exists()) {
                FileUtils.forceDelete(outputFile)
            }
            def ant = new AntBuilder()
            ant.taskdef(name: 'proguard', classname: 'proguard.ant.ProGuardTask', // TODO: use properties
                    classpath: project.configurations.androidScalaPluginProGuard.asPath)
            def proguardFile = extension.androidTestProguardFile
            if (!proguardFile) {
                proguardFile = new File(variantWorkDir, "proguard-config.txt")
                proguardFile.withWriter {
                    it.write """
                        ${defaultProGuardConfig}
                        -keep class ${testVariant.packageName}.** { *; }
                        -keep class ${testVariant.testedVariant.packageName}.** { *; }
                    """
                }
            }
            ant.proguard(configuration: proguardFile) {
                inputs.each {
                    injar(file: it)
                }
                (testVariant.javaCompile.classpath.collect { it.canonicalPath } - task.libraries.collect {
                    it.canonicalPath
                }).each {
                    libraryJar(file: new File(it))
                }
                def options = testVariant.javaCompile.options.optionMap()
                def bootClasspath = options["bootClasspath"] ?: options["bootclasspath"]
                bootClasspath?.split(File.pathSeparator)?.each {
                    libraryJar(file: new File(it))
                }
                outjar(file: outputFile)
            }
            task.inputFiles = [outputFile]
            task.libraries = []
        }
    }
}
