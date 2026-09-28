plugins {
    id("dev.architectury.loom-no-remap") version "1.14-SNAPSHOT"
}

val minecraft = property("deps.minecraft") as String;

loom {
    accessWidenerPath = rootProject.file("src/main/resources/${property("mod.id")}.unobf.accesswidener")
}

tasks.named<ProcessResources>("processResources") {
    fun prop(name: String) = project.property(name) as String

    val props = HashMap<String, String>().apply {
        this["mod_group"] =     prop("mod.group")
        this["mod_id"] =        prop("mod.id")
        this["mod_name"] =      prop("mod.name")
        this["mod_version"] =   prop("mod.version")
        this["mod_description"]=prop("mod.description")
        this["mod_author"] =    prop("mod.author")
        this["mod_contributor"]=prop("mod.contributor")
        this["mod_sources"] =   prop("mod.sources")
        this["mod_issues"] =    prop("mod.issues")
        this["mod_homepage"] =  prop("mod.homepage")
        this["mod_modrinth"] =  prop("mod.modrinth")
        this["mod_mcmod"] =     prop("mod.mcmod")
        this["mod_license"] =   prop("mod.license")
        this["mod_icon"] =      prop("mod.icon")

        this["version_range"] = prop("version_range")

        this["access_widener"] = "${prop("mod.id")}.unobf.accesswidener"

        // insert version-specific mixins

        // insert deps
    }

    filesMatching(listOf("fabric.mod.json", "${prop("mod.id")}.mixins.json")) {
        expand(props)
    }
}

version = "${property("mod.version")}+${minecraft}-fabric"
base.archivesName = property("mod.id") as String

repositories {
    mavenLocal()
    maven("https://api.modrinth.com/maven")
    maven("https://maven.terraformersmc.com/")
    maven("https://maven.shedaniel.me/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    implementation("net.fabricmc:fabric-loader:${property("deps.fabric")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric-api")}")

    //modmenu
    implementation("com.terraformersmc:modmenu:${property("deps.modmenu")}")
}

tasks {
    processResources {
        // 只排除 named 版 AW（与本 unobf 构建无关），保留 traceableprint.unobf.accesswidener 随包发布，
        // 供 Fabric 运行期按 fabric.mod.json 的 accessWidener 字段加载（loom-no-remap 不会自动回注）。
        exclude("**/neoforge.mods.toml", "**/mods.toml", "**/${project.property("mod.id")}.accesswidener")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
