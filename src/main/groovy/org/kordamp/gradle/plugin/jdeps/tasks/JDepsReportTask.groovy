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
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.options.Option
import org.kordamp.gradle.property.BooleanState
import org.kordamp.gradle.property.DirectoryState
import org.kordamp.gradle.property.ListState
import org.kordamp.gradle.property.MapState
import org.kordamp.gradle.property.SimpleBooleanState
import org.kordamp.gradle.property.SimpleDirectoryState
import org.kordamp.gradle.property.SimpleListState
import org.kordamp.gradle.property.SimpleMapState
import org.kordamp.gradle.property.SimpleStringState
import org.kordamp.gradle.property.StringState
import org.zeroturnaround.exec.ProcessExecutor

import javax.inject.Inject
import java.util.regex.Pattern

/**
 * @author Andres Almiray
 * @author Mark Paluch
 */
@CompileStatic
class JDepsReportTask extends DefaultTask {
    private static final Pattern WARNING = Pattern.compile("^(?:Warning:.*)|(?:.+->\\s([a-zA-Z\\.]+)\\s+JDK internal API.*)")
    private static final Pattern ERROR = Pattern.compile("^(?:Error:.*)|Exception in thread.*")

    private final BooleanState listDeps
    private final BooleanState listReducedDeps
    private final BooleanState printModuleDeps
    private final BooleanState verbose
    private final BooleanState modular
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
    private final DirectoryState dotOutput

    @OutputDirectory
    final DirectoryProperty reportDir

    @Internal
    TaskProvider<JavaCompile> compileJava

    @Internal
    final Property<JavaPluginConvention> javaPluginConvention

    @Internal
    final Property<String> projectName

    @Internal
    ConfigurationContainer projectConfigurations

    @Inject
    JDepsReportTask(ObjectFactory objects) {
        extensions.create('moduleOptions', ModuleOptions)

        reportDir = objects.directoryProperty()
        projectName = objects.property(String)
        javaPluginConvention = objects.property(JavaPluginConvention)

        listDeps = SimpleBooleanState.of(this, 'jdeps.list.deps', false)
        listReducedDeps = SimpleBooleanState.of(this, 'jdeps.list.reduced.deps', false)
        printModuleDeps = SimpleBooleanState.of(this, 'jdeps.print.module.deps', false)
        verbose = SimpleBooleanState.of(this, 'jdeps.verbose', false)
        modular = SimpleBooleanState.of(this, 'jdeps.modular', false)
        summary = SimpleBooleanState.of(this, 'jdeps.summary', false)
        profile = SimpleBooleanState.of(this, 'jdeps.profile', false)
        recursive = SimpleBooleanState.of(this, 'jdeps.recursive', false)
        jdkinternals = SimpleBooleanState.of(this, 'jdeps.jdkinternals', false)
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
        classpaths = SimpleListState.of(this, 'jdeps.classpaths', [])
        sourceSets = SimpleListState.of(this, 'jdeps.sourcesets', ['main'])

        multiRelease = SimpleStringState.of(this, 'jdeps.multi.release', 'base')

        multiReleaseJars = SimpleMapState.of(this, 'jdeps.multi.release.jars', [:])
        dotOutput = SimpleDirectoryState.of(this, 'jdeps.dot.output', (Directory) null)
    }

    @Option(option = 'list-deps', description = 'Lists the module dependences')
    void setListDeps(boolean value) { listDeps.property.set(value) }

    @Option(option = 'list-reduced-deps', description = 'Lists the module dependences')
    void setListReducedDeps(boolean value) { listReducedDeps.property.set(value) }

    @Option(option = 'print-module-deps', description = 'Comma-separated list of module dependences')
    void setPrintModuleDeps(boolean value) { printModuleDeps.property.set(value) }

    @Option(option = 'verbose', description = 'Print all class level dependences')
    void setVerbose(boolean value) { verbose.property.set(value) }

    @Option(option = 'modular', description = 'Uses the module path instead of the classpath')
    void setModular(boolean value) { modular.property.set(value) }

    @Option(option = 'summary', description = 'Print dependency summary only')
    void setSummary(boolean value) { summary.property.set(value) }

    @Option(option = 'profile', description = 'Show profile containing a package')
    void setProfile(boolean value) { profile.property.set(value) }

    @Option(option = 'recursive', description = 'Recursively traverse all run-time dependences')
    void setRecursive(boolean value) { recursive.property.set(value) }

    @Option(option = 'jdkinternals', description = 'Finds class-level dependences on JDK internal APIs')
    void setJdkinternals(boolean value) { jdkinternals.property.set(value) }

    @Option(option = 'console-output', description = 'Print out report to console')
    void setConsoleOutput(boolean value) { consoleOutput.property.set(value) }

    @Option(option = 'apionly', description = 'Restrict analysis to APIs')
    void setApionly(boolean value) { apionly.property.set(value) }

    @Option(option = 'fail-on-warning', description = 'Fails the build if jdeps finds any warnings')
    void setFailOnWarning(boolean value) { failOnWarning.property.set(value) }

    @Option(option = 'missing-deps', description = 'Finds missing dependences')
    void setMissingDeps(boolean value) { missingDeps.property.set(value) }

    @Option(option = 'ignore-missing-deps', description = 'Ignore missing dependences')
    void setIgnoreMissingDeps(boolean value) { ignoreMissingDeps.property.set(value) }

    @Option(option = 'include', description = 'Restrict analysis to classes matching pattern')
    void setInclude(String value) { include.property.set(value) }

    @Option(option = 'regex', description = 'Finds dependences matching the given pattern')
    void setRegex(String value) { regex.property.set(value) }

    @Option(option = 'filter', description = 'Filter dependences matching the given pattern')
    void setFilter(String value) { filter.property.set(value) }

    @Option(option = 'package', description = 'Finds dependences matching the given package name. REPEATABLE')
    void setPackage(String value) { pkgs.property.add(value) }

    @Option(option = 'require', description = 'Finds dependences matching the given module name. REPEATABLE')
    void setRequire(String value) { requires.property.add(value) }

    @Option(option = 'configurations', description = 'Configurations to be analyzed')
    void setConfigurations(String value) { configurations.property.set(value.split(',').toList()) }

    @Option(option = 'classpaths', description = 'Classpaths to be analyzed')
    void setClasspaths(String value) { classpaths.property.set(value.split(',').toList()) }

    @Option(option = 'sourcesets', description = 'SourceSets to be analyzed')
    void setSourceSets(String value) { sourceSets.property.set(value.split(',').toList()) }

    @Option(option = 'multi-release', description = 'Set the multi-release level')
    void setMultiRelease(String value) { multiRelease.property.set(value) }

    @Option(option = 'dot-output', description = 'Destination directory for DOT file output')
    void setDotOutput(String value) { dotOutput.property.set(new File(value)) }

    @Internal
    Property<Boolean> getListDeps() { listDeps.property }

    @Input
    Provider<Boolean> getResolvedListDeps() { listDeps.provider }

    @Internal
    Property<Boolean> getListReducedDeps() { listReducedDeps.property }

    @Input
    Provider<Boolean> getResolvedListReducedDeps() { listReducedDeps.provider }

    @Internal
    Property<Boolean> getPrintModuleDeps() { printModuleDeps.property }

    @Input
    Provider<Boolean> getResolvedPrintModuleDeps() { printModuleDeps.provider }

    @Internal
    Property<Boolean> getVerbose() { verbose.property }

    @Input
    Provider<Boolean> getResolvedVerbose() { verbose.provider }

    @Internal
    Property<Boolean> getModular() { modular.property }

    @Input
    Provider<Boolean> getResolvedModular() { modular.provider }

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
    DirectoryProperty getDotOutput() { dotOutput.property }

    @InputDirectory
    @Optional
    Provider<Directory> getResolvedDotOutput() { dotOutput.provider }

    @TaskAction
    void evaluate() {
        ModuleOptions moduleOptions = extensions.getByType(ModuleOptions)
        String classpath = compileJava.get().classpath.asPath
        List<String> compilerArgs = compileJava.get().options.compilerArgs
        List<String> commandOutput = []

        int explicitCommand = 0
        if (resolvedListDeps.get()) explicitCommand++
        if (resolvedListReducedDeps.get()) explicitCommand++
        if (resolvedPrintModuleDeps.get()) explicitCommand++
        if (explicitCommand > 1) {
            throw new IllegalArgumentException("--list-deps, --list-reduced-deps, --print-module-deps are mutually exclusive")
        }

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
            baseCmd << resolvedDotOutput.get().asFile.absolutePath
            resolvedDotOutput.get().asFile.mkdirs()
        }
        if (resolvedIgnoreMissingDeps.get()) baseCmd << '--ignore-missing-deps'

        if (JavaVersion.current().java9Compatible) {
            if (resolvedMultiRelease.present) {
                baseCmd << '--multi-release'
                baseCmd << resolvedMultiRelease.get()
            }

            if (resolvedModular.get()) {
                int modulePathIndex = compilerArgs.indexOf('--module-path')
                if (modulePathIndex > -1) {
                    baseCmd << '--module-path'
                    baseCmd << compilerArgs[modulePathIndex + 1]
                } else {
                    baseCmd << '--module-path'
                    baseCmd << classpath
                }
            } else if (classpath) {
                baseCmd << '--class-path'
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

            if (resolvedFilter.get()) {
                String filter = resolvedFilter.get()
                if (filter in [':package', ':archive', ':module', ':none']) {
                    baseCmd << '-filter' + filter
                } else {
                    baseCmd << '-filter'
                    baseCmd << filter
                }
            }
        }

        // compileJava.get().classpath = project.files()

        logger.info("jdeps version is ${executeCommand(['jdeps', '-version'])}")

        Map<String, String> outputs = [:]
        resolvedSourceSets.get().each { sc ->
            SourceSet sourceSet = javaPluginConvention.get().sourceSets.findByName(sc)
            logger.info("Running jdeps on sourceSet ${sourceSet.name}")
            sourceSet.output.files.each { File file ->
                if (!file.exists()) {
                    return // skip
                }

                List<String> cmd = applyExplicitCommand(baseCmd)
                logger.info("jdeps command set to ${cmd.join(' ')}")
                String output = JDepsReportTask.executeCommandOn(cmd, file.absolutePath)
                if (output) {
                    commandOutput << "\nProject: ${projectName.get()}\n${output}".toString()
                    outputs[sourceSet.name] = output
                }

                List<String> warnings = getWarnings(output)
                if (warnings && getResolvedFailOnWarning().get()) {
                    throw new IllegalStateException("jdeps reported warnings: " +
                        System.lineSeparator() +
                        warnings.join(System.lineSeparator()))
                }

                List<String> errors = getErrors(output)
                if (errors) {
                    throw new IllegalStateException("jdeps reported errors: " +
                        System.lineSeparator() +
                        errors.join(System.lineSeparator()))
                }
            }
        }

        for (String c : resolvedConfigurations.get()) {
            inspectConfiguration(projectConfigurations[c.trim()], baseCmd, commandOutput, outputs)
        }

        for (String c : resolvedClasspaths.get()) {
            inspectConfiguration(projectConfigurations[c.trim()], baseCmd, commandOutput, outputs)
        }

        if (commandOutput) {
            commandOutput = commandOutput.unique()
            if (resolvedConsoleOutput.get()) println commandOutput.join('\n')

            File parentFile = reportDir.get().asFile
            if (!parentFile.exists()) parentFile.mkdirs()
            File logFile = new File(parentFile, 'jdeps-report.txt')
            logFile.append(commandOutput.join('\n'))

            String prefix = 'jdeps-'
            if (resolvedListDeps.get()) {
                prefix = 'list-deps-'
            } else if (resolvedListReducedDeps.get()) {
                prefix = 'list-reduced-deps-'
            } else if (resolvedPrintModuleDeps.get()) {
                prefix = 'print-module-deps-'
            }
            outputs.each { k, v ->
                logFile = new File(parentFile, prefix + k + '.txt')
                logFile.append(v)
            }
        }
    }

    private void inspectConfiguration(Configuration configuration, List<String> baseCmd, List<String> commandOutput, Map<String, String> outputs) {
        logger.info("Running jdeps on configuration ${configuration.name}")
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

            command = applyExplicitCommand(command)
            logger.info("jdeps command set to: ${command.join(' ')} ${file.absolutePath}")
            String output = JDepsReportTask.executeCommandOn(command, file.absolutePath)
            if (output) {
                commandOutput << "\nDependency: ${file.name}\n${output}".toString()
                outputs[configuration.name] = output
            }

            List<String> warnings = getWarnings(output)
            if (warnings && getResolvedFailOnWarning().get()) {
                throw new IllegalStateException("jdeps reported errors/warnings: " +
                    System.lineSeparator() +
                    warnings.join(System.lineSeparator()))
            }
        }
    }

    private List<String> applyExplicitCommand(List<String> cmd) {
        List<String> c = new ArrayList<>(cmd)
        String subcommand = ''
        if (resolvedListDeps.get()) {
            subcommand = '--list-deps'
        } else if (resolvedListReducedDeps.get()) {
            subcommand = '--list-reduced-deps'
        } else if (resolvedPrintModuleDeps.get()) {
            subcommand = '--print-module-deps'
        }

        if (subcommand) {
            if (c.contains('--class-path')) {
                c.add(c.indexOf('--class-path'), subcommand)
            } else if (c.contains('--module-path')) {
                c.add(c.indexOf('--module-path'), subcommand)
            } else {
                c << subcommand
            }
        }

        c
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

    private static List<String> getErrors(String output) {
        List<String> errors = []
        output.eachLine { String line ->
            if (ERROR.matcher(line).matches()) {
                errors.add(line)
            }
        }
        errors
    }
}
