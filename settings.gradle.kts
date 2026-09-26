pluginManagement {
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
                maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///root/maven-mirror") }
        // 必须排在腾讯镜像之前：腾讯镜像未做 content 过滤，会“认领”所有 group。
        // 它代理不到 com.github.* 的 JAR（只有 POM），一旦先命中就不会回退到 jitpack。
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.Ujhhgtg.rhino")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            // 与上面 jitpack 同理：腾讯镜像未做 content 过滤，会“认领”所有 group。
            // 它代理得到 com.tencent:mmkv 的 POM，却给不出对应 AAR，
            // 一旦命中就不会回退到 mavenCentral，导致解析报
            // "Could not find mmkv-x.y.z.jar"。这里直接把 com.tencent 交给 mavenCentral。
            content {
                excludeGroup("com.tencent")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "wekit"

include(
    ":app",
    ":libs:common:annotation-scanner",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt"
)
