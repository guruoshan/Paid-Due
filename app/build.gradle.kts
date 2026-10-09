import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}


val paidDueLocalProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun paidDueConfig(name: String): String =
    paidDueLocalProperties.getProperty(name)
        ?: System.getenv(name)
        ?: ""

fun buildConfigString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""
android {
    val inboundApplyDefault = providers.gradleProperty("PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES")
        .orElse(providers.environmentVariable("PAID_DUE_APPLY_INBOUND_FINANCIAL_ENVELOPES"))
        .orElse("false")
        .get()
        .toBooleanStrictOrNull() ?: false
    namespace = "com.dailymoneyregister"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dailymoneyregister"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.dailymoneyregister.HiltTestRunner"
        val paidDueSupabaseUrl =
            paidDueConfig("PAID_DUE_SUPABASE_URL").trim()

        val paidDueSupabasePublishableKey =
            paidDueConfig("PAID_DUE_SUPABASE_PUBLISHABLE_KEY").trim()

        check(paidDueSupabaseUrl.startsWith("https://")) {
            "PAID_DUE_SUPABASE_URL must be configured as HTTPS."
        }

        check(paidDueSupabasePublishableKey.startsWith("sb_publishable_")) {
            "PAID_DUE_SUPABASE_PUBLISHABLE_KEY is missing or invalid."
        }

        buildConfigField(
            "String",
            "PAID_DUE_SUPABASE_URL",
            buildConfigString(paidDueSupabaseUrl)
        )

        buildConfigField(
            "String",
            "PAID_DUE_SUPABASE_PUBLISHABLE_KEY",
            buildConfigString(paidDueSupabasePublishableKey)
        )

        /*
         * Paid&Due employee invitation public URL.
         *
         * Configure with either:
         *
         *   gradle.properties:
         *   PAID_DUE_PUBLIC_INVITE_BASE_URL=https://<paid-due-owned-host>/open/invite
         *
         * or environment variable:
         *   PAID_DUE_PUBLIC_INVITE_BASE_URL
         *
         * Empty means invite sharing fails closed.
         * This is a public URL, not a secret.
         */
        val paidDueInviteBaseUrl =
            providers.gradleProperty("PAID_DUE_PUBLIC_INVITE_BASE_URL")
                .orElse(
                    providers.environmentVariable(
                        "PAID_DUE_PUBLIC_INVITE_BASE_URL"
                    )
                )
                .orElse("")
                .get()
                .trim()
                .trimEnd('/')

        buildConfigField(
            "String",
            "PAID_DUE_PUBLIC_INVITE_BASE_URL",
            "\"${paidDueInviteBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        /*
         * Paid&Due public backend origin.
         *
         * This is public configuration, not a secret.
         */
        val paidDueBackendBaseUrl =
            providers.gradleProperty(
                "PAID_DUE_BACKEND_BASE_URL"
            )
                .orElse(
                    providers.environmentVariable(
                        "PAID_DUE_BACKEND_BASE_URL"
                    )
                )
                .orElse(
                    "https://paidanddue.guruoshan2208.workers.dev"
                )
                .get()
                .trim()
                .trimEnd('/')

        check(
            paidDueBackendBaseUrl.startsWith(
                "https://"
            )
        ) {
            "PAID_DUE_BACKEND_BASE_URL must use HTTPS."
        }

        buildConfigField(
            "String",
            "PAID_DUE_BACKEND_BASE_URL",
            buildConfigString(
                paidDueBackendBaseUrl
            )
        )
    }

    buildTypes {
        getByName("debug") {
            buildConfigField(
                "boolean",
                "APPLY_INBOUND_FINANCIAL_ENVELOPES",
                inboundApplyDefault.toString()
            )
        }
        release {
            buildConfigField("boolean", "APPLY_INBOUND_FINANCIAL_ENVELOPES", inboundApplyDefault.toString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        execution = "HOST"
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


dependencies {
    // Paid&Due cloud foundation.
    // No financial cloud synchronization is enabled in this phase.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-android:3.0.3")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Database-at-rest encryption foundation.
    // Session 7 dependency compatibility proof only.
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    // Phone canonicalization
    implementation(libs.libphonenumber)

    // Testing
    testImplementation(libs.junit)
    // InvoiceSnapshotCodec persists JSON on-device; provide the same implementation to JVM tests.
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.mockk)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestUtil(libs.androidx.test.orchestrator)
    kspAndroidTest(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}


