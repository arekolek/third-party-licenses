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
import java.security.MessageDigest
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
            val fileName = license.name.lowercase().replace(' ', '_') + "_" + license.hash
            File(licensesDirectory, "lic_generated_$fileName.json")
                    .outputStream()
                    .bufferedWriter()
                    .use {
                        it.write(json.encodeToString(license))
                    }

            File(licensesDirectory, "lic_generated_txt_$fileName.txt")
                    .outputStream()
                    .bufferedWriter()
                    .use {
                        it.write(license.content)
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

            if (uniqueId !in extraLibraries) {
                var license = licenses.drop(metadata.start).take(metadata.length).trim()
                val licenseNames = mutableListOf<String>()

                licenseRegexes.forEach { (licenseName, regex) ->
                    val matchResult = regex.find(license)
                    if (matchResult != null) {
                        licenseNames += licenseName
                        val content = matchResult.value
                        val hash = content.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() }.hash()
                        val licenseModel = LicenseModel(
                                name = licenseName,
                                content = content,
                                hash = hash,
                        )
                        extraLicenses[hash] = licenseModel

                        license = license.removeRange(matchResult.range)
                    }
                }

                licenseNames += "$name license"
                val hash = license.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() }.hash()
                val licenseModel = LicenseModel(
                        name = "$name license",
                        content = license.trim(),
                        hash = hash,
                )
                extraLicenses[hash] = licenseModel

                val libraryModel = LibraryModel(
                        uniqueId = uniqueId,
                        name = name,
                        licenses = licenseNames
                )
                extraLibraries[uniqueId] = libraryModel
            }
        }
    }

    @Suppress("MaxLineLength")
    private val licenseRegexes = mapOf(
            "GNU LGPL" to """GNU LESSER GENERAL PUBLIC LICENSE.*That's all there is to it!""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "GNU GPL" to """GNU GENERAL PUBLIC LICENSE.*Public License instead of this License\.""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "Apache-2.0" to """Apache License\s+Version 2\.0.*See the License for the specific language governing permissions and\s+limitations under the License\.""".toRegex(
                    RegexOption.DOT_MATCHES_ALL
            ),
            "MIT" to """The MIT License.*OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN\s+THE SOFTWARE\.""".toRegex(
                    RegexOption.DOT_MATCHES_ALL
            ),
            "MIT" to """Permission is hereby granted, free of charge, to any person obtaining a copy.*OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN\s+THE SOFTWARE\.""".toRegex(
                    RegexOption.DOT_MATCHES_ALL
            ),
            "BSD-2" to """Copyright \(c\) 2005-\d+, The Android Open Source Project.*END OF TERMS AND CONDITIONS""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "BSD-3-2" to """Redistribution and use in source and binary forms.*Redistributions of source code must retain the above copyright notice.*Redistributions in binary form must reproduce the above copyright notice.*may be used to endorse or promote products.*without specific prior written permission.*EVEN IF ADVISED OF THE\s+POSSIBILITY OF SUCH DAMAGE\.""".toRegex(
                    RegexOption.DOT_MATCHES_ALL
            ),
            "Netscape" to """AMENDMENTS.*The Netscape Public License Version 1\.1.*Modifications\.]""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "Boost" to """Boost Software License - Version 1\.0.*DEALINGS IN THE SOFTWARE\.""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "Eclipse" to """Eclipse Public License, Version 1\.0.*jury trial in any resulting litigation\.""".toRegex(RegexOption.DOT_MATCHES_ALL),
            "BSD-3-1" to """Copyright.*Redistributions of source code must retain the above copyright.*Redistributions in binary form must reproduce the above copyright.*may be used to endorse or promote products.*without specific prior written permission.*EVEN IF ADVISED OF.+POSSIBILITY OF SUCH DAMAGE\.""".toRegex(
                    RegexOption.DOT_MATCHES_ALL
            ),
    )

    private fun String.hash(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(toByteArray())
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val GENERATED_UNIQUE_ID_PREFIX = "com.example.generated.google."

        private const val GRANULAR_BASE_VERSION = 14
        private const val GOOGLE_PLAY_SERVICES_GROUP = "com.google.android.gms"
        private const val LICENSE_ARTIFACT_SUFFIX = "-license"
        private const val FIREBASE_GROUP = "com.google.firebase"
    }
}
