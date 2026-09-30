plugins {
    id("dev.architectury.loom") version "1.13-SNAPSHOT"
}

val minecraft = property("deps.minecraft") as String

// 1.21.1 legacy 渲染层用不到新版 net/minecraft/client/renderer/rendertype/RenderType.create，
// 而 traceableprint.accesswidener 里的这条规则在 1.21.1 无对应类，会导致 validateAccessWidener 失败。
// 故 1.21.1 走空的 traceableprint.legacy.accesswidener（无规则），1.21.11 仍用带规则的 traceableprint.accesswidener。
val isLegacy1211 = minecraft == "1.21.1"
val awName = (property("mod.id") as String) + (if (isLegacy1211) ".legacy.accesswidener" else ".accesswidener")

loom {
    silentMojangMappingsLicense()
    accessWidenerPath = rootProject.file("src/main/resources/$awName")
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

        this["access_widener"] = awName

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
    mavenCentral()
    maven("https://maven.architectury.dev/")
    maven("https://maven.shedaniel.me/")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.terraformersmc.com/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("deps.minecraft")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric-api")}")

    // @NonNull 注解（org.jspecify）：新版由 fabric-api/映射自带，旧版（1.21.1）需显式提供
    compileOnly("org.jspecify:jspecify:1.0.0")

    // modmenu
    modApi("com.terraformersmc:modmenu:${property("deps.modmenu")}")
    // cloth config
    modApi("me.shedaniel.cloth:cloth-config-fabric:${property("deps.cloth")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
}

tasks {
    processResources {
        // 只随包发布当前版本选中的那一个 AW：始终排除 unobf 版（属 26.x）与未被选中的 named 变体。
        val id = project.property("mod.id") as String
        val otherNamedAw = id + (if (isLegacy1211) ".accesswidener" else ".legacy.accesswidener")
        exclude("**/neoforge.mods.toml", "**/mods.toml", "**/${id}.unobf.accesswidener", "**/$otherNamedAw", "**/*.mcmeta")
        // 分版本只发一套着色器：<=1.21.1 用 legacy（footprint_legacy），>1.21.1 用新版 pipeline（footprint_pulse）；剔除另一套。
        if (isLegacy1211) exclude("**/footprint_pulse.*") else exclude("**/footprint_legacy.*")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(remapJar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs"))
        dependsOn("build")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
