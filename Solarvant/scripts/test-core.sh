#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tmp-solarvant-tests
trap 'rm -rf .tmp-solarvant-tests' EXIT
javac --release 21 -d .tmp-solarvant-tests \
  common/src/main/java/dev/solarvant/core/*.java \
  common/src/main/java/dev/solarvant/engine/CanopyOcclusion.java \
  common/src/main/java/dev/solarvant/engine/RenderBudgetGovernor.java \
  common/src/main/java/dev/solarvant/engine/UploadPacer.java \
  common/src/main/java/dev/solarvant/engine/SectionTintCache.java \
  common/src/main/java/dev/solarvant/engine/RegionArena.java \
  common/src/main/java/dev/solarvant/engine/RegionMeshPipeline.java \
  common/src/main/java/dev/solarvant/engine/QuadIndexCache.java \
  common/src/main/java/dev/solarvant/engine/IndexStoragePolicy.java \
  common/src/main/java/dev/solarvant/engine/SectionFrustum.java \
  common/src/main/java/dev/solarvant/engine/ReciprocalFaceCache.java \
  tests/dev/solarvant/tests/CoreTests.java \
  tests/dev/solarvant/tests/RegionMeshPipelineTests.java \
  tests/dev/solarvant/tests/QuadIndexCacheTests.java \
  tests/dev/solarvant/tests/SharedIndexPolicyTests.java
java -cp .tmp-solarvant-tests dev.solarvant.tests.CoreTests

java -cp .tmp-solarvant-tests dev.solarvant.tests.RegionMeshPipelineTests
java -cp .tmp-solarvant-tests dev.solarvant.tests.QuadIndexCacheTests

python3 scripts/test-native-adoption.py
python3 scripts/test-native-memory.py

java -cp .tmp-solarvant-tests dev.solarvant.tests.SharedIndexPolicyTests
