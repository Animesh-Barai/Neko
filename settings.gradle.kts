pluginManagement {
    repositories {
        gradlePluginPortal()
        jcenter()
        google()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin")) {
                useVersion("1.3.71")
            } else if (requested.id.id.equals("com.android.application")) {
                useModule("com.android.tools.build:gradle:3.6.0")
            } else if (requested.id.id.equals("com.google.gms.google-services")) {
                useModule("com.google.gms:google-services:4.3.3")
            }

        }
    }
}

include(":app")
