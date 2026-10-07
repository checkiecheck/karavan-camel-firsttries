#!/usr/bin/env bash
# check-jbang-deps.sh — verifieer dat elke camel.jbang.dependencies-component
# in application.properties ook als camel-quarkus-<component> in pom.xml staat.
# Zo voorkom je: "werkt in Karavan/JBang wel, container faalt".
# Gebruik: ./check-jbang-deps.sh <integratie-dir>  (of zonder arg: alle dirs)

set -eu

# Componenten die in Camel core zitten en dus geen aparte quarkus-artifact nodig hebben
CORE_COMPONENTS="core|bean|direct|log|mock|setBody|setHeader|removeHeaders|choice|seda|timer|file|stub"

fail=0

check_dir() {
  local dir="$1"
  local props="$dir/application.properties"
  local pom="$dir/pom.xml"

  # Geen JBang-lijst? Dan niets te checken (stilzwijgend ok)
  [ -f "$props" ] || return 0
  local deps
  deps=$(grep -E '^camel\.jbang\.dependencies=' "$props" | head -1 | cut -d= -f2-)
  [ -n "$deps" ] || return 0
  [ -f "$pom" ] || { echo "FAIL $dir: camel.jbang.dependencies gevuld maar geen pom.xml"; fail=1; return 0; }

  # IFS op komma; strips 'camel:' prefix en whitespace
  local IFS=','
  for entry in $deps; do
    local comp
    comp=$(echo "$entry" | sed 's/^ *camel://' | tr -d ' ')
    [ -n "$comp" ] || continue

    # core-componenten hebben geen apart artifact nodig
    if echo "$comp" | grep -qE "^(${CORE_COMPONENTS})$"; then
      continue
    fi

    if grep -q "camel-quarkus-${comp}<" "$pom"; then
      echo "OK   $dir: camel:${comp} -> camel-quarkus-${comp}"
    else
      echo "FAIL $dir: camel:${comp} in jbang-lijst maar camel-quarkus-${comp} ONTBREEKT in pom.xml"
      fail=1
    fi
  done
}

if [ $# -ge 1 ]; then
  check_dir "$1"
else
  for d in */; do
    [ -f "$d/application.properties" ] && check_dir "${d%/}"
  done
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "Dependency-check GEFAILD: voeg ontbrekende camel-quarkus-* dependencies toe aan de pom.xml"
  exit 1
fi
echo "Dependency-check geslaagd."
