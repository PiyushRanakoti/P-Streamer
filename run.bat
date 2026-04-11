@echo off
echo ============================================
echo   AudioStream — Launcher
echo ============================================
echo.

:: Compile server
echo [1/2] Compiling server...
cd server
javac AudioServer.java QrFetcher.java
if errorlevel 1 (
    echo ERROR: Server compile failed!
    pause & exit /b 1
)

:: Compile sender (so AudioServer can launch it without needing javac at runtime)
echo [2/2] Compiling sender...
cd ..\sender
javac AudioSender.java
if errorlevel 1 (https://github.com/PiyushRanakoti/P-Streamer.git
    echo ERROR: Sender compile failed!
    pause & exit /b 1
)
cd ..

echo.
echo Compilation successful!
echo.

:: Launch ONLY the server GUI — it will start AudioSender internally (no extra windows)
echo Starting AudioStream Server...
java -cp server AudioServer

:: When the GUI closes, we're done — no cleanup needed
echo.
echo AudioStream stopped.