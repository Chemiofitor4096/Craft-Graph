@echo off
rem ============================================================================
rem  CraftGraph release -- push main, tag the version, publish to KessokuMaven.
rem
rem  Double-click to run, or from a shell:  release.bat --dry-run
rem
rem ASCII only, on purpose: cmd.exe parses a .bat using the *console* codepage (GBK on
rem  (GBK on Chinese Windows), so UTF-8 Chinese both renders as mojibake and
rem  breaks parsing itself. `chcp 65001` inside the file does not help -- by then
rem  the bytes were already misread. Same reason as live-check.bat.
rem
rem  Why the checks before doing anything: every action here is outward-facing and
rem  hard to undo (a pushed tag triggers a public Release; a published Maven
rem  version cannot be taken back). So this script refuses to act when the repo is
rem  not in the state the release procedure requires, instead of doing half of it.
rem ============================================================================

setlocal
cd /d "%~dp0"

set DRYRUN=0
if /i "%~1"=="--dry-run" set DRYRUN=1
if /i "%~1"=="dry" set DRYRUN=1

set FAILED=0

echo ============================================================
echo   CraftGraph release
if "%DRYRUN%"=="1" echo   MODE: DRY RUN -- nothing will be pushed, tagged or published
echo ============================================================
echo.

rem ---------------------------------------------------------------- 0. tools
where git >nul 2>nul
if errorlevel 1 goto :nogit
echo [1/7] git
git --version
echo.

rem ---------------------------------------------------------------- 1. branch / tree
echo [2/7] Repository state
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set BRANCH=%%b
if not "%BRANCH%"=="main" goto :badbranch

rem git diff only sees tracked files, so a freshly added source file is not "dirty" --
rem and that is exactly the file a tag would silently leave out. Porcelain includes it.
git status --porcelain > "%TEMP%\cg-status.txt"
for %%A in ("%TEMP%\cg-status.txt") do if %%~zA GTR 0 goto :dirty
echo       branch: main, working tree clean

git fetch -q origin
if errorlevel 1 goto :noremote
for /f "delims=" %%l in ('git rev-parse HEAD') do set LOCAL=%%l
for /f "delims=" %%r in ('git rev-parse origin/main') do set REMOTE=%%r
if not "%LOCAL%"=="%REMOTE%" goto :diverged
echo       local main == origin/main (%LOCAL:~0,7%)
echo.
goto :version

:dirty
echo       [X] Working tree is not clean. Commit (or stash) first -- this script
echo           deliberately does not invent a commit message for you.
echo           Uncommitted changes:
git status --short
goto :abort

:badbranch
echo       [X] On branch "%BRANCH%", not main. Releases are cut from main.
goto :abort

:diverged
echo       [X] local main (%LOCAL:~0,7%) != origin/main (%REMOTE:~0,7%)
echo           Push or pull first, then run this again.
goto :abort

:noremote
echo       [X] git fetch failed -- cannot reach the remote. Check the proxy / network.
goto :abort

rem ---------------------------------------------------------------- 2. version
:version
echo [3/7] Version consistency
for /f "tokens=2 delims==" %%v in ('findstr /b "mod_version=" mod\gradle.properties') do set VER=%%v
if not defined VER goto :noversion
echo       mod/gradle.properties      mod_version=%VER%

findstr /c:"\"version\": \"%VER%\"" mcp-server\package.json >nul
if errorlevel 1 goto :mismatch
echo       mcp-server/package.json    %VER%  ok

findstr /c:"const VERSION = \"%VER%\";" mcp-server\src\index.ts >nul
if errorlevel 1 goto :mismatch
echo       mcp-server/src/index.ts    %VER%  ok

rem mod-1.20.1 reads mod_version from mod/gradle.properties, so it stays consistent
rem by construction -- stated here so nobody thinks a place was missed.
echo       mod-1.20.1                 reads mod_version from mod/ (same %VER%)
echo.
goto :conflicts

:noversion
echo       [X] Could not read mod_version from mod\gradle.properties
goto :abort

:mismatch
echo       [X] Version mismatch: mod_version=%VER% but one of these does not match:
echo             mcp-server/package.json
echo             mcp-server/src/index.ts
echo           All three must be the same number (the product is one thing).
goto :abort

rem ---------------------------------------------------------------- 3. not already out
:conflicts
echo [4/7] Has this version been published already?
git ls-remote --tags origin "v%VER%" >"%TEMP%\cg-tags.txt" 2>nul
if errorlevel 1 goto :noremote
findstr /c:"v%VER%" "%TEMP%\cg-tags.txt" >nul
if not errorlevel 1 goto :tagexists
echo       git tag v%VER%                 not present  ok

curl -s -o "%TEMP%\cg-maven.xml" -w "%%{http_code}" "https://maven.kessokuteatime.work/releases/dev/craftgraph/craftgraph/maven-metadata.xml" >"%TEMP%\cg-http.txt" 2>nul
set /p HTTP=<"%TEMP%\cg-http.txt"
if not "%HTTP%"=="200" goto :mavenunknown
findstr /c:"<version>%VER%</version>" "%TEMP%\cg-maven.xml" >nul
if not errorlevel 1 goto :mavenexists
echo       KessokuMaven %VER%             not present  ok
curl -s -o "%TEMP%\cg-maven2.xml" -w "%%{http_code}" "https://maven.kessokuteatime.work/releases/dev/craftgraph/craftgraph-mc1.20.1/maven-metadata.xml" >"%TEMP%\cg-http2.txt" 2>nul
set /p HTTP=<"%TEMP%\cg-http2.txt"
if not "%HTTP%"=="200" goto :mavenunknown
findstr /c:"<version>%VER%</version>" "%TEMP%\cg-maven2.xml" >nul
if not errorlevel 1 goto :mavenexists
echo       KessokuMaven mc1.20.1 %VER%             not present  ok
echo.
goto :creds

:tagexists
echo       [X] Tag v%VER% already exists on origin.
echo           Published version numbers are never reused: the same number must not
echo           mean two different sets of bytes. Bump mod_version and run again.
goto :abort

:mavenexists
echo       [X] KessokuMaven already has %VER% (dev.craftgraph:craftgraph).
echo           Same rule as the tag: bump mod_version instead of reusing it.
goto :abort

:mavenunknown
echo       [!] Could not read KessokuMaven metadata (HTTP %HTTP%). Continuing, but
echo           if %VER% is already published you will be republishing the same number.
goto :creds

rem ---------------------------------------------------------------- 4. credentials
:creds
echo [5/7] Publish credentials
if not defined K_MAVEN_USERNAME goto :nocreds
if not defined K_MAVEN_TOKEN goto :nocreds
echo       K_MAVEN_USERNAME is set, K_MAVEN_TOKEN is set
echo.
goto :plan

:nocreds
echo       [X] K_MAVEN_USERNAME / K_MAVEN_TOKEN not set in this session.
echo           The Maven publish step will fail without them.
echo           PowerShell:  $env:K_MAVEN_USERNAME='...'; $env:K_MAVEN_TOKEN='...'
goto :abort

rem ---------------------------------------------------------------- 5. plan
:plan
echo [6/7] This is what will happen:
echo         1. git push origin main
echo         2. git tag v%VER%          (on the commit just pushed)
echo         3. git push origin v%VER%  -^> triggers the Release workflow:
echo               builds both jars (1.21.1 + 1.20.1), checks their metadata,
echo               attaches 4 files to the GitHub Release
echo         4. cd mod          ^&^& gradlew publish        (KessokuMaven, 1.21.1)
echo         5. cd mod-1.20.1   ^&^& gradlew publish        (KessokuMaven, craftgraph-mc1.20.1)
echo.
echo       The tag step starts CI; the two publish steps run locally while CI builds.
echo.
if "%DRYRUN%"=="1" goto :dryend

echo [7/7] Proceed?
choice /c YN /n /m "      Press Y to release %VER% to the public, N to abort: "
if errorlevel 2 goto :aborted
echo.

rem ---------------------------------------------------------------- 6. push main
echo --- git push origin main
git push origin main
if errorlevel 1 goto :pushfailed
echo.

rem ---------------------------------------------------------------- 7. tag
echo --- git tag v%VER%
git tag "v%VER%"
if errorlevel 1 goto :tagfailed
echo --- git push origin v%VER%
git push origin "v%VER%"
if errorlevel 1 goto :tagpushfailed
echo.

rem ---------------------------------------------------------------- 8. publish
echo --- publish 1/2: mod (1.21.1)
pushd mod
call gradlew publish
set RC1=%errorlevel%
popd
echo.

echo --- publish 2/2: mod-1.20.1
pushd mod-1.20.1
call gradlew publish
set RC2=%errorlevel%
popd
echo.

if not "%RC1%"=="0" goto :pubfailed
if not "%RC2%"=="0" goto :pubfailed

echo ============================================================
echo   Done: main pushed, v%VER% tagged and pushed, both modules published.
echo   The Release workflow is building -- check the Actions tab in a few minutes.
echo ============================================================
goto :end

:dryend
echo [7/7] DRY RUN -- stopping here. Nothing was pushed, tagged or published.
goto :end

rem ---------------------------------------------------------------- failures
:pushfailed
echo [X] git push origin main failed (above). Nothing else was done.
goto :abort

:tagfailed
echo [X] git tag v%VER% failed -- does a local tag with that name already exist?
echo     Check with: git tag -l "v%VER%"
goto :abort

:tagpushfailed
echo [X] The tag exists locally but could not be pushed (network?).
echo     main is already pushed. Retry with: git push origin v%VER%
goto :abort

:pubfailed
echo [X] A Maven publish failed (mod=%RC1%, mod-1.20.1=%RC2%).
echo     The tag and the GitHub Release are already out, so the GitHub-side release
echo     is not affected. Retry the failed module by hand:
echo       cd mod ^&^& gradlew publish        (or cd mod-1.20.1)
goto :abort

:aborted
echo Aborted. Nothing was released.
goto :end

:nogit
echo [X] git not found in PATH.
goto :end

:abort
set FAILED=1

:end
echo.
del "%TEMP%\cg-tags.txt" "%TEMP%\cg-maven.xml" "%TEMP%\cg-http.txt" "%TEMP%\cg-maven2.xml" "%TEMP%\cg-http2.txt" "%TEMP%\cg-status.txt" 2>nul
echo ============================================================
pause
