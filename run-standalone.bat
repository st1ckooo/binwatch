@echo off
rem BIN Watch standalone (no Minecraft). Builds the jar on first use, then runs it. Options are passed through,
rem e.g.  run-standalone.bat --dry-run
setlocal
cd /d "%~dp0"
if not exist build\libs\binwatch-standalone-1.0.0.jar (
	call gradlew.bat standaloneJar || exit /b 1
)
java -jar build\libs\binwatch-standalone-1.0.0.jar %*
