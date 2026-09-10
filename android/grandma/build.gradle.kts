import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ru.pult.grandma"
    compileSdk = 36

    defaultConfig {
        // Installed package id — neutral, Swedish (se.), никакого ru.
        applicationId = "se.pult.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Адрес сигналинга задаётся при сборке; в паре он всё равно перезаписывается
        // тем, что пришло в пакете спаривания.
        buildConfigField("String", "DEFAULT_SIGNALING_URL", "\"wss://signal.pult.local/ws\"")

        // Источник подписанного конфига (адреса/ключи). Пусто → автоподхват выключен.
        // Принимается только подписанный запиненным ключом манифест (см. ConfigRefresher).
        buildConfigField(
            "String",
            "CONFIG_SOURCE_URL",
            "\"${System.getenv("CONFIG_SOURCE_URL") ?: ""}\"",
        )

        // Токен каналов /link и /tunnel (AGENT_TOKEN на сервере). Только для личной
        // демо-сборки: токен внутри APK извлекаем, боевой путь — выдача при спаривании.
        buildConfigField(
            "String",
            "TUNNEL_TOKEN",
            "\"${System.getenv("TUNNEL_TOKEN") ?: ""}\"",
        )

        // Токен скачивания обновлений (UPDATE_TOKEN на сервере): query-параметр у
        // /update/<file> и /api/update/manifest. Демо-значение — для локального стенда.
        buildConfigField(
            "String",
            "UPDATE_TOKEN",
            "\"${System.getenv("UPDATE_TOKEN") ?: "pult-local-test"}\"",
        )

        // Параметры Firebase для ручной инициализации FCM без google-services.json.
        // Заполняются из окружения при сборке релиза; пустые → FCM не активируется,
        // приложение работает по онлайн-пути и автозапуску.
        buildConfigField("String", "FCM_PROJECT_ID", "\"${System.getenv("FCM_PROJECT_ID") ?: ""}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${System.getenv("FCM_APP_ID") ?: ""}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${System.getenv("FCM_API_KEY") ?: ""}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${System.getenv("FCM_SENDER_ID") ?: ""}\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Проект собирается вокруг Nordic Gateway: единственная редакция — v2
    // (управление устройством, §6). AccessibilityService объявляется
    // только здесь (src/v2/AndroidManifest.xml).
    flavorDimensions += "edition"
    productFlavors {
        create("v2") {
            dimension = "edition"
            buildConfigField("boolean", "CONTROL_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // Эмулятор видит хост как 10.0.2.2. Открытый ws:// допустим только в отладке —
            // в релизе остаётся wss://, иначе сигналинг можно слушать по дороге.
            // Адрес можно переопределить окружением SIGNALING_URL (стенд/VPS).
            buildConfigField(
                "String",
                "DEFAULT_SIGNALING_URL",
                "\"${System.getenv("SIGNALING_URL") ?: "ws://10.0.2.2:8080/ws"}\"",
            )
        }

        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // libwebrtc тянет ~12 МБ нативного кода на каждую ABI. В отладке держим все
            // (эмуляторы), в релизе — только то, что стоит у реальных людей: иначе APK
            // для пилота весит 48 МБ, а его ставят по мобильной сети.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    // Прямой HTTP (загрузка обновлений, UpdateManager): та же версия, что и в :core.
    implementation(libs.okhttp)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Свой ADB-клиент (TLS-паринг wireless debugging + shell-exec как UID 2000) —
    // автономный shell-уровень без Shizuku и без постоянного LanAgent-процесса.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    testImplementation(libs.junit)
}
