@echo off
if exist "%USERPROFILE%\.jdks\openjdk-25.0.2" (
    set "JAVA_HOME=%USERPROFILE%\.jdks\openjdk-25.0.2"
)
set "MAVEN_CMD=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
    for /r "%USERPROFILE%\.m2\wrapper\dists" %%f in (mvn.cmd) do (
        set "MAVEN_CMD=%%f"
        goto :found
    )
)
:found
"%MAVEN_CMD%" %*
