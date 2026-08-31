import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsStartScriptSupportTest {

    @Test
    fun `launcher selects utf8 and restores the original console code page`() {
        val source = """
            setlocal EnableExtensions

            :execute
            endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" Main %* & call :exitWithErrorLevel

            :exitWithErrorLevel
            "%COMSPEC%" /c exit %ERRORLEVEL%
        """.trimIndent()

        val configured = configureWindowsUtf8StartScript(source)

        assertContains(configured, "chcp 65001 >NUL")
        assertContains(
            configured,
            "for /F \"tokens=*\" %%G in ('chcp') do for %%H in (%%G) do set \"CLAUDE_CODE_ORIGINAL_CODE_PAGE=%%H\"",
        )
        assertFalse(configured.contains("tokens=2 delims=: "))
        assertFalse(configured.contains("call :exitWithErrorLevel"))
        assertContains(configured, "\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS% -classpath")
        assertContains(configured, "set \"CLAUDE_CODE_EXIT_CODE=%ERRORLEVEL%\"")
        assertContains(configured, "chcp %CLAUDE_CODE_ORIGINAL_CODE_PAGE% >NUL")
        assertContains(configured, "endlocal & \"%COMSPEC%\" /c exit %CLAUDE_CODE_EXIT_CODE%")
        assertEquals(configured, configureWindowsUtf8StartScript(configured))
    }
}
