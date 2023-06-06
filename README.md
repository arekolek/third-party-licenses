
To install the app (it just shows the licenses):

```shell
./gradlew app-demo:installDebug
```

To update licenses after dependencies change:

```shell
./update-licenses.sh
```

It will run the `updateGoogleLicenses` task defined by `buildSrc/src/main/java/com/example/tpl/gradle/licenses/LicenseCheckPlugin.kt`,
followed by `exportLibraryDefinitions` task defined by `com.mikepenz.aboutlibraries.plugin`.

On a separate branch `detect-license` you can see a proof-of-concept for detecting different licenses that could be included in ordinary way.
