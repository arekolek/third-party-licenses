/**
 * Copyright 2018 Google LLC
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

package com.example.tpl.gradle.licenses.google

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.internal.component.AmbiguousVariantSelectionException
import org.slf4j.LoggerFactory
import java.io.File

object GoogleDependencies {
    private const val LOCAL_LIBRARY_VERSION = "unspecified"
    private const val TEST_PREFIX = "test"
    private const val ANDROID_TEST_PREFIX = "androidTest"

    private val PACKAGED_DEPENDENCIES_PREFIXES = setOf("compile", "implementation", "api")
    private val TEST_COMPILE = setOf("testCompile", "androidTestCompile")

    private val logger = LoggerFactory.getLogger(GoogleDependencies::class.java)

    /**
     * Returns the library file of a resolved dependency matching artifactInfo or null. Eagerly returns the first matching instance in the
     * first matching configuration. Skips unresolvable, unresolved, test, and non-package dependency configurations.
     *
     * Will not cause an unresolved configuration to be resolved.
     */
    fun getLibraryFile(project: Project, artifactInfo: ArtifactInfo): File? {
        val file = project
                .configurations
                .filterNot(::shouldSkipConfiguration)
                .filterNot(::isNotResolved)
                .firstNotNullOfOrNull {
                    val resolvedDependencies = it.resolvedConfiguration.lenientConfiguration.allModuleDependencies
                    findLibraryFileInResolvedDependencies(resolvedDependencies, artifactInfo, emptySet())
                }
        if (file == null) logger.warn("No resolved configurations contained $artifactInfo")
        return file
    }

    private fun findLibraryFileInResolvedDependencies(
            resolvedDependencies: Set<ResolvedDependency>,
            artifactInfo: ArtifactInfo,
            visitedDependencies: Set<ResolvedDependency>,
    ): File? {
        return resolvedDependencies.firstNotNullOfOrNull { resolvedDependency ->
            try {
                if (resolvedDependency.moduleVersion == LOCAL_LIBRARY_VERSION) {
                    // Attempting to getAllModuleArtifacts on a local library project will result in AmbiguousVariantSelectionException as
                    // there are not enough criteria to match a specific variant of the library project. Instead we skip the the library
                    // project itself and enumerate its dependencies.
                    if (resolvedDependency in visitedDependencies) {
                        logger.info("Dependency cycle at ${resolvedDependency.name}")
                        null
                    } else {
                        findLibraryFileInResolvedDependencies(
                                resolvedDependencies = resolvedDependency.children,
                                artifactInfo = artifactInfo,
                                visitedDependencies = visitedDependencies + resolvedDependency,
                        )
                    }
                } else {
                    resolvedDependency.allModuleArtifacts
                            .find { isMatchingArtifact(it, artifactInfo) }
                            ?.file
                }
            } catch (exception: AmbiguousVariantSelectionException) {
                logger.info("Failed to process ${resolvedDependency.name}", exception)
                null
            }
        }
    }

    /**
     * Returns true for configurations that cannot be resolved in the newer version of gradle API, are tests, or are not packaged
     * dependencies.
     */
    private fun shouldSkipConfiguration(configuration: Configuration): Boolean {
        return !configuration.isCanBeResolved || isTest(configuration) || !isPackagedDependency(configuration)
    }

    /**
     * Checks if the configuration is from test.
     *
     * @return true if configuration is a test configuration or its parent configurations are either testCompile or androidTestCompile,
     * otherwise false.
     */
    private fun isTest(configuration: Configuration): Boolean {
        return configuration.name.startsWith(TEST_PREFIX) ||
                configuration.name.startsWith(ANDROID_TEST_PREFIX) ||
                configuration.hierarchy.any { it.name in TEST_COMPILE }
    }

    /**
     * Checks if the configuration is for a packaged dependency (rather than e.g. a build or test time dependency)
     *
     * @return true if the configuration is in the set of @link #BINARY_DEPENDENCIES
     */
    private fun isPackagedDependency(configuration: Configuration): Boolean {
        return PACKAGED_DEPENDENCIES_PREFIXES.any { configuration.name.startsWith(it) } ||
                configuration.hierarchy.any { configurationHierarchy ->
                    PACKAGED_DEPENDENCIES_PREFIXES.any { configurationHierarchy.name.startsWith(it) }
                }
    }

    private fun isNotResolved(configuration: Configuration): Boolean {
        return configuration.state != Configuration.State.RESOLVED
    }

    private fun isMatchingArtifact(resolvedArtifact: ResolvedArtifact, artifactInfo: ArtifactInfo): Boolean {
        return resolvedArtifact.toArtifactInfo() == artifactInfo
    }
}
