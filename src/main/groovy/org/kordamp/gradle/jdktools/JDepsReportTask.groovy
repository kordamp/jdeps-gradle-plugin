/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2017-2019 Andres Almiray.
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
package org.kordamp.gradle.jdktools

import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
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
    @Input @Optional List<String> configurations = ['runtime']
    @Input @Optional List<String> sourceSets = ['main']

    List<String> commandOutput = []
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

        final List<String> baseCmd = ['jdeps']
        if (summary) baseCmd << '-s'
        if (verbose) baseCmd << '-v'
        if (profile) baseCmd << '-profile'
        if (recursive) baseCmd << '-recursive'
        if (jdkinternals) baseCmd << '-jdkinternals'

        if (JavaVersion.current().java9Compatible) {
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

        if (!configurations) configurations = ['runtime']
        if (!sourceSets) sourceSets = ['main']

        sourceSets.each { sc ->
            project.sourceSets[sc].output.files.each { File file ->
                if (!file.exists()) {
                    return // skip
                }

                String output = JDepsReportTask.runJDepsOn(baseCmd, file.absolutePath)
                if (output) {
                    commandOutput << "\nProject: ${project.name}\n${output}".toString()
                }
            }
        }

        configurations.each { c ->
            project.configurations[c].resolve().each { File file ->
                if (!file.exists()) {
                    return // skip
                }

                String output = JDepsReportTask.runJDepsOn(baseCmd, file.absolutePath)
                if (output) {
                    commandOutput << "\nDependency: ${file.name}\n${output}".toString()
                }
            }
        }

        if (commandOutput) {
            if (consoleOutput) println commandOutput.join('\n')

            File parentFile = getReportsDir()
            if (!parentFile.exists()) parentFile.mkdirs()
            File logFile = new File(parentFile, 'jdeps-report.txt')

            logFile.withPrintWriter { w -> this.outputs.each { f -> w.println(f) } }
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

    private static String runJDepsOn(List<String> baseCmd, String path) {
        List<String> cmd = []
        cmd.addAll(baseCmd)
        cmd.add(path)

        ByteArrayOutputStream out = new ByteArrayOutputStream()
        new ProcessExecutor(cmd).redirectOutput(out).execute().getExitValue()
        return out.toString().trim()
    }
}
