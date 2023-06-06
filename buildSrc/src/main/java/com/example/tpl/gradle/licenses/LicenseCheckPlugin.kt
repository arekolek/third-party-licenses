/*
 * Copyright (c) 2023 FARA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.tpl.gradle.licenses

import com.example.tpl.gradle.licenses.google.UpdateGoogleLicencesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.diagnostics.DependencyReportTask

class LicenseCheckPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.registerReportDependencies(variant = "release")
        project.registerUpdateGoogleLicenses(variant = "release")
    }

    private fun Project.registerReportDependencies(variant: String) {
        tasks.register("reportDependencies", DependencyReportTask::class.java) {
            it.outputFile = file("$buildDir/licensecheck/$variant/dependencies.txt")
            it.setConfiguration("${variant}RuntimeClasspath")
        }
    }

    private fun Project.registerUpdateGoogleLicenses(variant: String) {
        tasks.register("updateGoogleLicenses", UpdateGoogleLicencesTask::class.java) {
            it.dependenciesFile = file("$buildDir/licensecheck/$variant/dependencies.txt")
        }
        tasks.getByName("updateGoogleLicenses").dependsOn("reportDependencies")
    }
}
