plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dref.transferbubble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dref.transferbubble"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.0"
    }

    /**
     * FIRMA ESTABLE.
     *
     * Antes: se firmaba con signingConfigs.debug, y cada ejecucion de GitHub
     * Actions generaba una clave nueva -> INSTALL_FAILED_UPDATE_INCOMPATIBLE
     * y habia que desinstalar para actualizar.
     *
     * Ahora: si existen las propiedades KEYSTORE_* (que el workflow pasa desde
     * los Secrets), se firma con el keystore propio; si no, se cae a debug para
     * que la compilacion local siga funcionando sin configurar nada.
     */
    signingConfigs {
        create("release") {
            val ksPath = findProperty("KEYSTORE_PATH") as String?
                ?: System.getenv("KEYSTORE_PATH")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = (findProperty("KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = (findProperty("KEY_ALIAS") as String?)
                    ?: System.getenv("KEY_ALIAS")
                keyPassword = (findProperty("KEY_PASSWORD") as String?)
                    ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksPath = findProperty("KEYSTORE_PATH") as String?
                ?: System.getenv("KEYSTORE_PATH")
            signingConfig = if (ksPath != null && file(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
}