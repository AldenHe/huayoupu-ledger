import org.gradle.api.tasks.PathSensitivity

plugins { id("com.android.application"); kotlin("android") }

val ledgerAbi = providers.gradleProperty("ledgerAbi").getOrElse("arm64-v8a")
require(ledgerAbi in listOf("arm64-v8a", "x86_64")) { "Unsupported ledger ABI" }

val webProtectionDirectory = rootProject.layout.projectDirectory.dir("tools/web-protection")
val installReleaseWebTools by tasks.registering(Exec::class) {
    description = "Installs the locked, build-time-only JavaScript protection tool."
    inputs.files(webProtectionDirectory.file("package.json"), webProtectionDirectory.file("package-lock.json"))
    outputs.dir(webProtectionDirectory.dir("node_modules"))
    workingDir(webProtectionDirectory)
    if (System.getProperty("os.name").startsWith("Windows")) {
        commandLine("cmd", "/c", "npm.cmd", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    } else {
        commandLine("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    }
}

abstract class PrepareLedgerAssets : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceAssets: DirectoryProperty
    @get:Internal
    abstract val protectionTools: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protectionSources: ConfigurableFileCollection
    @get:Input abstract val protectJavaScript: Property<Boolean>
    @get:Input abstract val targetAbi: Property<String>
    @get:Input abstract val runtimeCoordinates: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val v6Inputs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        project.exec {
            commandLine("node", protectionTools.file("prepare-assets.cjs").get().asFile.absolutePath,
                sourceAssets.get().asFile.absolutePath, outputDirectory.get().asFile.absolutePath,
                if (protectJavaScript.get()) "release" else "debug")
        }
        run {
            val coordinates = temporaryDir.resolve("coordinates.txt")
            coordinates.writeText(runtimeCoordinates.get().joinToString("\n"))
            project.exec {
                commandLine(project.providers.gradleProperty("ledgerPython").getOrElse("python"),
                    project.rootProject.file("scripts/prepare-ledger-v6-assets.py"),
                    "--output", outputDirectory.get().asFile, "--abi", targetAbi.get(), "--coordinates", coordinates)
            }
        }
    }
}
android {
    namespace = "net.ankio.ledger"
    compileSdk = 36
    // Keep the fixed Chinese interface available even when installed on an English device.
    bundle { language { enableSplit = false } }
    defaultConfig {
        applicationId = "net.ankio.ledger"
        minSdk = 30
        targetSdk = 36
        versionCode = 119
        versionName = "0.5.113-v6"
        buildConfigField("String", "LOCAL_OCR_ENGINE", "\"paddle-v6-tiny\"")
        buildConfigField("String", "LOCAL_OCR_LABEL", "\"PaddleOCR V6 Tiny\"")
        testInstrumentationRunner = providers.gradleProperty("ledgerTestRunner").getOrElse("androidx.test.runner.AndroidJUnitRunner")
        ndk { abiFilters += ledgerAbi }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { buildConfig = true }
    // Every variant consumes one generated copy: no duplicate asset overlays and no source rewriting.
    sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    buildTypes {
        release {
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val protected = variant.buildType == "release"
        val assetsTask = tasks.register<PrepareLedgerAssets>("prepare${variant.name.replaceFirstChar { it.uppercase() }}LedgerAssets") {
            sourceAssets.set(layout.projectDirectory.dir("src/main/assets"))
            protectionTools.set(webProtectionDirectory)
            protectionSources.from(webProtectionDirectory.file("prepare-assets.cjs"),
                rootProject.file("LICENSE"),
                webProtectionDirectory.file("package.json"), webProtectionDirectory.file("package-lock.json"),
                webProtectionDirectory.file("LICENSE.javascript-obfuscator.BSD"))
            protectJavaScript.set(protected)
            targetAbi.set(ledgerAbi)
            runtimeCoordinates.set(providers.provider {
                configurations.getByName("${variant.name}RuntimeClasspath").incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion?.let { id -> "${id.group}:${id.name}:${id.version}" } }.distinct()
            })
            v6Inputs.from(rootProject.file("scripts/prepare-ledger-v6-assets.py"),
                rootProject.file("third_party/ocr-runtime/config/upstream-lock.json"), rootProject.file("third_party/ocr-runtime/config/ledger-v6-runtime-lock.json"),
                rootProject.fileTree("third_party/ocr-runtime/licenses"),
                rootProject.fileTree(".tools/ocr-ab/sdk/src/main"), rootProject.file(".tools/ocr-ab/sdk/LICENSE"),
                rootProject.file(".tools/ocr-ab/sdk/proguard-rules.pro"),
                rootProject.fileTree(".tools/ocr-ab/v6-assets"), rootProject.fileTree(".tools/ocr-native") { include("*-$ledgerAbi.aar") })
            outputDirectory.set(layout.buildDirectory.dir("generated/ledgerAssets/${variant.name}"))
            if (protected) dependsOn(installReleaseWebTools)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(assetsTask, PrepareLedgerAssets::outputDirectory)
    }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":paddle-ocr-sdk"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
