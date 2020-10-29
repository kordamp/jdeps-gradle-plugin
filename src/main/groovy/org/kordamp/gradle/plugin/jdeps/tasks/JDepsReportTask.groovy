/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2017-2020 Andres Almiray.
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

import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.zeroturnaround.exec.ProcessExecutor

/**
 * @author Andres Almiray
 * @author Mark Paluch
 */
class JDepsReportTask extends DefaultTask {
    @Input boolean verbose = false
    @Input boolean summary = false
    @Input boolean profile = false
    @Input boolean recursive = false
    @Input boolean jdkinternals = true
    @Input boolean consoleOutput = true
    @Input boolean apionly = false
    @Input @Optional List<String> configurations = ['runtime']
    @Input @Optional List<String> classpaths = ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath']
    @Input @Optional List<String> sourceSets = ['main']
    @Input @Optional Integer multiRelease
    @Input @Optional Map<String, Integer> multiReleaseJars = [:]

    private Object reportDir

    JDepsReportTask() {
        extensions.create('moduleOptions', ModuleOptions)
    }

    @TaskAction
    void evaluate() {
        ModuleOptions moduleOptions = extensions.getByType(ModuleOptions)
        JavaCompile compileJava = project.tasks.findByName(JavaPlugin.COMPILE_JAVA_TASK_NAME)
        String classpath = compileJava.classpath.asPath
        List<String> compilerArgs = compileJava.options.compilerArgs
        List<String> commandOutput = []

        final List<String> baseCmd = ['jdeps']
        if (summary) baseCmd << '-s'
        if (verbose) baseCmd << '-v'
        if (profile) baseCmd << '-P'
        if (recursive) baseCmd << '-R'
        if (jdkinternals) baseCmd << '-jdkinternals'
        if (apionly) baseCmd << '-apionly'

        if (JavaVersion.current().java9Compatible) {
            if (multiRelease) {
                baseCmd << '--multi-release'
                baseCmd << multiRelease.toString()
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

        compileJava.classpath = project.files()

        project.logger.info("jdeps version is ${executeCommand(['jdeps', '-version'])}")

        sourceSets.each { sc ->
            SourceSet sourceSet = project.sourceSets[sc]
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
            }
        }

        for (String c : configurations) {
            inspectConfiguration(project.configurations[c], baseCmd, commandOutput)
        }

        for (String c : classpaths) {
            inspectConfiguration(project.configurations[c], baseCmd, commandOutput)
        }

        if (commandOutput) {
            commandOutput = commandOutput.unique()
            if (consoleOutput) println commandOutput.join('\n')

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
                Integer multiReleaseVersion = JDepsReportTask.resolveMultiReleaseVersion(file.name, multiReleaseJars)
                if (multiReleaseVersion) {
                    command.add(1, multiReleaseVersion.toString())
                    command.add(1, '--multi-release')
                }
            }

            project.logger.info("jdeps command set to: ${command.join(' ')} ${file.absolutePath}")
            String output = JDepsReportTask.executeCommandOn(command, file.absolutePath)
            if (output) {
                commandOutput << "\nDependency: ${file.name}\n${output}".toString()
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

    private static Integer resolveMultiReleaseVersion(String artifactName, Map<String, Integer> multiReleaseJars) {
        for (Map.Entry<String, Integer> e : multiReleaseJars.entrySet()) {
            if (artifactName.matches(e.key)) {
                return e.value
            }
        }
        null
    }
}
