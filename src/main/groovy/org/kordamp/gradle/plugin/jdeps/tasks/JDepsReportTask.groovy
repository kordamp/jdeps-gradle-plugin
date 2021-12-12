/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2017-2021 Andres Almiray.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kordamp.gradle.plugin.jdeps.tasks

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.options.Option
import org.kordamp.gradle.property.BooleanState
import org.kordamp.gradle.property.IntegerState
import org.kordamp.gradle.property.ListState
import org.kordamp.gradle.property.MapState
import org.kordamp.gradle.property.SimpleBooleanState
import org.kordamp.gradle.property.SimpleIntegerState
import org.kordamp.gradle.property.SimpleListState
import org.kordamp.gradle.property.SimpleMapState
import org.kordamp.gradle.util.PluginUtils
import org.zeroturnaround.exec.ProcessExecutor

import java.util.regex.Pattern

/**
 * @author Andres Almiray
 * @author Mark Paluch
 */
@CompileStatic
class JDepsReportTask extends DefaultTask {
    private static final Pattern WARNING = Pattern.compile("^(?:Warning:.*)|(?:.+->\\s([a-zA-Z\\.]+)\\s+JDK internal API.*)")

    private final BooleanState verbose
    private final BooleanState summary
    private final BooleanState profile
    private final BooleanState recursive
    private final BooleanState jdkinternals
    private final BooleanState consoleOutput
    private final BooleanState apionly
    private final BooleanState failOnWarning
    private final ListState configurations
    private final ListState classpaths
    private final ListState sourceSets
    private final IntegerState multiRelease
    private final MapState multiReleaseJars

    private Object reportDir

    JDepsReportTask() {
        extensions.create('moduleOptions', ModuleOptions)

        verbose = SimpleBooleanState.of(this, 'jdeps.verbose', false)
        summary = SimpleBooleanState.of(this, 'jdeps.summary', false)
        profile = SimpleBooleanState.of(this, 'jdeps.profile', false)
        recursive = SimpleBooleanState.of(this, 'jdeps.recursive', false)
        jdkinternals = SimpleBooleanState.of(this, 'jdeps.jdkinternals', true)
        consoleOutput = SimpleBooleanState.of(this, 'jdeps.console.output', true)
        apionly = SimpleBooleanState.of(this, 'jdeps.apionly', false)
        failOnWarning = SimpleBooleanState.of(this, 'jdeps.fail.on.warning', false)

        configurations = SimpleListState.of(this, 'jdeps.configurations', [])
        classpaths = SimpleListState.of(this, 'jdeps.classpaths', ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'])
        sourceSets = SimpleListState.of(this, 'jdeps.sourcesets', ['main'])

        multiRelease = SimpleIntegerState.of(this, 'jdeps.multi.release', -1)

        multiReleaseJars = SimpleMapState.of(this, 'jdeps.multi.release.jars', [:])
    }

    @Option(option = 'jdeps-verbose', description = 'Print all class level dependences')
    void setVerbose(boolean value) { verbose.property.set(value) }

    @Option(option = 'jdeps-summary', description = 'Print dependency summary only')
    void setSummary(boolean value) { summary.property.set(value) }

    @Option(option = 'jdeps-profile', description = 'Show profile containing a package')
    void setProfile(boolean value) { profile.property.set(value) }

    @Option(option = 'jdeps-recursive', description = 'Recursively traverse all run-time dependences')
    void setRecursive(boolean value) { recursive.property.set(value) }

    @Option(option = 'jdeps-jdkinternals', description = 'Finds class-level dependences on JDK internal APIs')
    void setJdkinternals(boolean value) { jdkinternals.property.set(value) }

    @Option(option = 'jdeps-console-output', description = 'Print out report to console')
    void setConsoleOutput(boolean value) { consoleOutput.property.set(value) }

    @Option(option = 'jdeps-apionly', description = 'Restrict analysis to APIs')
    void setApionly(boolean value) { apionly.property.set(value) }

    @Option(option = 'jdeps-fail-on-warning', description = 'Fails the build if jdeps finds any warnings')
    void setFailOnWarning(boolean value) { failOnWarning.property.set(value) }

    @Option(option = 'jdeps-configurations', description = 'Configurations to be analyzed')
    void setConfigurations(String value) { configurations.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-classpaths', description = 'Classpaths to be analyzed')
    void setClasspaths(String value) { classpaths.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-sourcesets', description = 'SourceSets to be analyzed')
    void setSourceSets(String value) { sourceSets.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-multi-release', description = 'Set the multi-release level')
    void setMultiRelease(String value) { multiRelease.property.set(Integer.valueOf(value)) }

    @Internal
    Property<Boolean> getVerbose() { verbose.property }

    @Input
    Provider<Boolean> getResolvedVerbose() { verbose.provider }

    @Internal
    Property<Boolean> getSummary() { summary.property }

    @Input
    Provider<Boolean> getResolvedSummary() { summary.provider }

    @Internal
    Property<Boolean> getProfile() { profile.property }

    @Input
    Provider<Boolean> getResolvedProfile() { profile.provider }

    @Internal
    Property<Boolean> getRecursive() { recursive.property }

    @Input
    Provider<Boolean> getResolvedRecursive() { recursive.provider }

    @Internal
    Property<Boolean> getJdkinternals() { jdkinternals.property }

    @Input
    Provider<Boolean> getResolvedJdkinternals() { jdkinternals.provider }

    @Internal
    Property<Boolean> getConsoleOutput() { consoleOutput.property }

    @Input
    Provider<Boolean> getResolvedConsoleOutput() { consoleOutput.provider }

    @Internal
    Property<Boolean> getApionly() { apionly.property }

    @Input
    Provider<Boolean> getResolvedApionly() { apionly.provider }

    @Internal
    Property<Boolean> getFailOnWarning() { failOnWarning.property }

    @Input
    Provider<Boolean> getResolvedFailOnWarning() { failOnWarning.provider }

    @Internal
    ListProperty<String> getConfigurations() { configurations.property }

    @Input
    @Optional
    Provider<List<String>> getResolvedConfigurations() { configurations.provider }

    @Internal
    ListProperty<String> getClasspaths() { classpaths.property }

    @Input
    @Optional
    Provider<List<String>> getResolvedClasspaths() { classpaths.provider }

    @Internal
    ListProperty<String> getSourceSets() { sourceSets.property }

    @Input
    @Optional
    Provider<List<String>> getResolvedSourceSets() { sourceSets.provider }

    @Internal
    Property<Integer> getMultiRelease() { multiRelease.property }

    @Input
    Provider<Integer> getResolvedMultiRelease() { multiRelease.provider }

    @Internal
    MapProperty<String, String> getMultiReleaseJars() { multiReleaseJars.property }

    @Input
    @Optional
    Provider<Map<String, String>> getResolvedMultiReleaseJars() { multiReleaseJars.provider }

    @TaskAction
    void evaluate() {
        ModuleOptions moduleOptions = extensions.getByType(ModuleOptions)
        TaskProvider<JavaCompile> compileJava = project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile)
        String classpath = compileJava.get().classpath.asPath
        List<String> compilerArgs = compileJava.get().options.compilerArgs
        List<String> commandOutput = []

        final List<String> baseCmd = ['jdeps']
        if (resolvedSummary.get()) baseCmd << '-s'
        if (resolvedVerbose.get()) baseCmd << '-v'
        if (resolvedProfile.get()) baseCmd << '-P'
        if (resolvedRecursive.get()) baseCmd << '-R'
        if (resolvedJdkinternals.get()) baseCmd << '-jdkinternals'
        if (resolvedApionly.get()) baseCmd << '-apionly'

        if (JavaVersion.current().java9Compatible) {
            if (resolvedMultiRelease.get() > -1) {
                baseCmd << '--multi-release'
                baseCmd << resolvedMultiRelease.get().toString()
            }

            if (classpath) {
                baseCmd << '--module-path'
                baseCmd << classpath
            } else {
                int modulePathIndex = compilerArgs.indexOf('--module-path')
                if (modulePathIndex > -1) {
                    baseCmd << '--module-path'
                    baseCmd << compilerArgs[modulePathIndex + 1]
                }
            }

            if (!moduleOptions.addModules.empty) {
                baseCmd << '--add-modules'
                baseCmd << moduleOptions.addModules.join(',')
            } else {
                int addModulesIndex = compilerArgs.indexOf('--add-modules')
                if (addModulesIndex > -1) {
                    baseCmd << '--add-modules'
                    baseCmd << compilerArgs[addModulesIndex + 1]
                }
            }
        }

        compileJava.get().classpath = project.files()

        project.logger.info("jdeps version is ${executeCommand(['jdeps', '-version'])}")

        JavaPluginConvention convention = project.convention.getPlugin(JavaPluginConvention)
        resolvedSourceSets.get().each { sc ->
            SourceSet sourceSet = convention.sourceSets.findByName(sc)
            project.logger.info("Running jdeps on sourceSet ${sourceSet.name}")
            sourceSet.output.files.each { File file ->
                if (!file.exists()) {
                    return // skip
                }

                project.logger.info("jdeps command set to ${baseCmd.join(' ')}")
                String output = JDepsReportTask.executeCommandOn(baseCmd, file.absolutePath)
                if (output) {
                    commandOutput << "\nProject: ${project.name}\n${output}".toString()
                }

                List<String> warnings = getWarnings(output)
                if (warnings && failOnWarning.getOrElse(false)) {
                    throw new IllegalStateException("jdeps reported errors/warnings: " +
                        System.lineSeparator() +
                        warnings.join(System.lineSeparator()))
                }
            }
        }

        List<String> confs = new ArrayList<>(resolvedConfigurations.get())
        if (!confs) {
            if (PluginUtils.isGradle7Compatible()) {
                confs.add('runtimeClasspath')
            } else {
                confs.add('runtime')
            }
        }

        for (String c : confs) {
            inspectConfiguration(project.configurations[c.trim()], baseCmd, commandOutput)
        }

        for (String c : resolvedClasspaths.get()) {
            inspectConfiguration(project.configurations[c.trim()], baseCmd, commandOutput)
        }

        if (commandOutput) {
            commandOutput = commandOutput.unique()
            if (resolvedConsoleOutput.get()) println commandOutput.join('\n')

            File parentFile = getReportsDir()
            if (!parentFile.exists()) parentFile.mkdirs()
            File logFile = new File(parentFile, 'jdeps-report.txt')
            logFile.append(commandOutput)
        }
    }

    @OutputDirectory
    File getReportsDir() {
        if (this.reportDir == null) {
            File reportsDir = new File(project.buildDir, 'reports')
            this.reportDir = new File(reportsDir, 'jdeps')
        }
        project.file(this.reportDir)
    }

    void setReportsDir(File f) {
        this.reportDir = f
    }

    private void inspectConfiguration(Configuration configuration, List<String> baseCmd, List<String> commandOutput) {
        project.logger.info("Running jdeps on configuration ${configuration.name}")
        configuration.resolve().each { File file ->
            if (!file.exists()) {
                return // skip
            }

            List<String> command = new ArrayList<>(baseCmd)
            if (JavaVersion.current().java9Compatible) {
                String multiReleaseVersion = JDepsReportTask.resolveMultiReleaseVersion(file.name, resolvedMultiReleaseJars.get())
                if (multiReleaseVersion) {
                    command.add(1, multiReleaseVersion)
                    command.add(1, '--multi-release')
                }
            }

            project.logger.info("jdeps command set to: ${command.join(' ')} ${file.absolutePath}")
            String output = JDepsReportTask.executeCommandOn(command, file.absolutePath)
            if (output) {
                commandOutput << "\nDependency: ${file.name}\n${output}".toString()
            }

            List<String> warnings = getWarnings(output)
            if (warnings && failOnWarning.getOrElse(false)) {
                throw new IllegalStateException("jdeps reported errors/warnings: " +
                    System.lineSeparator() +
                    warnings.join(System.lineSeparator()))
            }
        }
    }

    private static String executeCommandOn(List<String> baseCmd, String path) {
        List<String> cmd = []
        cmd.addAll(baseCmd)
        cmd.add(path)

        return executeCommand(cmd)
    }

    private static String executeCommand(List<String> cmd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        new ProcessExecutor(cmd).redirectOutput(out).execute().getExitValue()
        return out.toString().trim()
    }

    private static String resolveMultiReleaseVersion(String artifactName, Map<String, String> multiReleaseJars) {
        for (Map.Entry<String, String> e : multiReleaseJars.entrySet()) {
            if (artifactName.matches(e.key.trim())) {
                return e.value.trim()
            }
        }
        null
    }

    private static List<String> getWarnings(String output) {
        List<String> warnings = []
        output.eachLine { String line ->
            if (WARNING.matcher(line).matches()) {
                warnings.add(line)
            }
        }
        warnings
    }
}
