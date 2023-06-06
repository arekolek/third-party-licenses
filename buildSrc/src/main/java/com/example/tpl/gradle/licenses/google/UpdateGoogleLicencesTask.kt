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

import com.example.tpl.gradle.licenses.LibraryModel
import com.example.tpl.gradle.licenses.LicenseModel
import com.example.tpl.gradle.licenses.parseDependencies
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

abstract class UpdateGoogleLicencesTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    lateinit var dependenciesFile: File

    @OutputDirectory
    var librariesDirectory: File = project.file("${project.rootDir}/config/aboutlibraries/libraries")

    @OutputDirectory
    var licensesDirectory: File = project.file("${project.rootDir}/config/aboutlibraries/licenses")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val extraLibraries = mutableMapOf<String, LibraryModel>()
    private val extraLicenses = mutableMapOf<String, LicenseModel>()

    @TaskAction
    fun updateLicenses() {
        val dependencies = dependenciesFile.parseDependencies()

        dependencies
                .map {
                    val (group, name, version) = it.split(":")
                    ArtifactInfo(group = group, name = name, version = version)
                }
                .forEach { artifactInfo ->
                    if (isGoogleServices(artifactInfo.group)) {
                        // Add transitive licenses info for google-play-services. For post-granular versions, this is located in the
                        // artifact itself, whereas for pre-granular versions, this information is located at the complementary license
                        // artifact as a runtime dependency.
                        if (isGranularVersion(artifactInfo.version) || artifactInfo.name.endsWith(LICENSE_ARTIFACT_SUFFIX)) {
                            addGooglePlayServiceLicenses(artifactInfo)
                        }
                    }
                }

        librariesDirectory
                .listFiles { _, name -> name.startsWith("lib_generated_") }
                ?.forEach(File::delete)

        extraLibraries.values.forEach { library ->
            val fileName = library.name.lowercase().replace(' ', '_')
            File(librariesDirectory, "lib_generated_$fileName.json")
                    .outputStream()
                    .bufferedWriter()
                    .use {
                        it.write(json.encodeToString(library))
                    }
        }

        licensesDirectory
                .listFiles { _, name -> name.startsWith("lic_generated_") }
                ?.forEach(File::delete)

        extraLicenses.values.forEach { license ->
            val fileName = license.hash
            File(licensesDirectory, "lic_generated_$fileName.json")
                    .outputStream()
                    .bufferedWriter()
                    .use {
                        it.write(json.encodeToString(license))
                    }
        }
    }

    private fun isGoogleServices(group: String): Boolean {
        return GOOGLE_PLAY_SERVICES_GROUP.equals(group, ignoreCase = true) ||
                FIREBASE_GROUP.equals(group, ignoreCase = true)
    }

    private fun isGranularVersion(version: String): Boolean {
        val majorVersion = version.split(".").firstOrNull()?.toInt() ?: return false
        return majorVersion >= GRANULAR_BASE_VERSION
    }

    private fun addGooglePlayServiceLicenses(artifactInfo: ArtifactInfo) {
        val artifactFile = GoogleDependencies.getLibraryFile(project, artifactInfo)
        if (artifactFile == null) {
            logger.warn("Unable to find Google Play Services Artifact for $artifactInfo")
            return
        }
        addGooglePlayServiceLicenses(artifactFile)
    }

    private fun addGooglePlayServiceLicenses(artifactFile: File) {
        val licensesZip = ZipFile(artifactFile)

        val jsonFile = licensesZip.getEntry("third_party_licenses.json") ?: return
        val txtFile = licensesZip.getEntry("third_party_licenses.txt") ?: return

        val licenses = licensesZip.getInputStream(txtFile)
                .bufferedReader()
                .use { it.readText() }

        val thirdPartyLicenses: ThirdPartyLicenses = json.decodeFromStream(licensesZip.getInputStream(jsonFile))

        thirdPartyLicenses.forEach { (name, metadata) ->
            val uniqueId = "$GENERATED_UNIQUE_ID_PREFIX${name.lowercase().replace(' ', '.')}"

            if (uniqueId !in extraLicenses) {
                val license = licenses.drop(metadata.start).take(metadata.length)
                val licenseHash = name.lowercase().replace(' ', '_')
                val licenseModel = LicenseModel(
                        name = "Google Play Services dependency license",
                        content = license,
                        hash = licenseHash,
                )
                extraLicenses[uniqueId] = licenseModel
            }

            if (uniqueId !in extraLibraries) {
                val libraryModel = LibraryModel(
                        uniqueId = uniqueId,
                        name = name,
                        licenses = listOf(extraLicenses.getValue(uniqueId).hash)
                )
                extraLibraries[uniqueId] = libraryModel
            }
        }
    }

    companion object {
        const val GENERATED_UNIQUE_ID_PREFIX = "com.example.generated.google."

        private const val GRANULAR_BASE_VERSION = 14
        private const val GOOGLE_PLAY_SERVICES_GROUP = "com.google.android.gms"
        private const val LICENSE_ARTIFACT_SUFFIX = "-license"
        private const val FIREBASE_GROUP = "com.google.firebase"
    }
}
