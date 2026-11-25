#!/usr/bin/env bash
# Availability-confirmation test (SQLite only). Simulates a leftover availability row from the
# hourly-version migration (confirmed = 0) and proves it is not bookable until the owner
# re-saves the day, and that re-saving makes it bookable. Needs the server's DB file.
# Usage: BASE=http://localhost:8080 DB_FILE=/path/to/cyclerental.db ./smoke-availability-confirm.sh
set -u
BASE="${BASE:-http://localhost:8080}"; DB_FILE="${DB_FILE:?set DB_FILE to the SQLite file}"
PASS=0; FAIL=0
check() { if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "ok   - $1"; else FAIL=$((FAIL+1)); echo "FAIL - $1"; fi; }
jqv() { jq -r "$2" <<<"$1"; }
sql() { python3 -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(sys.argv[2]); c.commit()" "$DB_FILE" "$1"; }
post() { curl -s -X POST "$BASE$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' ${3:+-d "$3"}; }

TA=$(jqv "$(post /api/auth/register x '{"name":"Avail Owner","email":"ao_be24@thapar.edu","phone":"9666666666","password":"password1"}')" .token)
TB=$(jqv "$(post /api/auth/register x '{"name":"Avail Renter","email":"ar_be24@thapar.edu","phone":"9777777777","password":"password1"}')" .token)
CY=$(jqv "$(post /api/cycles $TA '{"title":"Confirm Test Cycle","pricePerHour":100,"location":"Library"}')" .id)
post /api/cycles/$CY/availability $TA '{"day":"2026-12-10","pickupFrom":"09:00","returnBy":"18:00"}' >/dev/null

# Simulate a leftover row from the hourly-version migration: present but unconfirmed.
sql "UPDATE cycle_availability SET confirmed = 0 WHERE cycle_id = $CY"

R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=09:00&timeTo=10:00" -H "Authorization: Bearer $TB")
[ "$(jqv "$R" '[.[] | select(.id == '$CY')] | length')" = "0" ]; check "leftover unconfirmed day excluded from search" $?

R=$(post /api/bookings $TB "{\"cycleId\":$CY,\"date\":\"2026-12-10\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"}")
[ "$(jqv "$R" .error)" = "The owner has not made this cycle available on 2026-12-10" ]; check "leftover unconfirmed day not bookable" $?

R=$(curl -s "$BASE/api/cycles/$CY" -H "Authorization: Bearer $TB")
[ "$(jqv "$R" '.availability | length')" = "0" ]; check "renter view hides unconfirmed days" $?

# Owner re-saves the same day (the My Cycles "keep this day" action) -> confirmed and bookable.
R=$(post /api/cycles/$CY/availability $TA '{"day":"2026-12-10","pickupFrom":"09:00","returnBy":"18:00"}')
[ "$(jqv "$R" '.[0].confirmed')" = "true" ]; check "owner re-saving the day confirms it" $?

R=$(post /api/bookings $TB "{\"cycleId\":$CY,\"date\":\"2026-12-10\",\"startTime\":\"10:00\",\"endTime\":\"11:15\"}")
BK=$(jqv "$R" .id); [ "$(jqv "$R" .totalAmount)" = "125" ]; check "confirmed day books at hourly rate (1.25h = Rs. 125)" $?
curl -s -X POST "$BASE/api/bookings/$BK/cancel" -H "Authorization: Bearer $TB" > /dev/null

echo; echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
