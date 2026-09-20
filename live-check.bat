@echo off
rem ============================================================================
rem  CraftGraph live check -- just double-click this file.
rem
rem  Two on-purpose decisions, both learned the hard way:
rem
rem  1. **ASCII only.** cmd.exe parses a .bat using the *console* codepage (GBK on
rem     Chinese Windows), so Chinese written as UTF-8 turns into mojibake AND
rem     breaks the parsing itself ("not a recognized command" on random
rem     fragments). `chcp 65001` does not help -- by then the file's own bytes were
rem     already misread. Chinese output comes from Node below, which is exactly the
rem     path you get when running `npm run live` by hand.
rem
rem  2. **No chcp, no delayed expansion.** chcp would change how Node encodes its
rem     output, so leaving the console alone keeps this identical to the manual
rem     command. Plain `goto` flow avoids `!VAR!` surprises inside blocks.
rem
rem  Line endings: .gitattributes forces CRLF for *.bat (a LF-only .bat can break
rem  some cmd.exe versions when it hits labels/goto).
rem ============================================================================

cd /d "%~dp0"

echo ============================================================
echo   CraftGraph live check
echo ============================================================
echo.

rem ---------------------------------------------------------------- 1. the jar
set JAR=
for %%f in ("%~dp0mod-1.20.1\build\libs\craftgraph-*-mc1.20.1.jar") do set JAR=%%f

echo [1/5] Mod jar built in this repo:
if not defined JAR goto :nojar
echo       %JAR%
echo       ^(make sure this exact file is the one in your pack's mods folder --
echo        replace any older craftgraph-*.jar there, 1.21.1 jars will not load^)
goto :jarok

:nojar
echo       [X] none found. Build it first:
echo             cd mod-1.20.1
echo             gradlew build

:jarok
echo.

rem ---------------------------------------------------------------- 2. node
where node >nul 2>nul
if errorlevel 1 goto :nonode

echo [2/5] Node.js:
node -v
echo.

rem ---------------------------------------------------------------- 3. deps
cd /d "%~dp0mcp-server"
if exist node_modules goto :depsok

echo [3/5] First run: installing dependencies ^(npm install, may take a minute^)...
call npm install
if errorlevel 1 goto :npmfail
goto :depsdone

:depsok
echo [3/5] Dependencies ready

:depsdone
echo.

rem ---------------------------------------------------------------- 4. game
rem The mod writes a "discovery file" on startup; the MCP server uses it to find
rem the port and token. Missing = game not running / not in a world / mod absent.
echo [4/5] Looking for the running game
set FOUNDFILE=%USERPROFILE%\.craftgraph\bridge.json
if not exist "%FOUNDFILE%" goto :nogame

echo       Discovery file: %FOUNDFILE%
echo       Contents:
type "%FOUNDFILE%"
echo.
goto :check

:nogame
echo       [X] Not found: %FOUNDFILE%
echo.
echo       That means the bridge is not up yet. Check:
echo         1. Minecraft is RUNNING and you are IN A WORLD
echo            ^(recipe data only exists after a world is loaded^)
echo         2. the mods folder has craftgraph-*-mc1.20.1.jar for a 1.20.1 pack
echo         3. search the game log for these CraftGraph lines:
echo              "CraftGraph bridge started on /127.0.0.1:xxxxx"   = bridge up
echo              "CraftGraph snapshot rebuilt: N recipes ..."       = recipes read
echo            game log: ^<game dir^>\logs\latest.log
echo.
echo       If the game IS running, keep going -- the check below looks again itself.
pause

rem ---------------------------------------------------------------- 5. check
:check
echo.
echo [5/5] Running npm run live ...
echo ------------------------------------------------------------
call npm run live
if errorlevel 1 goto :failed

echo ------------------------------------------------------------
echo.
echo RESULT: all checks passed.
goto :send

:failed
echo ------------------------------------------------------------
echo.
echo RESULT: some checks FAILED ^(the lines marked with the cross^).

:send
echo.
echo Send the whole output above back, including the line that says which game
echo version / loader was detected and how many recipes were read.
goto :end

:nonode
echo [X] node not found. The MCP server needs Node.js 20 or newer: https://nodejs.org
goto :end

:npmfail
echo [X] npm install failed. Send the output above back.

:end
echo.
echo ============================================================
pause
