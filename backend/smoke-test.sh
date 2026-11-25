#!/usr/bin/env bash
# End-to-end smoke test: exercises every SRS workflow against a running server.
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
  -d '{"name":"Asha Owner","email":"asha_be24@thapar.edu","phone":"+91111","password":"secret1"}')
TOKEN_A=$(jqv "$R" .token); [ "${#TOKEN_A}" -gt 20 ]; check "register owner" $?
R=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
  -d '{"name":"Ram Renter","email":"ram_be24@thapar.edu","phone":"+91222","password":"secret2"}')
TOKEN_B=$(jqv "$R" .token); [ "${#TOKEN_B}" -gt 20 ]; check "register renter" $?

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

# 5. Owner creates a listing (Rs. 150/day, available all of December)
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Hero Sprint 26T","brand":"Hero","model":"Sprint","cycleType":"City","condition":"Good","description":"Sturdy city cycle","pricePerDay":150,"location":"Main Gate, TIET campus","availableFrom":"2026-12-01","availableTo":"2026-12-31"}')
CYCLE_ID=$(jqv "$R" .id); [ "$CYCLE_ID" -gt 0 ] 2>/dev/null; check "create listing" $?

# 6. Photo upload (1x1 PNG)
printf '\x89PNG\r\n\x1a\n' > /tmp/tiny.png
printf '\x00\x00\x00\x0dIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\x0aIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\x0d\x0a-\xb4\x00\x00\x00\x00IEND\xaeB\x60\x82' >> /tmp/tiny.png
R=$(curl -s -X POST "$BASE/api/cycles/$CYCLE_ID/photos" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: image/png' --data-binary @/tmp/tiny.png)
PHOTO_URL=$(jqv "$R" .url); [ -n "$PHOTO_URL" ] && [ "$PHOTO_URL" != "null" ]; check "photo upload" $?
C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$PHOTO_URL"); [ "$C" = "200" ]; check "photo is served" $?

# 7. Search finds it with filters
R=$(curl -s "$BASE/api/cycles?query=hero&maxPrice=200&from=2026-12-10&to=2026-12-12" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '.[0].id')" = "$CYCLE_ID" ]; check "search with filters" $?
R=$(curl -s "$BASE/api/cycles?maxPrice=100" -H "Authorization: Bearer $TOKEN_B"); [ "$(jqv "$R" 'length')" = "0" ]; check "price filter excludes" $?

# 8. Renter requests a booking (3 days -> Rs. 450)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-10\",\"endDate\":\"2026-12-12\"}")
BOOKING_ID=$(jqv "$R" .id); [ "$(jqv "$R" .totalAmount)" = "450" ]; check "booking request with price calc" $?

# 8b. Overlapping PENDING request is allowed while nothing is confirmed yet (BR-05)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-11\",\"endDate\":\"2026-12-13\"}")
REQ2_ID=$(jqv "$R" .id); [ "$(jqv "$R" .status)" = "PENDING" ]; check "overlapping pending request allowed" $?

# 8c. Request outside the listing's availability window is rejected (BR-04)
C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2027-01-05\",\"endDate\":\"2027-01-07\"}")
[ "$C" = "409" ]; check "outside availability window rejected (409)" $?

# 9. Renter cannot book own cycle; overlapping confirmed booking blocked later
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-05\",\"endDate\":\"2026-12-06\"}")
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
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-20\",\"endDate\":\"2026-12-22\"}")
REQ3_ID=$(jqv "$R" .id)
curl -s -X POST "$BASE/api/bookings/$REQ3_ID/accept" -H "Authorization: Bearer $TOKEN_A" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$REQ3_ID/cancel" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .status)" = "CANCELLED" ]; check "renter cancels accepted booking" $?

# 10b. Owner booking list carries renter name and owner id (UI needs both)
R=$(curl -s "$BASE/api/bookings/mine?role=owner" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" '.[0].renterName')" = "Ram Renter" ] && [ "$(jqv "$R" '.[0].ownerId')" != "null" ] && [ "$(jqv "$R" '.[0].ownerName')" = "Asha Owner" ]; check "owner booking list fields" $?

# 10e. Concurrent acceptance: two overlapping pending requests accepted at the same moment -> exactly one wins
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-25\",\"endDate\":\"2026-12-27\"}"); CA=$(jqv "$R" .id)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-26\",\"endDate\":\"2026-12-28\"}"); CB=$(jqv "$R" .id)
WINS=0
for i in 1; do
  curl -s -o /tmp/cc_a.$i -w '%{http_code}' -X POST "$BASE/api/bookings/$CA/accept" -H "Authorization: Bearer $TOKEN_A" > /tmp/cc_ca.$i 2>/dev/null &
  curl -s -o /tmp/cc_b.$i -w '%{http_code}' -X POST "$BASE/api/bookings/$CB/accept" -H "Authorization: Bearer $TOKEN_A" > /tmp/cc_cb.$i 2>/dev/null &
  wait
  break
done
OKS=$( (cat /tmp/cc_ca.1; echo; cat /tmp/cc_cb.1; echo) | grep -c '^200$')
[ "$OKS" = "1" ]; check "concurrent accept: exactly one of two overlapping bookings accepted" $?
R=$(curl -s "$BASE/api/bookings/mine?role=owner" -H "Authorization: Bearer $TOKEN_A")
N=$(jqv "$R" '[.[] | select(.id == '$CA' or .id == '$CB') | select(.status == "ACCEPTED")] | length')
[ "$N" = "1" ]; check "concurrent accept: database holds at most one ACCEPTED" $?
# Clean up the winner so later steps are unaffected
for X in $CA $CB; do curl -s -X POST "$BASE/api/bookings/$X/cancel" -H "Authorization: Bearer $TOKEN_B" > /dev/null; done

# 11. Overlap: a new request over the accepted period must fail (FR-13)
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Second Cycle","pricePerDay":100,"availableFrom":"2026-12-01","availableTo":"2026-12-31"}')
CYCLE2=$(jqv "$R" .id)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"cycleId\":$CYCLE_ID,\"startDate\":\"2026-12-11\",\"endDate\":\"2026-12-13\"}")
[ "$(jqv "$R" .error)" = "This cycle is already booked for (part of) that period" ]; check "overlap rejected at request" $?

# 12. Contact details visible after acceptance (NFR-03)
R=$(curl -s "$BASE/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .otherPartyContact.email)" = "asha_be24@thapar.edu" ]; check "contact shared after acceptance" $?

# 13. Payment recorded (FR-17)
R=$(curl -s -X POST "$BASE/api/bookings/$BOOKING_ID/payment" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"method":"UPI","transactionReference":"txn123"}')
[ "$(jqv "$R" .payment.status)" = "RECORDED" ] && [ "$(jqv "$R" .payment.amount)" = "450" ]; check "payment recorded" $?

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

# 19b. Rental times: booking stores pickup/return times and they show on the booking
R=$(curl -s -X POST "$BASE/api/cycles" -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"Timed Cycle","pricePerDay":100,"location":"Library","availableFrom":"2027-01-01","availableTo":"2027-01-31"}')
CYCLE_T=$(jqv "$R" .id)
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"cycleId":'$CYCLE_T',"startDate":"2027-01-10","endDate":"2027-01-12","startTime":"08:30","endTime":"20:15"}')
BKT=$(jqv "$R" .id)
R=$(curl -s "$BASE/api/bookings/$BKT" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .startTime)" = "08:30" ] && [ "$(jqv "$R" .endTime)" = "20:15" ]; check "booking stores pickup and return times" $?
# Same-day booking must end after it starts
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"cycleId":'$CYCLE_T',"startDate":"2027-01-15","endDate":"2027-01-15","startTime":"18:00","endTime":"09:00"}')
[ "$(jqv "$R" .error)" = "The return time must be after the pickup time" ]; check "same-day booking with end before start rejected" $?
# A same-day booking with valid times is allowed
R=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"cycleId":'$CYCLE_T',"startDate":"2027-01-16","endDate":"2027-01-16","startTime":"09:00","endTime":"11:00"}')
BKSD=$(jqv "$R" .id)
[ "$BKSD" -gt 0 ] 2>/dev/null; check "same-day booking with valid times allowed" $?
# Search by timings: a confirmed booking overlapping the requested slot hides the cycle
curl -s -X POST "$BASE/api/bookings/$BKT/reject" -H "Authorization: Bearer $TOKEN_A" > /dev/null
curl -s -X POST "$BASE/api/bookings/$BKSD/accept" -H "Authorization: Bearer $TOKEN_A" > /dev/null
R=$(curl -s "$BASE/api/cycles?from=2027-01-16&to=2027-01-16&timeFrom=10:00&timeTo=12:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_T')] | length')" = "0" ]; check "timing search excludes an overlapping booking" $?
R=$(curl -s "$BASE/api/cycles?from=2027-01-16&to=2027-01-16&timeFrom=12:00&timeTo=14:00" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$CYCLE_T')] | length')" = "1" ]; check "timing search finds a free slot" $?

# 19c. The cycle owner must mark the cycle returned: renter initiates, owner completes
BKT2=$(curl -s -X POST "$BASE/api/bookings" -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d '{"cycleId":'$CYCLE_T',"startDate":"2027-01-20","endDate":"2027-01-21"}' | jq -r .id)
curl -s -X POST "$BASE/api/bookings/$BKT2/accept" -H "Authorization: Bearer $TOKEN_A" > /dev/null
curl -s -X POST "$BASE/api/bookings/$BKT2/pickup" -H "Authorization: Bearer $TOKEN_A" > /dev/null
curl -s -X POST "$BASE/api/bookings/$BKT2/pickup" -H "Authorization: Bearer $TOKEN_B" > /dev/null
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .error)" = "The renter has not initiated the return yet" ]; check "owner cannot mark returned before the renter initiates" $?
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" .status)" = "ACTIVE" ] && [ "$(jqv "$R" .returnRenterConfirmedAt)" != "null" ]; check "renter initiates the return (still active)" $?
R=$(curl -s -X POST "$BASE/api/bookings/$BKT2/return" -H "Authorization: Bearer $TOKEN_A")
[ "$(jqv "$R" .status)" = "COMPLETED" ]; check "owner marks returned, rental completes" $?

# 20. Owner history includes the completed booking even after listing removal (BR-10)
R=$(curl -s -X DELETE "$BASE/api/cycles/$CYCLE2" -H "Authorization: Bearer $TOKEN_A"); [ "$(jqv "$R" .ok)" = "true" ]; check "listing removal" $?
R=$(curl -s "$BASE/api/bookings/mine?role=renter" -H "Authorization: Bearer $TOKEN_B")
[ "$(jqv "$R" '[.[] | select(.id == '$BOOKING_ID')] | .[0].status')" = "COMPLETED" ]; check "booking history kept" $?

echo
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
