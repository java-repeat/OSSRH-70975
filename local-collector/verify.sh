#!/usr/bin/env bash
# Builds a local OpenTelemetry Collector (needs Go), starts it on 127.0.0.1:4318, runs
# LocalCollectorTests against it, and leaves what it received in local-collector/target/out.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/target/out"
collector="$here/target/dist/otelcol-local"

cd "$here"
if [[ ! -x "$collector" ]]; then
  echo "Building collector (first run only)..."
  GOTOOLCHAIN=auto go run go.opentelemetry.io/collector/cmd/builder@v0.163.0 --config builder.yaml
fi

rm -rf "$out" && mkdir -p "$out"
OUT_DIR="$out" "$collector" --config config.yaml > "$out/collector.log" 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT
for _ in $(seq 50); do
  grep -q "Everything is ready" "$out/collector.log" && break
  sleep 0.2
done

cd "$here/.."
mvn -q test -Dtest=LocalCollectorTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dotel.collector.output="$out" -Dotel.collector.endpoint=http://127.0.0.1:4318

echo "Collector received:"
grep -cE '"spanId"' "$out/traces.jsonl" | sed 's/^/  trace export lines with spans: /'
grep -oE 'Name *: [A-Za-z.]+|Metric #[0-9]+' "$out/collector.log" | sort | uniq -c | sort -rn | head -20
