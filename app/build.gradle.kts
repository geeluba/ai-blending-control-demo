plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.example.remotecontrolprojector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.remotecontrolprojector"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/kotlin-tooling-metadata.json"
            excludes += "META-INF/kotlin.kotlin_builtins"
            excludes += "META-INF/kotlin-versions.component"
            excludes += "module-info.class"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Ktor Client (for WebSocket client)
    implementation("io.ktor:ktor-client-core:2.3.10")
    implementation("io.ktor:ktor-client-websockets:2.3.10")
    implementation("io.ktor:ktor-client-cio:2.3.10") // CIO engine
    // Ktor Server (for WebSocket server)
    implementation("io.ktor:ktor-server-core-jvm:2.3.10")
    implementation("io.ktor:ktor-server-websockets-jvm:2.3.10")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.10") // Netty engine
    // Kotlinx Serialization (for type-safe messages)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.4")
    // Ktor content negotiation for serialization
    implementation("io.ktor:ktor-client-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.10")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.10")
    // Logging (Ktor uses SLF4J)
    implementation("org.slf4j:slf4j-simple:2.0.7")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
}