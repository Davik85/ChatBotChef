package app

import app.db.ProcessedUpdatesRepoTest
import app.db.UsersRepoBlockedTest
import app.db.UsersRepoStatsTest
import app.llm.OpenAIClientTest
import app.testsupport.AfterTest
import app.testsupport.BeforeTest
import app.testsupport.Test as KotlinTest
import org.junit.After
import org.junit.Before
import org.junit.Test as JunitTest
import java.lang.reflect.Method

object ManualTestRunner {
    private val beforeAnnotations = setOf(BeforeTest::class.java, Before::class.java)
    private val afterAnnotations = setOf(AfterTest::class.java, After::class.java)
    private val testAnnotations = setOf(KotlinTest::class.java, JunitTest::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val testClasses = listOf(
            ProcessedUpdatesRepoTest::class.java,
            UsersRepoBlockedTest::class.java,
            UsersRepoStatsTest::class.java,
            OpenAIClientTest::class.java
        )
        var failures = 0
        var executed = 0
        testClasses.forEach { clazz ->
            val instance = clazz.getDeclaredConstructor().newInstance()
            val methods = clazz.declaredMethods.filter { it.parameterCount == 0 }
            val beforeMethods = methods.filter { it.hasAnyAnnotation(beforeAnnotations) }
            val afterMethods = methods.filter { it.hasAnyAnnotation(afterAnnotations) }
            val tests = methods.filter { it.hasAnyAnnotation(testAnnotations) }
            tests.forEach { testMethod ->
                executed++
                try {
                    beforeMethods.forEach { it.invokeSafe(instance) }
                    testMethod.invokeSafe(instance)
                    println("PASS ${clazz.simpleName}.${testMethod.name}")
                } catch (t: Throwable) {
                    failures++
                    val cause = t.cause ?: t
                    System.err.println("FAIL ${clazz.simpleName}.${testMethod.name}: ${cause.message}")
                } finally {
                    afterMethods.forEach { after ->
                        runCatching { after.invokeSafe(instance) }
                            .onFailure { err -> System.err.println("TEARDOWN ERR ${clazz.simpleName}.${after.name}: ${err.message}") }
                    }
                }
            }
        }
        if (failures > 0) {
            throw IllegalStateException("$failures tests failed out of $executed")
        }
        println("All $executed tests passed")
    }

    private fun Method.hasAnyAnnotation(targets: Set<Class<out Annotation>>): Boolean =
        targets.any { this.isAnnotationPresent(it) }

    private fun Method.invokeSafe(target: Any) {
        isAccessible = true
        invoke(target)
    }
}
