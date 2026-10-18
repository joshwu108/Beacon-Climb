pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "beacon-climb"
include(":api")
project(":api").projectDir = file("services/api")
include(":events")
project(":events").projectDir = file("services/events")
include(":stream")
project(":stream").projectDir = file("services/stream")
include(":features")
project(":features").projectDir = file("services/features")
