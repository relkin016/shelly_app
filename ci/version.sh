#!/usr/bin/env bash
# Печатает версию приложения из app/build.gradle.kts (val appVersion = "X.Y.Z").
sed -nE 's/^val appVersion = "([0-9]+\.[0-9]+\.[0-9]+)".*/\1/p' "$(dirname "$0")/../app/build.gradle.kts"
