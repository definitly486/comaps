#!/bin/sh
JAVA_TOOL_OPTIONS="-Dos.name=Linux" ./gradlew  -Parm64 assembleRelease --no-daemon "$@"