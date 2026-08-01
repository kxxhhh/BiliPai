import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.android.purebilibili.community.bilicompanion"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation("com.github.jay3-yy.BiliPai:plugin-sdk:0.1.0-SNAPSHOT")
}

val releaseJar = layout.buildDirectory.file(
    "intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"
)
val stagingDir = layout.buildDirectory.dir("bpplugin-staging")
val signingKeyPath = providers.environmentVariable("BILIPAI_BPPLUGIN_SIGNING_KEY")
    .orElse("signing/bili-companion-private.pem")
val pluginKeyId = "bili-companion-release-2026"

val prepareBpPlugin by tasks.registering {
    dependsOn("assembleRelease")

    doLast {
        val staging = stagingDir.get().asFile
        staging.deleteRecursively()
        File(staging, "companion").mkdirs()

        val manifestFile = rootProject.file("plugin-manifest.json")
        val classesJar = releaseJar.get().asFile
        if (!manifestFile.isFile) throw GradleException("缺少 plugin-manifest.json")
        if (!classesJar.isFile) throw GradleException("缺少 classes.jar: ${classesJar.absolutePath}")

        manifestFile.copyTo(File(staging, "plugin-manifest.json"), overwrite = true)
        classesJar.copyTo(File(staging, "classes.jar"), overwrite = true)
        rootProject.file("plugin/src/main/assets/companion/avatar.png")
            .copyTo(File(staging, "companion/avatar.png"), overwrite = true)
        rootProject.file("plugin/src/main/assets/companion/pet_sprites.png")
            .copyTo(File(staging, "companion/pet_sprites.png"), overwrite = true)
        rootProject.file("plugin/src/main/assets/companion/profile.json")
            .copyTo(File(staging, "companion/profile.json"), overwrite = true)

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        val entries = staging.walkTopDown()
            .filter { it.isFile && it.name != "plugin-signature.json" }
            .map { file ->
                val name = file.relativeTo(staging).invariantSeparatorsPath
                name to file
            }
            .sortedBy { it.first }
            .toList()
        val signingPayload = entries.joinToString("") { (name, file) ->
            "$name\n${file.length()}\n${sha256(file)}\n"
        }.toByteArray(Charsets.UTF_8)

        val keyFile = project.file(signingKeyPath.get())
        if (!keyFile.isFile) {
            throw GradleException(
                "缺少 BPPlugin 私钥：${keyFile.absolutePath}。请设置 BILIPAI_BPPLUGIN_SIGNING_KEY，私钥不要提交到仓库。"
            )
        }
        val keyBase64 = keyFile.readText()
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBase64))
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingPayload)
        }.sign()

        File(staging, "plugin-signature.json").writeText(
            """
            {
              "formatVersion": 1,
              "keyId": "$pluginKeyId",
              "algorithm": "SHA256withRSA",
              "signatureBase64": "${Base64.getEncoder().encodeToString(signature)}"
            }
            """.trimIndent() + "\n"
        )
    }
}

val packageBpPlugin by tasks.registering(Zip::class) {
    dependsOn(prepareBpPlugin)
    archiveBaseName.set("bili-companion-1.1.0")
    archiveExtension.set("bpplugin")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(stagingDir)
}
