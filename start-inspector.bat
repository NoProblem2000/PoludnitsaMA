@echo off
rem JVM Memory & Leak Inspector: starts the analyzer next to the WildFly / Hazelcast JVMs it inspects.
rem
rem   start-inspector.bat                        UI on http://127.0.0.1:8080
rem   set INSPECTOR_PORT=9090 ^& start-inspector.bat
rem   start-inspector.bat --inspector.snapshots.work-dir=D:\inspector   any Spring Boot argument
rem
rem Run it as the Windows user that runs the target JVMs (a WildFly service under LocalSystem needs an elevated
rem prompt or the same service account). It needs a JDK, not a JRE (module jdk.attach).
rem
rem Environment: JAVA_HOME / INSPECTOR_JAVA, INSPECTOR_JAR, INSPECTOR_XMS / INSPECTOR_XMX (256m / 1024m),
rem INSPECTOR_PORT (8080), INSPECTOR_OPTS (extra JVM options). Heap dumps are indexed in memory-mapped files on
rem disk, not in the analyzer heap, so 1 GB analyses dumps of several GB.
setlocal EnableExtensions DisableDelayedExpansion

set "HERE=%~dp0"

if defined INSPECTOR_JAVA (
  set "JAVA=%INSPECTOR_JAVA%"
) else if defined JAVA_HOME (
  set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA="
  for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%J"
)
if not defined JAVA (
  echo ERROR: java not found. Install JDK 17+ or set JAVA_HOME. 1>&2
  exit /b 1
)
if not exist "%JAVA%" (
  echo ERROR: "%JAVA%" does not exist. 1>&2
  exit /b 1
)

set "SPEC="
for /f "tokens=2 delims==" %%V in ('call "%JAVA%" -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.specification.version"') do set "SPEC=%%V"
if defined SPEC set "SPEC=%SPEC: =%"
for /f "tokens=1 delims=." %%M in ("%SPEC%") do set "MAJOR=%%M"
if not defined MAJOR (
  echo ERROR: cannot determine the Java version of "%JAVA%". 1>&2
  exit /b 1
)
if %MAJOR% LSS 17 (
  echo ERROR: Java 17+ required, "%JAVA%" is %SPEC%. 1>&2
  exit /b 1
)
"%JAVA%" --list-modules 2>nul | findstr /b /c:"jdk.attach@" >nul
if errorlevel 1 (
  echo ERROR: "%JAVA%" is a JRE without module jdk.attach; attaching to local JVMs needs a full JDK. 1>&2
  exit /b 1
)

set "JAR=%INSPECTOR_JAR%"
if not defined JAR if exist "%HERE%jvm-leak-inspector.jar" set "JAR=%HERE%jvm-leak-inspector.jar"
if not defined JAR if exist "%HERE%backend\target\jvm-leak-inspector.jar" set "JAR=%HERE%backend\target\jvm-leak-inspector.jar"
if not defined JAR (
  echo ERROR: jvm-leak-inspector.jar not found. Build it: cd backend ^&^& mvn clean package 1>&2
  exit /b 1
)

if not defined INSPECTOR_XMS set "INSPECTOR_XMS=256m"
if not defined INSPECTOR_XMX set "INSPECTOR_XMX=1024m"
if not defined INSPECTOR_PORT set "INSPECTOR_PORT=8080"

rem Java 17 module system: attach provider, JMX internals and the NIO buffers of the heap-dump index. The analyzer
rem uses only public APIs today; the opens keep third-party code (NetBeans profiler, JMX connectors) from failing
rem with InaccessibleObjectException on stricter JDK builds.
set JVM_OPTS=-Xms%INSPECTOR_XMS% -Xmx%INSPECTOR_XMX% -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError ^
 -Dfile.encoding=UTF-8 -Djava.awt.headless=true ^
 --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED ^
 --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED ^
 --add-opens java.management/sun.management=ALL-UNNAMED ^
 --add-exports java.management/sun.management=ALL-UNNAMED ^
 --add-opens java.management/javax.management=ALL-UNNAMED ^
 --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED ^
 --add-opens java.base/java.nio=ALL-UNNAMED ^
 --add-opens java.base/sun.nio.ch=ALL-UNNAMED

echo Starting JVM Leak Inspector (%JAR%) with %JAVA% (Java %SPEC%) on http://127.0.0.1:%INSPECTOR_PORT%
"%JAVA%" %JVM_OPTS% %INSPECTOR_OPTS% -jar "%JAR%" --server.port=%INSPECTOR_PORT% %*
exit /b %ERRORLEVEL%
