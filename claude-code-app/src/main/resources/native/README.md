# Generated cc-connect resources

The `cc-connect` executables packaged in the application JAR are downloaded by
the Gradle `vendorCcConnect` task from the immutable release pinned in
`gradle/native-assets.properties`.

Do not copy locally built executables into this directory. For unpublished or
offline assets, pass a directory containing the selected platform archive:

```bash
./gradlew :claude-code-app:shadowJar -PccConnectAssetDir=/path/to/release-assets
```

The archives are still verified against the manifest SHA-256 values.
