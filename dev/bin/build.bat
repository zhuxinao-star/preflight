@echo off
rem ============================================================
rem PreFlight build script (Windows dev machine)
rem Prefers the JDK/Maven bundled under tools\ (process-level env only, no system changes);
rem tools\ is NOT published with the source, so on a fresh clone it falls back to java/mvn on PATH.
rem Sources live in src\preflight-server\ (import that pom into IntelliJ IDEA directly).
rem The produced jar is copied into dev\lib\preflight-server.jar, which is what start.bat runs.
rem Anchors (src / maven repo / settings / jar / java order) come from _env.bat.
rem Usage: dev\bin\build.bat                 (= compile)
rem        dev\bin\build.bat package         (= fat jar + copy to dev\lib)
rem Note: this repo currently has NO src\test - "package" compiles main sources only,
rem       so -DskipTests changes nothing here (kept for when tests are added back).
rem ============================================================
setlocal
call "%~dp0_env.bat"

rem mvn only honours JAVA_HOME (a java on PATH is not enough for it), so point it
rem at the bundled JDK when there is one; otherwise leave the caller's JAVA_HOME alone.
if defined PF_JDK_HOME set "JAVA_HOME=%PF_JDK_HOME%"
if defined PF_JDK_HOME if not exist "%PF_JDK_HOME%\bin\java.exe" (
    echo [ERROR] Bundled JDK is broken - %PF_JDK_HOME%\bin\java.exe not found.
    exit /b 1
)

echo Using JDK    : %PF_JAVA%
echo Using Maven  : %PF_MVN%
echo Using pom    : %PF_SRC%\pom.xml

rem The bundled offline dependency repo is not published; fall back to the machine default ~/.m2.
if exist "%PF_M2%\." (
    echo Local repo   : %PF_M2%
    "%PF_JAVA%" -version
    call "%PF_MVN%" -B -s "%PF_SETTINGS%" -Dmaven.repo.local="%PF_M2%" -f "%PF_SRC%\pom.xml" %*
) else (
    echo Local repo   : default ~/.m2 - %PF_M2% not present, first build needs network.
    "%PF_JAVA%" -version
    call "%PF_MVN%" -B -s "%PF_SETTINGS%" -f "%PF_SRC%\pom.xml" %*
)
if errorlevel 1 exit /b 1

rem Deployment artifacts stay in dev\: copy the freshly built jar to its single runtime location.
set "BUILT=%PF_SRC%\target\preflight-server-0.1.0-SNAPSHOT.jar"
if exist "%BUILT%" (
    copy /Y "%BUILT%" "%PF_JAR%" >nul
    if errorlevel 1 (
        echo [WARN] built ok but copy to dev\lib failed - the running service holds the jar. Run dev\bin\stop.bat, then re-run this.
    ) else (
        echo [OK] deployed -^> %PF_JAR%
    )
)
endlocal
