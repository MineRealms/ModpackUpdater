#!/bin/sh
# Society 鏁村悎鍖呮洿鏂板櫒 - macOS/Linux 鍛戒护琛屽惎鍔ㄨ剼鏈?DIR="$(cd "$(dirname "$0")" && pwd)"
if command -v java >/dev/null 2>&1; then
    JAVA=java
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif [ -x "/usr/libexec/java_home" ]; then
    JAVA="$("/usr/libexec/java_home" -v 17)/bin/java"
else
    echo "鏈壘鍒?Java 17+锛岃鍏堝畨瑁咃紙macOS: brew install openjdk@17锛?
    exit 1
fi
exec "$JAVA" -jar "$DIR/ModpackUpdater-all.jar" "$@"
