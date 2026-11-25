#!/usr/bin/env bash
# Boundary checks for hourly bookings: any rental whose whole interval lies
# inside the owner's availability window is bookable (15-minute steps);
# touching intervals do not count as overlap.
set -u
BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0
ok()   { echo "ok   - $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL - $1"; FAIL=$((FAIL+1)); }
code() { curl -s -o /tmp/stb_body -w '%{http_code}' "$@"; }

# --- setup: owner + renter + confirmed listing with one window 09:00-18:00 tomorrow
DAY=$(date -d '+1 day' +%F)
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Own T","email":"tb_owner@thapar.edu","password":"password1","phone":"9812345678"}' > /dev/null
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Rent T","email":"tb_renter@thapar.edu","password":"password1","phone":"9812345679"}' > /dev/null
TO=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"email":"tb_owner@thapar.edu","password":"password1"}' | jq -r .token)
TR=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' -d '{"email":"tb_renter@thapar.edu","password":"password1"}' | jq -r .token)
CID=$(curl -s -X POST $BASE/api/cycles -H "Authorization: Bearer $TO" -H 'Content-Type: application/json' \
  -d '{"title":"Boundary Bike","brand":"B","model":"M","cycleType":"City","condition":"Good","location":"Gate","pricePerHour":100}' | jq -r .id)
curl -s -X POST $BASE/api/cycles/$CID/availability -H "Authorization: Bearer $TO" -H 'Content-Type: application/json' \
  -d "{\"day\":\"$DAY\",\"pickupFrom\":\"09:00\",\"returnBy\":\"18:00\"}" > /dev/null

book() { code -X POST $BASE/api/bookings -H "Authorization: Bearer $TR" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CID,\"date\":\"$DAY\",\"startTime\":\"$1\",\"endTime\":\"$2\"}"; }

# 1. contained sub-interval books (10:00-11:00)
[ "$(book 10:00 11:00)" = "201" ] && ok 'contained sub-interval 10:00-11:00 books' || { bad "contained sub-interval: $(cat /tmp/stb_body)"; }
B1=$(jq -r .id /tmp/stb_body)
# accept it so it blocks its slot
curl -s -X POST $BASE/api/bookings/$B1/accept -H "Authorization: Bearer $TO" > /dev/null

# 2. touching end: 11:00-11:15 books (return at 11:00, next pickup at 11:00)
[ "$(book 11:00 11:15)" = "201" ] && ok 'touching interval 11:00-11:15 books' || bad "touching end: $(cat /tmp/stb_body)"
# 15-minute duration priced proportionally (0.25h x Rs 100 = Rs 25)
T=$(jq -r '.totalAmount // .total_amount // empty' /tmp/stb_body)
[ "$T" = "25" ] || [ "$T" = "25.0" ] && ok '15-minute rental priced Rs 25 (0.25h x 100)' || bad "15-min price: $T"

# 3. touching start: 09:00-10:00 books
[ "$(book 09:00 10:00)" = "201" ] && ok 'touching interval 09:00-10:00 books' || bad "touching start: $(cat /tmp/stb_body)"

# 4. real overlap rejected: 10:30-11:30
[ "$(book 10:30 11:30)" = "409" ] && ok 'overlapping 10:30-11:30 rejected 409' || bad "overlap not rejected: $(cat /tmp/stb_body)"

# 5. outside window start: 08:45-09:15 rejected
[ "$(book 08:45 09:15)" = "409" ] && ok 'before-window 08:45-09:15 rejected 409' || bad "before-window not rejected: $(cat /tmp/stb_body)"

# 6. outside window end: 17:45-18:15 rejected
[ "$(book 17:45 18:15)" = "409" ] && ok 'after-window 17:45-18:15 rejected 409' || bad "after-window not rejected: $(cat /tmp/stb_body)"

# 7. window boundary exactly: 17:45-18:00 books
[ "$(book 17:45 18:00)" = "201" ] && ok 'window-edge 17:45-18:00 books' || bad "window-edge: $(cat /tmp/stb_body)"

# 8. non-quarter time rejected: 10:07
[ "$(book 10:07 11:00)" = "400" ] && ok 'non-15-minute time 10:07 rejected 400' || bad "non-quarter not rejected: $(cat /tmp/stb_body)"

# 8b. any duration books when fully inside the window (no one-hour cap):
# 75 and 90 minutes both book and price proportionally
[ "$(book 14:00 15:15)" = "201" ] && ok '75-minute rental books (no duration cap)' || bad "75-min: $(cat /tmp/stb_body)"
T75=$(jq -r '.totalAmount // .total_amount // empty' /tmp/stb_body)
[ "$T75" = "125" ] || [ "$T75" = "125.0" ] && ok '75-minute rental priced Rs 125 (1.25h x 100)' || bad "75-min price: $T75"
[ "$(book 15:15 16:45)" = "201" ] && ok '90-minute rental books (no duration cap)' || bad "90-min: $(cat /tmp/stb_body)"
T90=$(jq -r '.totalAmount // .total_amount // empty' /tmp/stb_body)
[ "$T90" = "150" ] || [ "$T90" = "150.0" ] && ok '90-minute rental priced Rs 150 (1.5h x 100)' || bad "90-min price: $T90"
[ "$(book 14:00 14:00)" = "400" ] && ok 'zero-length rental rejected 400' || bad "zero-length not rejected: $(cat /tmp/stb_body)"

# 9. search: touching slot shows the cycle, overlapping slot hides it
N=$(curl -s "$BASE/api/cycles?date=$DAY&timeFrom=11:00&timeTo=11:15" -H "Authorization: Bearer $TR" | jq --argjson c $CID '[.[] | select(.id==$c)] | length')
[ "$N" = "1" ] && ok 'search finds cycle for free touching slot 11:00-11:15' || bad 'search touching slot'
N=$(curl -s "$BASE/api/cycles?date=$DAY&timeFrom=10:30&timeTo=11:30" -H "Authorization: Bearer $TR" | jq --argjson c $CID '[.[] | select(.id==$c)] | length')
[ "$N" = "0" ] && ok 'search hides cycle for overlapped slot 10:30-11:30' || bad 'search overlapped slot'

echo
echo "Passed: $PASS  Failed: $FAIL"
[ $FAIL -eq 0 ]
