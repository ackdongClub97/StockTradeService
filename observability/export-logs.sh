#!/usr/bin/env bash
set -euo pipefail

# Loki에 쌓인 로그를 파일로 export. jq 필요(brew install jq).
#
# 사용법:
#   ./export-logs.sh ['<LogQL 쿼리>'] [<시작시각>] [<종료시각>] [<출력파일>]
#
# 예시:
#   ./export-logs.sh                                              # 최근 1시간, 전체 로그
#   ./export-logs.sh '{app="stocktrade"} |= "ERROR"'               # 최근 1시간, ERROR만
#   ./export-logs.sh '{app="stocktrade"}' 2026-07-30T00:00:00Z 2026-07-31T00:00:00Z out.txt

LOKI_URL="${LOKI_URL:-http://localhost:3100}"
QUERY="${1:-{app=\"stocktrade\"}}"
LIMIT="${LIMIT:-5000}"

# macOS(BSD date)와 Linux(GNU date) 둘 다 지원
one_hour_ago() {
  date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ
}

START="${2:-$(one_hour_ago)}"
END="${3:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
OUT="${4:-logs_$(date +%Y%m%d_%H%M%S).txt}"

echo "쿼리: ${QUERY}"
echo "기간: ${START} ~ ${END}"
echo "출력 파일: ${OUT}"

curl -sG "${LOKI_URL}/loki/api/v1/query_range" \
  --data-urlencode "query=${QUERY}" \
  --data-urlencode "start=${START}" \
  --data-urlencode "end=${END}" \
  --data-urlencode "limit=${LIMIT}" \
  --data-urlencode "direction=forward" \
  | jq -r '.data.result[] | .values[] | [(.[0]|tonumber/1000000000|gmtime|strftime("%Y-%m-%dT%H:%M:%SZ")), .[1]] | @tsv' \
  | sort \
  > "${OUT}"

echo "완료: $(wc -l < "${OUT}" | tr -d ' ') 줄 저장됨 -> ${OUT}"
