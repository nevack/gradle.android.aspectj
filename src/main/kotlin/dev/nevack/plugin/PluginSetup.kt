package dev.nevack.plugin

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import dev.nevack.AndroidConfig
import dev.nevack.AspectJExtension
import dev.nevack.api.AspectJCompileTask
import dev.nevack.utils.LANG_AJ
import dev.nevack.utils.getJavaTask
import dev.nevack.utils.getVariantDataList
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.PluginContainer

internal fun configProject(project: Project, config: AndroidConfig, settings: AspectJExtension) {
    if (settings.extendClasspath) {
        project.repositories.mavenCentral()
        project.dependencies.add("implementation", "org.aspectj:aspectjrt:${settings.ajc}")
    }

    project.whenEvaluated {
        prepareVariant(config)

        if (!settings.dryRun) {
            configureCompiler(project, config)
        }
    }
}

private fun prepareVariant(config: AndroidConfig) {
    val sets = config.extAndroid.sourceSets

    fun applier(path: String) = sets.getByName(path).java.srcDir("src/$path/$LANG_AJ")

    // general sets
    arrayOf("main", "test", "androidTest").forEach {
        sets.getByName(it).java.srcDir("src/$it/$LANG_AJ")
    }

    // applies srcSet 'aspectj' for each build variant
    getVariantDataList(config.plugin).forEach { variant ->
        variant.productFlavors.forEach { applier(it.second) }
        applier(variant.buildType ?: variant.flavorName)
    }
}

private fun configureCompiler(project: Project, config: AndroidConfig) {
    getVariantDataList(config.plugin).forEach variantScanner@ { variant ->
        val variantName = variant.name.capitalize()

        // do not configure compiler task for non-test variants in ConfigScope.JUNIT
        if (config.scope == ConfigScope.JUNIT && variantName.contains("androidtest", true))
            return@variantScanner

        val taskName = "compile$variantName${LANG_AJ.capitalize()}"
        val ajc = AspectJCompileTask.Builder(project)
                .plugin(project.plugins.getPlugin(config))
                .config(project.extensions.getByType(AspectJExtension::class.java))
                .compiler(getJavaTask(variant.variantData))
                .variant(variant.name)
                .name(taskName)

        val variantTypeClass: Class<*> = variant.variantType::class.java
        val variantAnalyticsType: Any? = when {
            variantTypeClass.fields.any { it.name == "mAnalyticsVariantType" } ->
                variantTypeClass.getField("mAnalyticsVariantType").get(variant.variantType)
            variantTypeClass.fields.any { it.name == "analyticsVariantType" } ->
                variantTypeClass.getField("analyticsVariantType").get(variant.variantType)
            variantTypeClass.enumConstants?.isNotEmpty() == true ->
                variantTypeClass.enumConstants[5] // suspect to find UNIT_TEST
                        ?.javaClass
                        ?.getMethod("getAnalyticsVariantType")
                        ?.invoke(variant.variantType)
            else -> null
        }

        if (variantAnalyticsType is GradleBuildVariant.VariantType
                && variantAnalyticsType == GradleBuildVariant.VariantType.UNIT_TEST) {
            if (config.aspectj().compileTests) {
                ajc.overwriteJavac(true)
                        .buildAndAttach(config)
            }
        } else {
            ajc.buildAndAttach(config)
        }
    }
}

private inline fun <reified T> PluginContainer.getPlugin(config: AndroidConfig): T where T : Plugin<Project> {
    @Suppress("UNCHECKED_CAST")
    val plugin: Class<out T> = (if (config.isLibraryPlugin) LibraryPlugin::class.java else AppPlugin::class.java) as Class<T>
    return getPlugin(plugin)
}

private inline fun <reified T> Project.whenEvaluated(noinline fn: Project.() -> T) {
    if (state.executed) fn() else afterEvaluate {
        fn.invoke(this)
    }
}