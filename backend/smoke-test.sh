#!/usr/bin/env bash
# End-to-end smoke test: exercises every SRS workflow against a running server.
# Rentals are single-day with hourly pricing and 15-minute time steps.
# Usage: BASE=http://localhost:8080 ./smoke-test.sh
set -u
BASE="${BASE:-http://localhost:8080}"
PASS=0; FAIL=0

check() { # check <name> <condition-result 0/1>
  if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "ok   - $1";
  else FAIL=$((FAIL+1)); echo "FAIL - $1"; fi
}

jqv() { jq -r "$2" <<<"$1"; }

# 1. Health
R=$(curl -s "$BASE/health"); [ "$(jqv "$R" .status)" = "ok" ]; check "health endpoint" $?

# 2. Register two users (Asha the owner, Ram the renter)
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Asha Owner","email":"asha_be24@thapar.edu","phone":"9111111111","password":"secret1"}')
TOKEN_A=$(jqv "$R" .token); [ "${#TOKEN_A}" -gt 20 ]; check "register owner" $?
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Ram Renter","email":"ram_be24@thapar.edu","phone":"9222222222","password":"secret2"}')
TOKEN_B=$(jqv "$R" .token); [ "${#TOKEN_B}" -gt 20 ]; check "register renter" $?

# 2b. Phone validation: must be exactly 10 digits
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Short Phone","email":"short_be24@thapar.edu","phone":"987654321","password":"secret1"}')
[ "$(jqv "$R" .error)" = "Phone number must be exactly 10 digits" ]; check "9-digit phone rejected at register" $?
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Long Phone","email":"long_be24@thapar.edu","phone":"98765432109","password":"secret1"}')
[ "$(jqv "$R" .error)" = "Phone number must be exactly 10 digits" ]; check "11-digit phone rejected at register" $?
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Alpha Phone","email":"alpha_be24@thapar.edu","phone":"98765abcd0","password":"secret1"}')
[ "$(jqv "$R" .error)" = "Phone number must be exactly 10 digits" ]; check "non-digit phone rejected at register" $?
R=$(curl -s -X PUT "$BASE/api/me" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' -d '{"phone":"12345"}')
[ "$(jqv "$R" .error)" = "Phone number must be exactly 10 digits" ]; check "short phone rejected on profile update" $?
R=$(curl -s -X PUT "$BASE/api/me" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' -d '{"phone":"9876543210"}')
[ "$(jqv "$R" .phone)" = "9876543210" ]; check "10-digit phone accepted on profile update" $?

# 3. Duplicate email rejected
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Copy","email":"asha_be24@thapar.edu","password":"secret3"}')
[ "$(jqv "$R" .error)" = "An account with this email already exists" ]; check "duplicate email rejected" $?

# 4. Login works, wrong password rejected
R=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"asha_be24@thapar.edu","password":"secret1"}')
[ "$(jqv "$R" .user.name)" = "Asha Owner" ]; check "login" $?
R=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"asha_be24@thapar.edu","password":"wrong"}')
[ "$(jqv "$R" .error)" = "Wrong email or password" ]; check "wrong password rejected" $?

# 4b. Non-Thapar email cannot register (campus-only rule)
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Outsider","email":"outsider@gmail.com","password":"secret9"}')
[ "$(jqv "$R" .error)" = "Only Thapar University email IDs (ending in @thapar.edu) can be used to create an account" ]; check "non-Thapar registration rejected" $?

# 4c. Non-Thapar email gets a clear error at login
R=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"outsider@gmail.com","password":"secret9"}')
[ "$(jqv "$R" .error)" = "This website is only for Thapar University students. Please log in with your Thapar email ID (ending in @thapar.edu)" ]; check "non-Thapar login shows clear error" $?

# 5. Owner creates a listing (Rs. 100/hour); missing price rejected
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"No Price Cycle"}')
[ "$(jqv "$R" .error)" = "Price per hour must be greater than zero" ]; check "listing without price rejected" $?
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Hero Sprint 26T","brand":"Hero","model":"Sprint","cycleType":"City","condition":"Good","description":"Sturdy city cycle","pricePerHour":100,"location":"Main Gate, TIET campus"}')
CYCLE_ID=$(jqv "$R" .id); [ "$CYCLE_ID" -gt 0 ] 2>/dev/null; check "create listing (hourly price)" $?
[ "$(jqv "$R" .pricePerHour)" = "100" ]; check "listing stores hourly rate" $?

# 5b. Availability days: the owner adds days with a pickup/return window
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-10","pickupFrom":"09:00","returnBy":"18:00"}')
[ "$(jqv "$R" '.[0].day')" = "2026-12-10" ]; check "owner adds an availability day" $?
for D in 2026-12-11 2026-12-12 2026-12-20 2026-12-25; do
  curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
    -d "{\"day\":\"$D\",\"pickupFrom\":\"09:00\",\"returnBy\":\"18:00\"}" > /dev/null
done
R=$(curl -s "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" 'length')" = "5" ]; check "availability list shows all days" $?
# Non-owner cannot add days
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-13","pickupFrom":"09:00","returnBy":"18:00"}')
[ "$C" = "403" ]; check "non-owner cannot add availability" $?
# Non-quarter-hour window rejected
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-13","pickupFrom":"09:10","returnBy":"18:00"}')
[ "$(jqv "$R" .error)" = "Times must be in 15-minute steps (e.g. 09:00, 09:15, 09:30, 09:45)" ]; check "non-quarter-hour window rejected" $?
# Past day rejected
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2020-01-01","pickupFrom":"09:00","returnBy":"18:00"}')
[ "$(jqv "$R" .error)" = "Please choose a day from today onwards" ]; check "past availability day rejected" $?
# Replacing a day's window works
curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-12","pickupFrom":"10:00","returnBy":"16:00"}' > /dev/null
R=$(curl -s "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" '[.[] | select(.day == "2026-12-12")] | .[0].pickupFrom')" = "10:00" ] && [ "$(jqv "$R" '[.[] | select(.day == "2026-12-12")] | length')" = "1" ]; check "re-adding a day replaces its window" $?
# Summary fields stay in sync
R=$(curl -s "$BASE/api/cycles/$CYCLE_ID" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .availableFrom)" = "2026-12-10" ] && [ "$(jqv "$R" .availableTo)" = "2026-12-25" ]; check "available_from/to summary tracks the days" $?

# 6. Photo upload (1x1 PNG)
printf '\x89PNG\r\n\x1a\n' > /tmp/tiny.png
printf '\x00\x00\x00\x0dIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\x0d\x0a-\xb4\x00\x00\x00\x00IEND\xaeB\x60\x82' >> /tmp/tiny.png
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/photos" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: image/png' --data-binary @/tmp/tiny.png)
PHOTO_URL=$(jqv "$R" .url); [ -n "$PHOTO_URL" ] && [ "$PHOTO_URL" != "null" ]; check "photo upload" $?
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$PHOTO_URL"); [ "$C" = "200" ]; check "photo is served" $?

# 7. Search: keyword + hourly price filter + single-day time search
R=$(curl -s "$BASE/api/cycles?query=hero&maxPrice=200&date=2026-12-10&timeFrom=10:00&timeTo=12:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '.[0].id')" = "$CYCLE_ID" ]; check "search with filters + day/time" $?
R=$(curl -s "$BASE/api/cycles?maxPrice=50" -H "Authorization: Bearer $TOKEN_B"); [ "$(jqv "$R" 'length')" = "0" ]; check "hourly price filter excludes" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-15" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_ID')] | length')" = "0" ]; check "day without availability excluded from search" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=06:00&timeTo=08:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_ID')] | length')" = "0" ]; check "search outside the day's window excluded" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=10:10&timeTo=12:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .error)" = "Times must be in 15-minute steps (e.g. 09:00, 09:15, 09:30, 09:45)" ]; check "non-quarter-hour search time rejected" $?

# 7b. Legacy-priced listings: a converted daily rate stays unconfirmed until the owner sets the hourly price
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Old Daily Cycle","pricePerDay":480,"location":"Hostel J"}')
LEGACY_ID=$(jqv "$R" .id)
[ "$(jqv "$R" .pricePerHour)" = "20" ]; check "legacy daily price converts to hourly (480/24 = 20)" $?
[ "$(jqv "$R" .pricingConfirmed)" = "false" ]; check "converted listing starts unconfirmed" $?
R=$(curl -s -X POST "$BASE/api/cycles/$LEGACY_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-10","pickupFrom":"09:00","returnBy":"18:00"}')
[ "$(jqv "$R" '.[0].day')" = "2026-12-10" ]; check "owner can add a day to an unconfirmed listing" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=09:00&timeTo=10:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$LEGACY_ID')] | length')" = "0" ]; check "unconfirmed listing excluded from search" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$LEGACY_ID,\"date\":\"2026-12-10\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"}")
[ "$(jqv "$R" .error)" = "This listing is not bookable yet: the owner still needs to set its hourly price and availability" ]; check "unconfirmed listing cannot be booked (409)" $?
R=$(curl -s -X PUT "$BASE/api/cycles/$LEGACY_ID" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Old Daily Cycle Renamed"}')
[ "$(jqv "$R" .pricingConfirmed)" = "false" ]; check "unrelated edit does not confirm pricing" $?
R=$(curl -s -X PUT "$BASE/api/cycles/$LEGACY_ID" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"pricePerHour":25}')
[ "$(jqv "$R" .pricingConfirmed)" = "true" ] && [ "$(jqv "$R" .pricePerHour)" = "25" ]; check "owner setting the hourly price confirms the listing" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=09:00&timeTo=10:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$LEGACY_ID')] | length')" = "1" ]; check "confirmed listing appears in search" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$LEGACY_ID,\"date\":\"2026-12-10\",\"startTime\":\"09:00\",\"endTime\":\"10:15\"}")
BKLEG=$(jqv "$R" .id); [ "$(jqv "$R" .totalAmount)" = "31.25" ]; check "confirmed listing books at the new rate (1.25h x 25 = 31.25)" $?
curl -s -X POST "$BASE/api/bookings/$BKLEG/cancel" -H "Authorization: Bearer $TOKEN_B" > /dev/null

# 8. Renter requests a booking: 2026-12-10 10:00-12:30 = 2.5 hours x Rs. 100 = Rs. 250
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-10\",\"startTime\":\"10:00\",\"endTime\":\"12:30\"}")
BOOKING_ID=$(jqv "$R" .id); [ "$(jqv "$R" .totalAmount)" = "250" ]; check "booking request with hourly price calc (2.5h = Rs. 250)" $?

# 8b. Proportional quarter-hour billing: 75 minutes = 1.25 x hourly rate (Rs. 125)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-11\",\"startTime\":\"10:00\",\"endTime\":\"11:15\"}")
BK75=$(jqv "$R" .id); [ "$(jqv "$R" .totalAmount)" = "125" ]; check "75-minute booking costs 1.25x hourly rate" $?
curl -s -X POST "$BASE/api/bookings/$BK75/cancel" -H "Authorization: Bearer $TOKEN_B" > /dev/null

# 8c. Times default to the day's window when omitted (9 hours x Rs. 100 = Rs. 900)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-12\"}")
[ "$(jqv "$R" .startTime)" = "10:00" ] && [ "$(jqv "$R" .endTime)" = "16:00" ] && [ "$(jqv "$R" .totalAmount)" = "600" ]; check "omitted times default to the day's window" $?
BKDEF=$(jqv "$R" .id); curl -s -X POST "$BASE/api/bookings/$BKDEF/cancel" -H "Authorization: Bearer $TOKEN_B" > /dev/null

# 8d. Overlapping PENDING request is allowed while nothing is confirmed yet (BR-05)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-10\",\"startTime\":\"11:00\",\"endTime\":\"13:00\"}")
REQ2_ID=$(jqv "$R" .id); [ "$(jqv "$R" .status)" = "PENDING" ]; check "overlapping pending request allowed" $?

# 8e. Validation: window, availability day, single-day rule, quarter hours, order, past date
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-10\",\"startTime\":\"06:00\",\"endTime\":\"08:00\"}")
[ "$C" = "409" ]; check "booking outside the day's window rejected (409)" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-15\",\"startTime\":\"10:00\",\"endTime\":\"12:00\"}")
[ "$(jqv "$R" .error)" = "The owner has not made this cycle available on 2026-12-15" ]; check "day without availability rejected" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-10\",\"endDate\":\"2026-12-12\"}")
[ "$(jqv "$R" .error)" = "Rentals are for a single day now - pick a date with a pickup and return time" ]; check "multi-day booking rejected" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-11\",\"startTime\":\"10:07\",\"endTime\":\"12:00\"}")
[ "$(jqv "$R" .error)" = "Times must be in 15-minute steps (e.g. 09:00, 09:15, 09:30, 09:45)" ]; check "non-quarter-hour booking time rejected" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-11\",\"startTime\":\"14:00\",\"endTime\":\"11:00\"}")
[ "$(jqv "$R" .error)" = "The return time must be after the pickup time" ]; check "return before pickup rejected" $?
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2020-06-01\",\"startTime\":\"10:00\",\"endTime\":\"12:00\"}")
[ "$(jqv "$R" .error)" = "Please choose a date from today onwards" ]; check "past booking date rejected" $?

# 9. Renter cannot book own cycle (BR-03)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-10\",\"startTime\":\"14:00\",\"endTime\":\"16:00\"}")
[ "$(jqv "$R" .error)" = "You cannot rent your own cycle" ]; check "BR-03 own-cycle rejected" $?

# 10. Renter cannot accept; owner accepts
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/accept" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .error)" = "Only the cycle owner can do this" ]; check "renter cannot accept" $?
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/accept" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .status)" = "ACCEPTED" ]; check "owner accepts" $?

# 10c. Overlap re-checked at accept time: the pending overlapping request can no longer be accepted (FR-13/BR-05)
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings/$REQ2_ID/accept" -H "Authorization: Bearer $TOKEN_A")
[ "$C" = "409" ]; check "accept-side overlap blocked (409)" $?
R=$(curl -s -X POST "$BASE/api/bookings/$REQ2_ID/reject" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .status)" = "REJECTED" ]; check "owner rejects pending request" $?

# 10d. Renter cancels an accepted booking before pickup (FR-15)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-20\",\"startTime\":\"09:00\",\"endTime\":\"10:00\"}")
REQ3_ID=$(jqv "$R" .id)
curl -s -X POST "$BASE/api/bookings/$REQ3_ID/accept" -H "Authorization: Bearer $TOKEN_A" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$REQ3_ID/cancel" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .status)" = "CANCELLED" ]; check "renter cancels accepted booking" $?

# 10b. Owner booking list carries renter name and owner id (UI needs both)
R=$(curl -s "$BASE/api/bookings/mine?role=owner" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" '.[0].renterName')" = "Ram Renter" ] && [ "$(jqv "$R" '.[0].ownerId')" != "null" ] && [ "$(jqv "$R" '.[0].ownerName')" = "Asha Owner" ]; check "owner booking list fields" $?

# 10e. Concurrent acceptance: two overlapping pending requests accepted at the same moment -> exactly one wins
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-25\",\"startTime\":\"09:00\",\"endTime\":\"12:00\"}"); CA=$(jqv "$R" .id)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-25\",\"startTime\":\"10:00\",\"endTime\":\"13:00\"}"); CB=$(jqv "$R" .id)
curl -s -o /tmp/cc_a -w '%{http_code}' -X POST "$BASE/api/bookings/$CA/accept" -H "Authorization: Bearer $TOKEN_A" > /tmp/cc_ca 2>/dev/null &
curl -s -o /tmp/cc_b -w '%{http_code}' -X POST "$BASE/api/bookings/$CB/accept" -H "Authorization: Bearer $TOKEN_A" > /tmp/cc_cb 2>/dev/null &
wait
OKS=$( (cat /tmp/cc_ca; echo; cat /tmp/cc_cb; echo) | grep -c '^200$')
[ "$OKS" = "1" ]; check "concurrent accept: exactly one of two overlapping bookings accepted" $?
R=$(curl -s "$BASE/api/bookings/mine?role=owner" -H "Authorization: Bearer $TOKEN_A")
N=$(jqv "$R" '[.[] | select(.id == '$CA' or .id == '$CB') | select(.status == "ACCEPTED")] | length')
[ "$N" = "1" ]; check "concurrent accept: database holds at most one ACCEPTED" $?
# Clean up the winner so later steps are unaffected
for X in $CA $CB; do curl -s -X POST "$BASE/api/bookings/$X/cancel" -H "Authorization: Bearer $TOKEN_B" > /dev/null; done

# 11. Overlap: a new request over the accepted slot must fail (FR-13)
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Second Cycle","pricePerHour":50}')
CYCLE2=$(jqv "$R" .id)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"date\":\"2026-12-10\",\"startTime\":\"12:00\",\"endTime\":\"13:00\"}")
[ "$(jqv "$R" .error)" = "This cycle is already booked for (part of) that period" ]; check "overlap rejected at request" $?

# 11b. Timing search: a confirmed booking overlapping the requested slot hides the cycle
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=11:00&timeTo=12:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_ID')] | length')" = "0" ]; check "timing search excludes an overlapping booking" $?
R=$(curl -s "$BASE/api/cycles?date=2026-12-10&timeFrom=13:00&timeTo=15:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_ID')] | length')" = "1" ]; check "timing search finds a free slot" $?

# 11c. A day with a pending booking cannot be removed; a free day can
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"day":"2026-12-28","pickupFrom":"09:00","returnBy":"12:00"}')
AV_ID=$(jqv "$R" '[.[] | select(.day == "2026-12-28")] | .[0].id')
R=$(curl -s -X DELETE "$BASE/api/cycles/$CYCLE_ID/availability/$AV_ID" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" '[.[] | select(.day == "2026-12-28")] | length')" = "0" ]; check "owner removes a free availability day" $?
AV_BOOKED=$(curl -s "$BASE/api/cycles/$CYCLE_ID/availability" -H "Authorization: Bearer $TOKEN_A" | jq -r '[.[] | select(.day == "2026-12-10")] | .[0].id')
C=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/cycles/$CYCLE_ID/availability/$AV_BOOKED" -H "Authorization: Bearer $TOKEN_A")
[ "$C" = "409" ]; check "day with an accepted booking cannot be removed (409)" $?

# 12. Contact details visible after acceptance (NFR-03)
R=$(curl -s "$BASE/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .otherPartyContact.email)" = "asha_be24@thapar.edu" ]; check "contact shared after acceptance" $?

# 13. Payment recorded (FR-17)
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/payment" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"method":"UPI","transactionReference":"txn123"}')
[ "$(jqv "$R" .payment.status)" = "RECORDED" ] && [ "$(jqv "$R" .payment.amount)" = "250" ]; check "payment recorded" $?

# 14. Two-sided pickup confirmation -> ACTIVE (FR-19)
curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/pickup" -H "Authorization: Bearer $TOKEN_A" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/pickup" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .status)" = "ACTIVE" ]; check "two-sided pickup -> ACTIVE" $?

# 14b. Renter cannot cancel once the cycle is picked up (FR-15)
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings/$BOOKING_ID/cancel" -H "Authorization: Bearer $TOKEN_B")
[ "$C" = "409" ]; check "cancel after pickup blocked (409)" $?

# 14c. Listing with an active rental cannot be removed (BR-10)
C=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/cycles/$CYCLE_ID" -H "Authorization: Bearer $TOKEN_A")
[ "$C" = "409" ]; check "delete with active rental blocked (409)" $?

# 15. Return management: renter initiates, owner confirms -> COMPLETED (FR-20)
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/bookings/$BOOKING_ID/return" -H "Authorization: Bearer $TOKEN_A")
[ "$CODE" = "409" ]; check "owner return before renter initiation blocked (409)" $?
curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/return" -H "Authorization: Bearer $TOKEN_B" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/return" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .status)" = "COMPLETED" ]; check "two-sided return -> COMPLETED" $?
# 15b. On-time return carries no fine (Rs. 50/day only after the end date)
R=$(curl -s "$BASE/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .lateDays)" = "0" ] && [ "$(jqv "$R" .fineAmount)" = "0" ] && [ "$(jqv "$R" .lateFeePerDay)" = "50" ]; check "on-time return has zero fine" $?

# 16. Reviews both ways (FR-23, BR-08)
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/reviews" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"rating":5,"comment":"Great cycle, smooth handover"}')
[ "$(jqv "$R" '.reviews | length')" = "1" ]; check "renter reviews owner" $?
curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/reviews" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"rating":4,"comment":"Returned on time"}' > /dev/null
R=$(curl -s "$BASE/api/users/$(curl -s "$BASE/api/cycles/$CYCLE_ID" -H "Authorization: Bearer $TOKEN_A" | jq -r .ownerId)")
[ "$(jqv "$R" .avgRating)" = "5" ]; check "owner public rating" $?

# 17. Notifications delivered (FR-21)
R=$(curl -s "$BASE/api/notifications" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" 'length')" -ge 3 ]; check "notifications for owner" $?

# 18. Issue report (FR-24)
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/report" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"description":"Brake was loose"}')
[ "$(jqv "$R" .status)" = "OPEN" ]; check "issue report" $?

# 19. Auth required: listings are private to logged-in students (search, detail, own lists)
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/cycles/mine"); [ "$C" = "401" ]; check "unauthenticated request rejected" $?
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/cycles"); [ "$C" = "401" ]; check "cycle search hidden when logged out" $?
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/cycles/$CYCLE_ID"); [ "$C" = "401" ]; check "cycle detail hidden when logged out" $?
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/cycles/$CYCLE_ID/availability"); [ "$C" = "401" ]; check "availability hidden when logged out" $?

# 20. The cycle owner must mark the cycle returned: renter initiates, owner completes
BKT2=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"cycleId":'$CYCLE_ID',"date":"2026-12-20","startTime":"10:00","endTime":"11:00"}' | jq -r .id)
curl -s -X POST "$BASE/api/bookings/$BKT2/accept" -H "Authorization: Bearer $TOKEN_A" > /dev/null
curl -s -X POST "$BASE/api/bookings/$BKT2/pickup" -H "Authorization: Bearer $TOKEN_A" > /dev/null
curl -s -X POST "$BASE/api/bookings/$BKT2/pickup" -H "Authorization: Bearer $TOKEN_B" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .error)" = "The renter has not initiated the return yet" ]; check "owner cannot mark returned before the renter initiates" $?
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .status)" = "ACTIVE" ] && [ "$(jqv "$R" .returnRenterConfirmedAt)" != "null" ]; check "renter initiates the return (still active)" $?
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .status)" = "COMPLETED" ]; check "owner marks returned, rental completes" $?

# 21. Owner history includes the completed booking even after listing removal (BR-10)
R=$(curl -s -X DELETE "$BASE/api/cycles/$CYCLE2" -H "Authorization: Bearer $TOKEN_A"); [ "$(jqv "$R" .ok)" = "true" ]; check "listing removal" $?
R=$(curl -s "$BASE/api/bookings/mine?role=renter" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$BOOKING_ID')] | .[0].status')" = "COMPLETED" ]; check "booking history kept" $?

echo
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
