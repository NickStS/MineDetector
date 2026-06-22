pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // ✅ JitPack для PhotoView
        maven { url = uri("https://jitpack.io") }

        // ✅ DJI SDK Repository (КРИТИЧНО!)
        maven {
            url = uri("https://raw.githubusercontent.com/DJI-Mobile-SDK/Android-Releases/master/")
        }

        // ✅ Mapbox (если используется)
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            credentials {
                username = "mapbox"
                password = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
                    .orElse(System.getenv("MAPBOX_DOWNLOADS_TOKEN"))
                    .getOrElse("")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}

rootProject.name = "MineDetectorApp"
include(":app")