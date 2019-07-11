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

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaBasePlugin

/**
 * @author Andres Almiray
 */
@CompileStatic
class JDepsPlugin implements Plugin<Project> {
    Project project

    void apply(Project project) {
        this.project = project

        project.plugins.apply(JavaBasePlugin)

        project.tasks.findByName('check').dependsOn << project.tasks.register('jdepsReport', JDepsReportTask,
            new Action<JDepsReportTask>() {
                @Override
                void execute(JDepsReportTask t) {
                    t.dependsOn('classes')
                    t.group = BasePlugin.BUILD_GROUP
                    t.description = 'Generate a jdeps report on project classes and dependencies'
                }
            })
    }
}