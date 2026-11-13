plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.althub.alteriza"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.althub.alteriza"
        minSdk = 26
        targetSdk = 35
        val runNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    tasks.whenTaskAdded {
        if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
            dependsOn("cargoBuild")
        }
    }
}

val rustProjectDir = file("${rootDir}/rust")
val jniLibsDir = file("${projectDir}/src/main/jniLibs")

tasks.register<Exec>("cargoBuild") {
    workingDir = rustProjectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-graphics")
}
