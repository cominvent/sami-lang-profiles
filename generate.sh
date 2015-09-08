#!/usr/bin/env bash
export MAVEN_OPTS="-Dfile.encoding=UTF-8"; mvn compile exec:java -Dexec.args="$*"