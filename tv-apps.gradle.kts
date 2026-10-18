// Builds and tests the LG webOS and Roku apps alongside the Android app.
// Unit tests run before the Android unit tests; the packages are made with the release APKs
// and land in out/, which the CI publishes with each release.

val tvBuildNumber = System.getenv("BUILD_NUMBER") ?: "1"
val repo = rootDir.absolutePath

fun bash(script: String): List<String> = listOf("bash", "-euo", "pipefail", "-c", script)

// ---------- LG webOS ----------

val webosTest = tasks.register<Exec>("webosTest") {
    description = "Unit tests, TV service tests and the remote-control end-to-end test for the webOS app"
    workingDir = rootDir
    commandLine(bash("""
        mkdir -p build/webos-test
        if [ ! -d build/webos-test/node_modules/playwright-core ]; then
          (cd build/webos-test && npm install --no-save --no-audit --no-fund --loglevel=error playwright-core@1.56.0)
        fi
        CHROME="${'$'}(command -v google-chrome || command -v google-chrome-stable || command -v chromium || command -v chromium-browser)"
        echo "webOS tests using ${'$'}CHROME"
        NODE_PATH="$repo/build/webos-test/node_modules" CHROME="${'$'}CHROME" node --test webos/test/core.test.js webos/test/service.test.js webos/test/app.e2e.test.js
    """.trimIndent()))
}

val packageWebos = tasks.register<Exec>("packageWebos") {
    description = "Packages the webOS app and its TV service as an .ipk"
    workingDir = rootDir
    commandLine(bash("""
        rm -rf build/webos && mkdir -p build/webos/app build/webos/service build/webos/ipk out
        cp webos/app/index.html webos/app/style.css webos/app/app.js webos/app/iptv-core.js webos/app/appinfo.json build/webos/app/
        base64 -d webos/app/icon.png.b64 > build/webos/app/icon.png
        base64 -d webos/app/largeIcon.png.b64 > build/webos/app/largeIcon.png
        cp webos/service/service.js webos/service/package.json webos/service/services.json build/webos/service/
        cp webos/app/iptv-core.js build/webos/service/
        sed -i 's/"version": "1.0.0"/"version": "1.0.$tvBuildNumber"/' build/webos/app/appinfo.json
        npx --yes -p @webos-tools/cli ares-package build/webos/app build/webos/service -o build/webos/ipk
        cp build/webos/ipk/*.ipk out/IPTV-Player-LG-webOS-1.0.$tvBuildNumber.ipk
        ls -la out
    """.trimIndent()))
}

// ---------- Roku ----------

val rokuImages = tasks.register<Exec>("rokuImages") {
    workingDir = rootDir
    commandLine(bash("for f in roku/images/*.png.b64; do base64 -d \"${'$'}f\" > \"${'$'}{f%.b64}\"; done"))
}

val rokuCheck = tasks.register<Exec>("rokuCheck") {
    description = "Compiles and validates the Roku app with the BrighterScript compiler"
    dependsOn(rokuImages)
    workingDir = file("$repo/roku")
    commandLine(bash("npx --yes brighterscript@0 --project bsconfig.json"))
}

val rokuTest = tasks.register<Exec>("rokuTest") {
    description = "Runs the Roku logic tests with the brs BrightScript interpreter"
    workingDir = file("$repo/roku")
    commandLine(bash("""
        mkdir -p ../build
        npx --yes brs@0 source/core.brs tests/test_core.brs | tee ../build/roku-test.log
        grep -q 'ALL TESTS PASSED' ../build/roku-test.log
    """.trimIndent()))
}

val packageRoku = tasks.register<Zip>("packageRoku") {
    description = "Packages the Roku app as a zip for developer-mode sideloading"
    dependsOn(rokuImages)
    from("$repo/roku") {
        include("manifest", "source/**", "components/**", "images/*.png")
        filesMatching("manifest") {
            filter { line: String -> if (line.startsWith("build_version=")) "build_version=$tvBuildNumber" else line }
        }
    }
    archiveFileName.set("IPTV-Player-Roku-1.0.$tvBuildNumber.zip")
    destinationDirectory.set(file("$repo/out"))
}

// ---------- hook into the Android build ----------

tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(webosTest, rokuCheck, rokuTest) }
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(packageWebos, packageRoku) }
