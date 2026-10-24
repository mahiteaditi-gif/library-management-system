#!/bin/sh
# Compiles and starts Library Management System (needs JDK 17 or newer)
cd "$(dirname "$0")" || exit 1
mkdir -p out
javac -d out src/lms/*.java || exit 1
echo "Starting... open http://localhost:8080 in your browser"
java -cp "out:lib/sqlite-jdbc.jar" lms.Main
