/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2017-2022 Andres Almiray.
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
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
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
import org.kordamp.gradle.property.ListState
import org.kordamp.gradle.property.MapState
import org.kordamp.gradle.property.RegularFileState
import org.kordamp.gradle.property.SimpleBooleanState
import org.kordamp.gradle.property.SimpleListState
import org.kordamp.gradle.property.SimpleMapState
import org.kordamp.gradle.property.SimpleRegularFileState
import org.kordamp.gradle.property.SimpleStringState
import org.kordamp.gradle.property.StringState
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
    private final BooleanState missingDeps
    private final BooleanState ignoreMissingDeps
    private final ListState pkgs
    private final ListState requires
    private final StringState include
    private final StringState regex
    private final StringState filter
    private final ListState configurations
    private final ListState classpaths
    private final ListState sourceSets
    private final StringState multiRelease
    private final MapState multiReleaseJars
    private final RegularFileState dotOutput

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
        missingDeps = SimpleBooleanState.of(this, 'jdeps.missing.deps', false)
        ignoreMissingDeps = SimpleBooleanState.of(this, 'jdeps.ignore.missing.deps', false)
        include = SimpleStringState.of(this, 'jdeps.include', "")
        regex = SimpleStringState.of(this, 'jdeps.regex', "")
        filter = SimpleStringState.of(this, 'jdeps.filter', "")
        pkgs = SimpleListState.of(this, 'jdeps.package', [])
        requires = SimpleListState.of(this, 'jdeps.require', [])

        configurations = SimpleListState.of(this, 'jdeps.configurations', [])
        classpaths = SimpleListState.of(this, 'jdeps.classpaths', ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath'])
        sourceSets = SimpleListState.of(this, 'jdeps.sourcesets', ['main'])

        multiRelease = SimpleStringState.of(this, 'jdeps.multi.release', 'base')

        multiReleaseJars = SimpleMapState.of(this, 'jdeps.multi.release.jars', [:])
        dotOutput = SimpleRegularFileState.of(this, 'jdeps.dot.output', (RegularFile) null)
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

    @Option(option = 'jdeps-missing-deps', description = 'Finds missing dependences')
    void setMissingDeps(boolean value) { missingDeps.property.set(value) }

    @Option(option = 'jdeps-ignore-missing-deps', description = 'Ignore missing dependences')
    void setIgnoreMissingDeps(boolean value) { ignoreMissingDeps.property.set(value) }

    @Option(option = 'jdeps-include', description = 'Restrict analysis to classes matching pattern')
    void setInclude(String value) { include.property.set(value) }

    @Option(option = 'jdeps-regex', description = 'Finds dependences matching the given pattern')
    void setRegex(String value) { regex.property.set(value) }

    @Option(option = 'jdeps-filter', description = 'Filter dependences matching the given pattern')
    void setFilter(String value) { filter.property.set(value) }

    @Option(option = 'jdeps-package', description = 'Finds dependences matching the given package name. REPEATABLE')
    void setPackage(String value) { pkgs.property.add(value) }

    @Option(option = 'jdeps-require', description = 'Finds dependences matching the given module name. REPEATABLE')
    void setRequire(String value) { requires.property.add(value) }

    @Option(option = 'jdeps-configurations', description = 'Configurations to be analyzed')
    void setConfigurations(String value) { configurations.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-classpaths', description = 'Classpaths to be analyzed')
    void setClasspaths(String value) { classpaths.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-sourcesets', description = 'SourceSets to be analyzed')
    void setSourceSets(String value) { sourceSets.property.set(value.split(',').toList()) }

    @Option(option = 'jdeps-multi-release', description = 'Set the multi-release level')
    void setMultiRelease(String value) { multiRelease.property.set(value) }

    @Option(option = 'jdeps-dot-output', description = 'Destination directory for DOT file output')
    void setDotOutput(String value) { dotOutput.property.set(project.file(value)) }

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
    Property<Boolean> getMissingDeps() { missingDeps.property }

    @Input
    Provider<Boolean> getResolvedMissingDeps() { missingDeps.provider }

    @Internal
    Property<Boolean> getIgnoreMissingDeps() { ignoreMissingDeps.property }

    @Input
    Provider<Boolean> getResolvedIgnoreMissingDeps() { ignoreMissingDeps.provider }

    @Internal
    Property<String> getInclude() { include.property }

    @Input
    @Optional
    Provider<String> getResolvedInclude() { include.provider }

    @Internal
    Property<String> getRegex() { regex.property }

    @Input
    @Optional
    Provider<String> getResolvedRegex() { regex.provider }

    @Internal
    Property<String> getFilter() { filter.property }

    @Input
    @Optional
    Provider<String> getResolvedFilter() { filter.provider }

    @Internal
    ListProperty<String> getPackages() { pkgs.property }

    @Input
    @Optional
    Provider<List<String>> getResolvedPackages() { pkgs.provider }

    @Internal
    ListProperty<String> getRequires() { requires.property }

    @Input
    @Optional
    Provider<List<String>> getResolvedRequires() { requires.provider }

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
    Property<String> getMultiRelease() { multiRelease.property }

    @Input
    Provider<String> getResolvedMultiRelease() { multiRelease.provider }

    @Internal
    MapProperty<String, String> getMultiReleaseJars() { multiReleaseJars.property }

    @Input
    @Optional
    Provider<Map<String, String>> getResolvedMultiReleaseJars() { multiReleaseJars.provider }

    @Internal
    RegularFileProperty getDotOutput() { dotOutput.property }

    @Input
    @Optional
    Provider<RegularFile> getResolvedDotOutput() { dotOutput.provider }

    @TaskAction
    void evaluate() {
        ModuleOptions moduleOptions = extensions.getByType(ModuleOptions)
        TaskProvider<JavaCompile> compileJava = project.tasks.named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile)
        String classpath = compileJava.get().classpath.asPath
        List<String> compilerArgs = compileJava.get().options.compilerArgs
        List<String> commandOutput = []

        final List<String> baseCmd = ['jdeps']
        if (resolvedSummary.get()) {
            if (resolvedMissingDeps.get()) {
                throw new IllegalArgumentException("-s, --missing-deps are mutually exclusive")
            }
            baseCmd << '-s'
        }
        if (resolvedVerbose.get()) baseCmd << '-v'
        if (resolvedProfile.get()) baseCmd << '-P'
        if (resolvedRecursive.get()) baseCmd << '-R'
        if (resolvedJdkinternals.get()) baseCmd << '-jdkinternals'
        if (resolvedApionly.get()) baseCmd << '-apionly'
        if (getResolvedDotOutput().present) {
            baseCmd << '-dotoutput'
            baseCmd << getResolvedDotOutput().get().asFile.absolutePath
        }

        if (JavaVersion.current().java9Compatible) {
            if (resolvedMultiRelease.present) {
                baseCmd << '--multi-release'
                baseCmd << resolvedMultiRelease.get()
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

            List<String> requires = resolvedRequires.get()
            List<String> packages = resolvedPackages.get()
            String regex = resolvedRegex.orNull
            int exclusive = 0
            if (!requires.isEmpty()) exclusive++
            if (!packages.isEmpty()) exclusive++
            if (regex) exclusive++
            if (exclusive > 1) {
                throw new IllegalArgumentException("--package, --regex, --require are mutually exclusive")
            }

            if (resolvedMissingDeps.get()) {
                exclusive = 1
                if (!packages.isEmpty()) exclusive++
                if (regex) exclusive++
                if (exclusive > 1) {
                    throw new IllegalArgumentException("--package, --regex, --missing-deps are mutually exclusive")
                }
            }

            requires.each { s ->
                baseCmd << '--require'
                baseCmd << s
            }

            packages.each { s ->
                baseCmd << '--package'
                baseCmd << s
            }

            if (regex) {
                baseCmd << '--regex'
                baseCmd << regex
            }

            if (resolvedFilter.present) {
                String filter = resolvedFilter.get()
                if (filter in [':package', ':archive', ':module', ':none']) {
                    baseCmd << '-filter' + filter
                } else {
                    baseCmd << '-filter'
                    baseCmd << filter
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
                if (warnings && getResolvedFailOnWarning().get()) {
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
            if (warnings && getResolvedFailOnWarning().get()) {
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
