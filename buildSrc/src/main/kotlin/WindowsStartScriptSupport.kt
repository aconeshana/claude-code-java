private const val utf8LauncherMarker = "@rem Keep Java UTF-8 output aligned with the Windows console"

fun configureWindowsUtf8StartScript(script: String): String {
    if (script.contains(utf8LauncherMarker)) return script

    val newline = if (script.contains("\r\n")) "\r\n" else "\n"
    val scopeAnchor = "setlocal EnableExtensions"
    val executionPrefix = "endlocal & \"%JAVA_EXE%\""
    val exitCall = "& call :exitWithErrorLevel"
    val exitCommand = "\"%COMSPEC%\" /c exit %ERRORLEVEL%"
    check(script.contains(scopeAnchor)) { "Windows start script is missing the setlocal anchor" }
    check(script.contains(executionPrefix)) { "Windows start script is missing the Java execution prefix" }
    check(script.contains(exitCall)) { "Windows start script is missing the exit handler call" }
    check(script.contains(exitCommand)) { "Windows start script is missing the exit command" }

    val codePageSetup = listOf(
        utf8LauncherMarker,
        "for /F \"tokens=*\" %%G in ('chcp') do for %%H in (%%G) do set \"CLAUDE_CODE_ORIGINAL_CODE_PAGE=%%H\"",
        "chcp 65001 >NUL",
    ).joinToString(newline)
    val exitHandler = listOf(
        "set \"CLAUDE_CODE_EXIT_CODE=%ERRORLEVEL%\"",
        "if defined CLAUDE_CODE_ORIGINAL_CODE_PAGE chcp %CLAUDE_CODE_ORIGINAL_CODE_PAGE% >NUL",
        "endlocal & \"%COMSPEC%\" /c exit %CLAUDE_CODE_EXIT_CODE%",
    ).joinToString(newline)
    val generatedExecutionComment = listOf(
        "@rem endlocal doesn't take effect until after the line is parsed and variables are expanded",
        "@rem which allows us to clear the local environment before executing the java command",
    ).joinToString(newline)
    val utf8ExecutionComment =
        "@rem Keep local state through execution so the caller's console code page can be restored"

    return script
        .replaceFirst(scopeAnchor, "$scopeAnchor$newline$newline$codePageSetup")
        .replaceFirst(generatedExecutionComment, utf8ExecutionComment)
        .replaceFirst(executionPrefix, "\"%JAVA_EXE%\"")
        .replaceFirst(" $exitCall", "")
        .replaceFirst(exitCommand, exitHandler)
}
