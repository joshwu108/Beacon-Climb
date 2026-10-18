pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "beacon-climb"
include(":api")
project(":api").projectDir = file("services/api")
include(":stream")
project(":stream").projectDir = file("services/stream")
