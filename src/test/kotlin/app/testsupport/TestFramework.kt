package app.testsupport

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class BeforeTest

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AfterTest

fun assertTrue(actual: Boolean, message: String? = null) {
    if (!actual) throw AssertionError(message ?: "Expected true but was false")
}

fun assertFalse(actual: Boolean, message: String? = null) {
    if (actual) throw AssertionError(message ?: "Expected false but was true")
}

fun assertEquals(expected: Any?, actual: Any?, message: String? = null) {
    if (expected != actual) {
        throw AssertionError(message ?: "Expected <$expected> but was <$actual>")
    }
}

fun <T> assertNotNull(actual: T?, message: String? = null): T {
    if (actual == null) {
        throw AssertionError(message ?: "Expected value to be non-null")
    }
    return actual
}
