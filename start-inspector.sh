#!/usr/bin/env bash
# JVM Memory & Leak Inspector: starts the analyzer next to the WildFly / Hazelcast JVMs it inspects.
#
#   ./start-inspector.sh                       UI on http://127.0.0.1:8080
#   INSPECTOR_PORT=9090 ./start-inspector.sh   another port
#   ./start-inspector.sh --inspector.snapshots.work-dir=/data/inspector   any Spring Boot argument
#
# Run it as the OS user that owns the target JVMs (e.g. `sudo -u wildfly ./start-inspector.sh`): the HotSpot attach
# mechanism only connects to processes of the same user. It needs a JDK, not a JRE (module jdk.attach).
#
# Environment:
#   JAVA_HOME / INSPECTOR_JAVA   JDK 17+ to use (default: java on PATH)
#   INSPECTOR_JAR                jar to run (default: next to this script, then backend/target)
#   INSPECTOR_XMS / INSPECTOR_XMX  analyzer heap, default 256m / 1024m. Heap dumps are indexed in memory-mapped
#                                files on disk, not in this heap, so 1 GB analyses dumps of several GB.
#   INSPECTOR_PORT               HTTP port (default 8080; the UI binds to 127.0.0.1 only)
#   INSPECTOR_OPTS               extra JVM options
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -n "${INSPECTOR_JAVA:-}" ]]; then
  java="$INSPECTOR_JAVA"
elif [[ -n "${JAVA_HOME:-}" ]]; then
  java="$JAVA_HOME/bin/java"
else
  java="$(command -v java || true)"
fi
if [[ -z "$java" || ! -x "$java" ]]; then
  echo "ERROR: java not found. Install JDK 17+ or set JAVA_HOME." >&2
  exit 1
fi

spec="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | head -n1)"
major="${spec%%.*}"
if [[ -z "$major" || "$major" -lt 17 ]]; then
  echo "ERROR: Java 17+ required, $java is ${spec:-unknown}." >&2
  exit 1
fi
if ! "$java" --list-modules 2>/dev/null | grep -q '^jdk\.attach@'; then
  echo "ERROR: $java is a JRE without module jdk.attach; attaching to local JVMs needs a full JDK." >&2
  exit 1
fi

jar="${INSPECTOR_JAR:-}"
if [[ -z "$jar" ]]; then
  for candidate in "$here/jvm-leak-inspector.jar" "$here/backend/target/jvm-leak-inspector.jar"; do
    if [[ -f "$candidate" ]]; then
      jar="$candidate"
      break
    fi
  done
fi
if [[ -z "$jar" || ! -f "$jar" ]]; then
  echo "ERROR: jvm-leak-inspector.jar not found. Build it: (cd backend && mvn clean package)" >&2
  exit 1
fi

if [[ "$(id -u)" -eq 0 ]]; then
  echo "WARNING: running as root. JVMs of other users are usually not attachable; prefer: sudo -u <wildfly-user> $0" >&2
fi

jvm_opts=(
  "-Xms${INSPECTOR_XMS:-256m}"
  "-Xmx${INSPECTOR_XMX:-1024m}"
  -XX:+UseG1GC
  -XX:+ExitOnOutOfMemoryError
  -Dfile.encoding=UTF-8
  -Djava.awt.headless=true
  # Java 17 module system: attach provider, JMX internals and the NIO buffers of the heap-dump index.
  # The analyzer uses only public APIs today; the opens keep third-party code (NetBeans profiler, JMX connectors)
  # from failing with InaccessibleObjectException on stricter JDK builds.
  --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
  --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED
  --add-opens java.management/sun.management=ALL-UNNAMED
  --add-exports java.management/sun.management=ALL-UNNAMED
  --add-opens java.management/javax.management=ALL-UNNAMED
  --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
  --add-opens java.base/java.nio=ALL-UNNAMED
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED
)
# shellcheck disable=SC2206 # INSPECTOR_OPTS is a list of options on purpose
extra=(${INSPECTOR_OPTS:-})

echo "Starting JVM Leak Inspector ($jar) with $java (Java $spec) on http://127.0.0.1:${INSPECTOR_PORT:-8080}"
exec "$java" "${jvm_opts[@]}" "${extra[@]}" -jar "$jar" --server.port="${INSPECTOR_PORT:-8080}" "$@"
