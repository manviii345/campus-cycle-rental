#!/usr/bin/env bash
# Late-return fine test (SQLite only). Needs the server's database file, because the test moves a
# booking's dates into the past directly in the database (the API does not accept past dates).
# Usage: BASE=http://localhost:8080 DB_FILE=/path/to/cyclerental.db ./smoke-late.sh
set -u
BASE="${BASE:-http://localhost:8080}"; DB_FILE="${DB_FILE:?set DB_FILE to the SQLite file}"
PASS=0; FAIL=0
check() { if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "ok   - $1"; else FAIL=$((FAIL+1)); echo "FAIL - $1"; fi; }
jqv() { jq -r "$2" <<<"$1"; }
sql() { python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()" "$DB_FILE" "$1"; }
post() { curl -s -X POST "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' ${3:+-d "$3"}; }

TA=$(jqv "$(post /api/auth/register x '{"name":"Late Owner","email":"lo_be24@thapar.edu","phone":"9111111111","password":"password1"}')" .token)
TB=$(jqv "$(post /api/auth/register x '{"name":"Late Renter","email":"lr_be24@thapar.edu","phone":"9222222222","password":"password1"}')" .token)
CY=$(jqv "$(post /api/cycles $TA '{"title":"Late Test Cycle","pricePerHour":100,"location":"Main Gate"}')" .id)
post /api/cycles/$CY/availability $TA '{"day":"2026-12-10","pickupFrom":"09:00","returnBy":"18:00"}' >/dev/null
post /api/cycles/$CY/availability $TA '{"day":"2026-12-20","pickupFrom":"09:00","returnBy":"18:00"}' >/dev/null
BK=$(jqv "$(post /api/bookings $TB "{\"cycleId\":$CY,\"date\":\"2026-12-10\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}")" .id)
post /api/bookings/$BK/accept $TA >/dev/null; post /api/bookings/$BK/pickup $TA >/dev/null; post /api/bookings/$BK/pickup $TB >/dev/null

# Move the rental into the past: it ended 5 days before today.
END=$(python3 -c "import datetime;print(datetime.date.today()-datetime.timedelta(days=5))")
START=$(python3 -c "import datetime;print(datetime.date.today()-datetime.timedelta(days=7))")
sql "UPDATE bookings SET start_date='$START', end_date='$END' WHERE id=$BK"

R=$(curl -s "$BASE/api/bookings/$BK" -H "Authorization: Bearer $TB")
[ "$(jqv "$R" .lateDays)" = "5" ] && [ "$(jqv "$R" .fineAmount)" = "250" ] && [ "$(jqv "$R" .fineFinal)" = "false" ]; check "overdue ACTIVE rental: 5 days, Rs. 250 so far, not final" $?
R=$(curl -s "$BASE/api/bookings/$BK" -H "Authorization: Bearer $TA")
[ "$(jqv "$R" .fineAmount)" = "250" ]; check "owner sees the same fine" $?

# Renter initiates the return; pretend that happened 2 days ago (3 days after the end date).
post /api/bookings/$BK/return $TB >/dev/null
INIT=$(python3 -c "import datetime;print((datetime.date.today()-datetime.timedelta(days=2)).isoformat()+' 10:00:00')")
sql "UPDATE bookings SET return_renter_at='$INIT' WHERE id=$BK"
R=$(curl -s "$BASE/api/bookings/$BK" -H "Authorization: Bearer $TB")
[ "$(jqv "$R" .lateDays)" = "3" ] && [ "$(jqv "$R" .fineAmount)" = "150" ] && [ "$(jqv "$R" .fineFinal)" = "true" ]; check "fine stops at renter initiation (3 days, Rs. 150, final)" $?

# Owner confirms today (2 days after initiation): the owner's delay must not add to the fine.
R=$(post /api/bookings/$BK/return $TA)
[ "$(jqv "$R" .status)" = "COMPLETED" ] && [ "$(jqv "$R" .lateDays)" = "3" ] && [ "$(jqv "$R" .fineAmount)" = "150" ]; check "owner's late confirmation does not increase the fine" $?

# Return on the end date itself is not late.
BK2=$(jqv "$(post /api/bookings $TB "{\"cycleId\":$CY,\"date\":\"2026-12-20\",\"startTime\":\"09:00\",\"endTime\":\"18:00\"}")" .id)
post /api/bookings/$BK2/accept $TA >/dev/null; post /api/bookings/$BK2/pickup $TA >/dev/null; post /api/bookings/$BK2/pickup $TB >/dev/null
TODAY=$(date +%F); sql "UPDATE bookings SET end_date='$TODAY' WHERE id=$BK2"
R=$(post /api/bookings/$BK2/return $TB)
[ "$(jqv "$R" .lateDays)" = "0" ] && [ "$(jqv "$R" .fineAmount)" = "0" ]; check "return on the end date has no fine" $?

echo; echo "Passed: $PASS  Failed: $FAIL"; [ "$FAIL" -eq 0 ]
