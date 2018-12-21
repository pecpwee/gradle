/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.integration

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

import org.gradle.kotlin.dsl.embeddedKotlinVersion
import org.gradle.kotlin.dsl.fixtures.AbstractKotlinIntegrationTest
import org.gradle.kotlin.dsl.fixtures.DeepThought
import org.gradle.kotlin.dsl.fixtures.LightThought
import org.gradle.kotlin.dsl.fixtures.ZeroThought
import org.gradle.kotlin.dsl.fixtures.containsMultiLineString
import org.gradle.kotlin.dsl.support.normaliseLineSeparators

import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat

import org.junit.Assert.assertNotEquals
import org.junit.Test


class GradleKotlinDslIntegrationTest : AbstractKotlinIntegrationTest() {

    @Test
    fun `given a buildscript block, it will be used to compute the runtime classpath`() {
        checkBuildscriptBlockIsUsedToComputeRuntimeClasspathAfter { it }
    }

    @Test
    fun `given a buildscript block separated by CRLF, it will be used to compute the runtime classpath`() {
        checkBuildscriptBlockIsUsedToComputeRuntimeClasspathAfter {
            it.replace("\r\n", "\n").replace("\n", "\r\n")
        }
    }

    private
    fun checkBuildscriptBlockIsUsedToComputeRuntimeClasspathAfter(buildscriptTransformation: (String) -> String) {

        withClassJar("fixture.jar", DeepThought::class.java)

        withBuildScript("""
            buildscript {
                dependencies { classpath(files("fixture.jar")) }
            }

            task("compute") {
                doLast {
                    val computer = ${DeepThought::class.qualifiedName}()
                    val answer = computer.compute()
                    println("*" + answer + "*")
                }
            }
        """.let(buildscriptTransformation))

        assert(
            build("compute").output.contains("*42*"))
    }

    @Test
    fun `given a script plugin with a buildscript block, it will be used to compute its classpath`() {

        withClassJar("fixture.jar", DeepThought::class.java)

        withFile("other.gradle.kts", """
            buildscript {
                dependencies { classpath(files("fixture.jar")) }
            }

            task("compute") {
                doLast {
                    val computer = ${DeepThought::class.qualifiedName}()
                    val answer = computer.compute()
                    println("*" + answer + "*")
                }
            }
        """)

        withBuildScript("""
            apply(from = "other.gradle.kts")
        """)

        assert(
            build("compute").output.contains("*42*"))
    }

    @Test
    fun `given a buildSrc dir, it will be added to the compilation classpath`() {

        withFile("buildSrc/src/main/groovy/build/DeepThought.groovy", """
            package build
            class DeepThought {
                def compute() { 42 }
            }
        """)

        withBuildScript("""
            task("compute") {
                doLast {
                    val computer = build.DeepThought()
                    val answer = computer.compute()
                    println("*" + answer + "*")
                }
            }
        """)

        assert(
            build("compute").output.contains("*42*"))
    }

    @Test
    fun `given a Kotlin project in buildSrc, it will be added to the compilation classpath`() {

        assumeNonEmbeddedGradleExecuter()

        withKotlinBuildSrc()

        withFile("buildSrc/src/main/kotlin/build/DeepThought.kt", """
            package build

            class DeepThought() {
                fun compute(handler: (Int) -> Unit) { handler(42) }
            }
        """)

        withFile("buildSrc/src/main/kotlin/build/DeepThoughtPlugin.kt", """
            package build

            import org.gradle.api.*
            import org.gradle.kotlin.dsl.*

            class DeepThoughtPlugin : Plugin<Project> {
                override fun apply(project: Project) {
                    project.run {
                        task("compute") {
                            doLast {
                                DeepThought().compute { answer ->
                                    println("*" + answer + "*")
                                }
                            }
                        }
                    }
                }
            }
        """)

        withBuildScript("""
            buildscript {
                // buildSrc types are available within buildscript
                // and must always be fully qualified
                build.DeepThought().compute { answer ->
                    println("buildscript: " + answer)
                }
            }
            apply<build.DeepThoughtPlugin>()
        """)

        executer.expectDeprecationWarning()

        val output = build("compute").output
        assert(output.contains("buildscript: 42"))
        assert(output.contains("*42*"))
    }

    @Test
    fun `can compile against a different (but compatible) version of the Kotlin compiler`() {

        assumeNonEmbeddedGradleExecuter()

        val differentKotlinVersion = "1.0.7"
        val expectedKotlinCompilerVersionString = "1.0.7-release-1"

        assertNotEquals(embeddedKotlinVersion, differentKotlinVersion)

        withBuildScript("""
            import org.jetbrains.kotlin.config.KotlinCompilerVersion
            import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

            buildscript {
                repositories {
                    jcenter()
                }
                dependencies {
                    classpath(kotlin("gradle-plugin", version = "$differentKotlinVersion"))
                }
            }

            apply(plugin = "kotlin")

            tasks.withType<KotlinCompile> {
                // can configure the Kotlin compiler
                kotlinOptions.suppressWarnings = true
            }

            task("print-kotlin-version") {
                doLast {
                    val compileOptions = tasks.filterIsInstance<KotlinCompile>().joinToString(prefix="[", postfix="]") {
                        it.name + "=" + it.kotlinOptions.suppressWarnings
                    }
                    println(KotlinCompilerVersion.VERSION + compileOptions)
                }
            }
        """)

        executer.expectDeprecationWarning()

        assertThat(
            build("print-kotlin-version").output,
            containsString("$expectedKotlinCompilerVersionString[compileKotlin=true, compileTestKotlin=true]"))
    }

    @Test
    fun `can apply base plugin via plugins block`() {

        withBuildScript("""
            plugins {
                id("base")
            }

            task("plugins") {
                doLast {
                    println(plugins.map { "*" + it::class.simpleName + "*" })
                }
            }
        """)

        assertThat(
            build("plugins").output,
            containsString("*BasePlugin*"))
    }

    @Test
    fun `can use Closure only APIs`() {

        withBuildScript("""
            gradle.buildFinished(closureOf<org.gradle.BuildResult> {
                println("*" + action + "*") // <- BuildResult.getAction()
            })
        """)

        assert(
            build("build").output.contains("*Build*"))
    }

    @Test
    fun `given an exception thrown during buildscript block execution, its stack trace should contain correct file and line info`() {

        withBuildScript(""" // line 1
            // line 2
            // line 3
            buildscript { // line 4
                throw IllegalStateException() // line 5
            }
        """)

        assertThat(
            buildFailureOutput(),
            containsString("build.gradle.kts:5"))
    }

    @Test
    fun `given a script with more than one buildscript block, it throws exception with offending block line number`() {

        withBuildScript(""" // line 1
            buildscript {}  // line 2
            buildscript {}  // line 3
        """)

        assertThat(
            buildFailureOutput(),
            containsString("build.gradle.kts:3:13: Unexpected `buildscript` block found. Only one `buildscript` block is allowed per script."))
    }

    @Test
    fun `given a script with more than one plugins block, it throws exception with offending block line number`() {

        withBuildScript(""" // line 1
            plugins {}      // line 2
            plugins {}      // line 3
        """)

        assertThat(
            buildFailureOutput(),
            containsString("build.gradle.kts:3:13: Unexpected `plugins` block found. Only one `plugins` block is allowed per script."))
    }

    @Test
    fun `given a buildscript block compilation error, it reports correct error location`() {

        assertCorrectLocationIsReportedForErrorIn("buildscript")
    }

    @Test
    fun `given a plugins block compilation error, it reports correct error location`() {

        assertCorrectLocationIsReportedForErrorIn("plugins")
    }

    private
    fun assertCorrectLocationIsReportedForErrorIn(block: String) {
        val buildFile =
            withBuildScript("""
                $block {
                    val module = "foo:bar:${'$'}fooBarVersion"
                }
            """)

        assertThat(
            buildFailureOutput("tasks"),
            containsString("e: $buildFile:3:44: Unresolved reference: fooBarVersion"))
    }

    @Test
    fun `sub-project build script inherits parent project compilation classpath`() {

        withClassJar("fixture.jar", DeepThought::class.java)

        withBuildScript("""
            buildscript {
                dependencies { classpath(files("fixture.jar")) }
            }
        """)

        withSettings("include(\"sub-project\")")

        withBuildScriptIn("sub-project", """
            task("compute") {
                doLast {
                    val computer = ${DeepThought::class.qualifiedName}()
                    val answer = computer.compute()
                    println("*" + answer + "*")
                }
            }
        """)

        assert(
            build(":sub-project:compute").output.contains("*42*"))
    }

    @Test
    fun `given non-existing build script file name set in settings do not fail`() {

        withSettings("rootProject.buildFileName = \"does-not-exist.gradle.kts\"")

        build("help")
    }

    @Test
    fun `build with groovy settings and kotlin-dsl build script succeeds`() {

        withFile("settings.gradle", """
            println 'Groovy DSL Settings'
        """)

        withBuildScript("""
            println("Kotlin DSL Build Script")
        """)

        assertThat(
            build("help").output,
            allOf(
                containsString("Groovy DSL Settings"),
                containsString("Kotlin DSL Build Script")))
    }

    @Test
    fun `build script can use jdk8 extensions`() {

        assumeJavaLessThan9()

        withBuildScript("""

            // without kotlin-stdlib-jdk8 we get:
            // > Retrieving groups by name is not supported on this platform.

            val regex = Regex("(?<bla>.*)")
            val groups = regex.matchEntire("abc")?.groups
            println("*" + groups?.get("bla")?.value + "*")

        """)

        assertThat(
            build("help").output,
            containsString("*abc*"))
    }

    @Test
    fun `settings script can use buildscript dependencies`() {

        withSettings("""
            buildscript {
                repositories { jcenter() }
                dependencies {
                    classpath("org.apache.commons:commons-lang3:3.6")
                }
            }

            println(org.apache.commons.lang3.StringUtils.reverse("Gradle"))
        """)

        assertThat(
            build("help").output,
            containsString("eldarG"))
    }

    @Test
    fun `script plugin can by applied to either Project or Settings`() {

        withFile("common.gradle.kts", """
            println("Target is Settings? ${"$"}{Settings::class.java.isAssignableFrom(this::class.java)}")
            println("Target is Project? ${"$"}{Project::class.java.isAssignableFrom(this::class.java)}")
        """)

        withSettings("""
            apply(from = "common.gradle.kts")
        """)

        assertThat(
            build("help").output,
            allOf(
                containsString("Target is Settings? true"),
                containsString("Target is Project? false")))

        withSettings("")
        withBuildScript("""
            apply(from = "common.gradle.kts")
        """)

        assertThat(
            build("help").output,
            allOf(
                containsString("Target is Settings? false"),
                containsString("Target is Project? true")))
    }

    @Test
    fun `can apply buildSrc plugin to Settings`() {

        withBuildSrc()

        withFile("buildSrc/src/main/groovy/my/SettingsPlugin.groovy", """
            package my

            import org.gradle.api.*
            import org.gradle.api.initialization.Settings

            class SettingsPlugin implements Plugin<Settings> {
                void apply(Settings settings) {
                    println("Settings plugin applied!")
                }
            }
        """)

        withSettings("""
            apply<my.SettingsPlugin>()
        """)

        assertThat(
            build("help").output,
            containsString("Settings plugin applied!"))
    }

    @Test
    fun `scripts can use the gradle script api`() {

        fun usageFor(target: String) = """

            logger.error("Error logging from $target")
            require(logging is LoggingManager, { "logging" })
            require(resources is ResourceHandler, { "resources" })

            require(relativePath("src/../settings.gradle.kts") == "settings.gradle.kts", { "relativePath(path)" })
            require(uri("settings.gradle.kts").toString().endsWith("settings.gradle.kts"), { "uri(path)" })
            require(file("settings.gradle.kts").isFile, { "file(path)" })
            require(files("settings.gradle.kts").files.isNotEmpty(), { "files(paths)" })
            require(fileTree(".").contains(file("settings.gradle.kts")), { "fileTree(path)" })
            require(copySpec {} != null, { "copySpec {}" })
            require(mkdir("some").isDirectory, { "mkdir(path)" })
            require(delete("some"), { "delete(path)" })
            require(delete {} != null, { "delete {}" })

        """

        withSettings(usageFor("Settings"))
        withBuildScript(usageFor("Project"))

        assertThat(
            build("help").error,
            allOf(
                containsString("Error logging from Settings"),
                containsString("Error logging from Project")))
    }

    @Test
    fun `automatically applies build scan plugin when --scan is provided on command-line and a script is applied in the buildscript block`() {

        withBuildScript("""
            buildscript {
                rootProject.apply(from = rootProject.file("gradle/dependencies.gradle.kts"))
            }
            buildScan {
                termsOfServiceUrl = "https://gradle.com/terms-of-service"
                termsOfServiceAgree = "yes"
            }
        """)
        withFile("gradle/dependencies.gradle.kts")
        canPublishBuildScan()
    }

    @Test
    fun `can use shorthand notation for bound callable references with inline functions in build scripts`() {

        withBuildScript("""
            fun foo(it: Any) = true

            // The inline modifier is important. This does not fail when this is no inline function.
            inline fun bar(f: (Any) -> Boolean) = print("*" + f(Unit) + "*")

            bar(::foo)
        """)

        assertThat(
            build().output,
            containsString("*true*"))
    }

    @Test
    fun `script compilation error message`() {

        val buildFile =
            withBuildScript("foo")

        assertThat(
            buildFailureOutput().normaliseLineSeparators(),
            containsString("""
                FAILURE: Build failed with an exception.

                * Where:
                Build file '${buildFile.canonicalPath}' line: 1

                * What went wrong:
                Script compilation error:

                  Line 1: foo
                          ^ Unresolved reference: foo

                1 error
            """.replaceIndent())
        )
    }

    @Test
    fun `multiline script compilation error message`() {

        withBuildScript("publishing { }")

        assertThat(
            buildFailureOutput().normaliseLineSeparators(),
            containsString("""
                * What went wrong:
                Script compilation errors:

                  Line 1: publishing { }
                          ^ Expression 'publishing' cannot be invoked as a function. The function 'invoke()' is not found

                  Line 1: publishing { }
                          ^ Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:${' '}
                              public val PluginDependenciesSpec.publishing: PluginDependencySpec defined in org.gradle.kotlin.dsl

                2 errors
            """.replaceIndent()))
    }

    @Test
    fun `multiple script compilation errors message`() {
        val buildFile = withBuildScript("println(foo)\n\n\n\n\nprintln(\"foo\").bar.bazar\n\n\n\nprintln(cathedral)")

        assertThat(
            buildFailureOutput().normaliseLineSeparators(),
            allOf(
                containsString("""
                    FAILURE: Build failed with an exception.

                    * Where:
                    Build file '${buildFile.canonicalPath}' line: 1

                    * What went wrong:
                    Script compilation errors:

                """.replaceIndent()),

                containsString("""
                |  Line 01: println(foo)
                |                   ^ Unresolved reference: foo
                """.trimMargin()),

                containsString("""
                |  Line 06: println("foo").bar.bazar
                |                          ^ Unresolved reference: bar
                """.trimMargin()),

                containsString("""
                |  Line 10: println(cathedral)
                |                   ^ Unresolved reference: cathedral
                """.trimMargin())))
    }

    @Test
    fun `given a remote buildscript, file paths are resolved relative to root project dir`() {

        val remoteScript = """

            apply(from = "./gradle/answer.gradle.kts")

        """

        withFile("gradle/answer.gradle.kts", """

            val answer by extra { "42" }

        """)

        MockWebServer().use { server ->

            server.enqueue(MockResponse().setBody(remoteScript))
            server.start()

            val remoteScriptUrl = server.url("/remote.gradle.kts")

            withBuildScript("""
                apply(from = "$remoteScriptUrl")
                val answer: String by extra
                println("*" + answer + "*")
            """)

            assert(build().output.contains("*42*"))
        }
    }

    @Test
    fun `given a script from a jar, file paths are resolved relative to root project dir`() {

        val scriptFromJar = """

            apply(from = "./gradle/answer.gradle.kts")

        """

        withZip(
            "fixture.jar",
            sequenceOf("common.gradle.kts" to scriptFromJar.toByteArray()))

        withFile("gradle/answer.gradle.kts", """

            val answer by extra { "42" }

        """)

        withBuildScript("""
            buildscript {
                dependencies { classpath(files("fixture.jar")) }
            }

            apply(from = project.buildscript.classLoader.getResource("common.gradle.kts").toURI())

            val answer: String by extra
            println("*" + answer + "*")
        """)

        assert(build().output.contains("*42*"))
    }

    @Test
    fun `script handler belongs to the current script`() {

        val init = withFile("some.init.gradle.kts", """
            println("init: ${'$'}{initscript.sourceFile}")
        """)

        val settings = withSettings("""
            println("settings: ${'$'}{buildscript.sourceFile}")
        """)

        val other = withFile("other.gradle.kts", """
            println("other: ${'$'}{buildscript.sourceFile}")
        """)

        val main = withBuildScript("""
            apply(from = "other.gradle.kts")
            println("main: ${'$'}{buildscript.sourceFile}")
        """)

        assertThat(
            build("-I", init.absolutePath, "help", "-q").output,
            containsMultiLineString("""
                init: ${init.absolutePath}
                settings: ${settings.absolutePath}
                other: ${other.absolutePath}
                main: ${main.absolutePath}
            """))
    }

    @Test
    fun `can cross configure buildscript`() {

        withClassJar("zero.jar", ZeroThought::class.java)
        withClassJar("light.jar", LightThought::class.java)
        withClassJar("deep.jar", DeepThought::class.java)

        val init = withFile("some.init.gradle.kts", """
            projectsLoaded {
                rootProject.buildscript {
                    dependencies {
                        classpath(files("zero.jar"))
                    }
                }
            }
        """)

        withSettings("""
            include("sub")
            gradle.projectsLoaded {
                rootProject.buildscript {
                    dependencies {
                        classpath(files("light.jar"))
                    }
                }
            }
        """)

        withBuildScript("""
            project(":sub") {
                buildscript {
                    dependencies {
                        classpath(files("../deep.jar"))
                    }
                }
            }
        """)

        withFile("sub/build.gradle.kts", """
            task("think") {
                doLast {
                    val zero = ${ZeroThought::class.qualifiedName}()
                    val light = ${LightThought::class.qualifiedName}()
                    val deep = ${DeepThought::class.qualifiedName}()
                    println("*" + zero.compute() + "*")
                    println("*" + light.compute() + "*")
                    println("*" + deep.compute() + "*")
                }
            }
        """)

        assertThat(
            build("-I", init.absolutePath, ":sub:think").output,
            containsMultiLineString("""
                *0*
                *23*
                *42*
            """))
    }

    @Test
    fun `given generic extension types they can be accessed and configured`() {

        assumeNonEmbeddedGradleExecuter()

        withDefaultSettingsIn("buildSrc")

        withFile("buildSrc/build.gradle.kts", """

            plugins {
                `kotlin-dsl`
            }

            gradlePlugin {
                plugins {
                    register("my") {
                        id = "my"
                        implementationClass = "my.MyPlugin"
                    }
                }
            }

            $repositoriesBlock
        """)

        withFile("buildSrc/src/main/kotlin/my/MyPlugin.kt", """
            package my

            import org.gradle.api.*
            import org.gradle.kotlin.dsl.*

            class Book(val name: String)

            class MyPlugin : Plugin<Project> {
                override fun apply(project: Project): Unit = project.run {
                    extensions.add(typeOf<MutableMap<String, String>>(), "mapOfString", mutableMapOf("foo" to "bar"))
                    extensions.add(typeOf<MutableMap<String, Int>>(), "mapOfInt", mutableMapOf("deep" to 42))
                    extensions.add(typeOf<NamedDomainObjectContainer<Book>>(), "books", container(Book::class))
                }
            }
        """)

        withBuildScript("""
            plugins {
                id("my")
            }

            configure<MutableMap<String, String>> {
                put("bazar", "cathedral")
            }
            require(the<MutableMap<String, String>>() == mapOf("foo" to "bar", "bazar" to "cathedral"))

            configure<MutableMap<String, Int>> {
                put("zero", 0)
            }
            require(the<MutableMap<String, Int>>() == mapOf("deep" to 42, "zero" to 0))

            require(the<MutableMap<*, *>>() == mapOf("foo" to "bar", "bazar" to "cathedral"))

            configure<NamedDomainObjectContainer<my.Book>> {
                create("The Dosadi experiment")
            }
            require(the<NamedDomainObjectContainer<my.Book>>().size == 1)
        """)

        executer.expectDeprecationWarning()

        build("help")
    }

    @Test
    fun `can use kotlin java8 inline-only methods`() {

        withBuildScript("""
            task("test") {
                doLast {
                    println(project.properties.getOrDefault("non-existent-property", "default-value"))
                }
            }
        """)

        assertThat(
            build("-q", "test").output.trim(),
            equalTo("default-value")
        )
    }
}
