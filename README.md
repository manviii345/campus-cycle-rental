# CycleRent - Peer-to-Peer Cycle Rental Management System

A web application where individuals list their own bicycles for rent and rent
bicycles listed by others. One account per person - every user can act as an
owner, a renter, or both (SRS BR-01). There is no admin or central fleet.

Built per the SRS: `Software Requirements Specification - Peer-to-Peer Cycle
Rental Management System, v1.0`.

## Tech stack

| Layer     | Technology                                                        |
|-----------|-------------------------------------------------------------------|
| Backend   | Pure Java 17 (JDK `HttpServer` + JDBC - no Spring, no frameworks) |
| Database  | MySQL 8 (SQLite option for a zero-setup demo)                     |
| Frontend  | React 18 + Vite, plain CSS                                        |

The only third-party jars are the database drivers (in `backend/lib/`):
`mysql-connector-j` and `sqlite-jdbc`. Everything else - HTTP routing, JSON,
password hashing, sessions - is hand-written standard JDK code, which makes it
easy to walk through at an evaluation.

## Project layout

```
cyclerental/
  backend/
    src/main/java/com/cyclerental/
      Main.java            entry point + HTTP router + static file serving
      Db.java              JDBC access layer + auto-created schema
      Json.java            hand-written JSON parser/writer
      Auth.java            PBKDF2 password hashing + session tokens
      Api.java / ApiException.java   HTTP helpers, typed errors
      AuthHandlers.java    register, login, logout, profile (FR-01..03)
      CycleHandlers.java   listings, search/filters, photos (FR-04..09)
      BookingHandlers.java bookings, payment, handover, reviews (FR-10..20, 23, 24)
      MiscHandlers.java    notifications, public profiles, health (FR-21)
    lib/                   JDBC driver jars (included)
    uploads/               uploaded cycle photos (created at runtime)
    smoke-test.sh          27-check end-to-end API test
  frontend/
    src/pages/             Login, Register, Browse, CycleDetail, MyCycles,
                           Bookings, Notifications, Profile
    src/api.js             fetch wrapper (Bearer token in localStorage)
    dist/                  production build (already built - ready to serve)
  README.md
```

## Running it locally (the eval demo)

### 1. Database - pick one

**Option A: MySQL (the course stack)**

1. Install MySQL 8 and start it.
2. Create the database:
   ```sql
   CREATE DATABASE cyclerental;
   ```
3. The app connects to `jdbc:mysql://localhost:3306/cyclerental` as `root`
   with an empty password by default. Override with environment variables if
   your setup differs:
   ```bash
   export DB_USER=root
   export DB_PASSWORD=yourpassword
   ```

Tables are created automatically on first run - no migration scripts needed.
Databases from the pre-hourly version upgrade in place on first start: the old daily
price is converted to an hourly starting rate (daily / 24), but such listings stay
unconfirmed and accept no new bookings until the owner explicitly sets the hourly
price (My Cycles shows an "unconfirmed" hint). No availability days are invented for
them; the owner adds rentable days. Existing bookings are untouched.

**Option B: zero-setup SQLite (handy for a quick demo)**

```bash
export DB_URL='jdbc:sqlite:cyclerental.db'
```

Same code, same queries; only the JDBC URL changes.

### 2. Backend

Requires JDK 17 or newer (`javac -version` to check).

```bash
cd backend
javac -cp "lib/*" -d bin src/main/java/com/cyclerental/*.java
java -cp "bin:lib/*" com.cyclerental.Main
```

You should see: `Cycle Rental server running at http://localhost:8080`

### 3. Frontend

The production build is already included in `frontend/dist`, and the backend
serves it - **just open http://localhost:8080**.

To change the frontend and rebuild:

```bash
cd frontend
npm install
npm run build        # outputs to frontend/dist, served by the backend
```

For live-reload development instead: `npm run dev` (Vite on port 5173, API
calls proxied to the backend on 8080).

### 4. Optional: run the API test suite

With the server running:

```bash
cd backend
BASE=http://localhost:8080 bash smoke-test.sh
```

Checks covering registration (incl. 10-digit phone validation), login, listings,
per-day availability windows, photos, search filters (incl. single-day time search),
hourly price calculation with 15-minute steps, booking flow, overlap prevention,
legacy-listing gating (a converted daily rate stays unconfirmed until the owner sets
the hourly price), payment, two-sided pickup/return, reviews, notifications, issue
reports, and history retention. Expect `Passed: 83`.
The late-return fine test (SQLite only, it edits dates in the database file) is run with
`BASE=http://localhost:8080 DB_FILE=/path/to/cyclerental.db ./smoke-late.sh` and expects `Passed: 5`.

## Demo flow for the eval (two browsers or one browser + incognito)

1. Register **Asha** and **Ram** with Thapar-style emails (e.g. `asha_be24@thapar.edu`,
   `ram_be24@thapar.edu`) - only `@thapar.edu` addresses can create accounts.
2. As Asha: My Cycles - create a listing (title, price per hour, location),
   add a photo, then add rentable days, each with a pickup/return time window.
3. As Ram: Browse - search with filters (one day + pickup/return time), open the
   listing, pick a day and times, see the live price calculation (exact hours x the
   hourly rate), send the booking request (status PENDING).
4. As Asha: Bookings - "Requests for my cycles" - **Accept**.
5. As Ram: Bookings - **Record payment** (method UPI, any reference).
6. Both sides click **Confirm pickup** - status becomes ACTIVE.
7. Both sides click **Confirm return** - status becomes COMPLETED.
8. Both sides **Leave a review** - the owner's rating now shows on listings.
9. Show Notifications on both accounts.
10. To show conflict prevention: create a second renter account and request
    overlapping dates - the system rejects it (FR-13).

## How the requirements map to the code

| SRS item | Where |
|---|---|
| FR-01/02 register, login/logout | `AuthHandlers`, PBKDF2 hashing in `Auth` (NFR-01) |
| FR-03 profile update | `AuthHandlers.updateMe`, Profile page |
| FR-04/05 listing create/edit/pause/remove | `CycleHandlers`, MyCycles page (owner-only, BR-02) |
| FR-06/07 browse + filters | `CycleHandlers.search` (query, type, location, hourly price, one day + times) |
| FR-08 listing details + owner info | `CycleHandlers.getOne`, CycleDetail page |
| FR-09 availability | `cycle_availability` table: the owner adds days, each with a pickup/return window |
| FR-10/11 request + accept/reject | `BookingHandlers.create/accept/reject` |
| FR-12 statuses | PENDING, ACCEPTED, REJECTED, CANCELLED, ACTIVE, COMPLETED |
| FR-13 no overlapping confirmed bookings | `hasOverlap()` checked at request **and** accept time (BR-05, NFR-05) |
| FR-14/15 history + cancellation | `listMine`, `cancel` (before pickup only) |
| FR-16 price calculation | exact time x hourly rate (proportional per 15 minutes), previewed before submit (BR-09) |
| FR-17/18 payment recording | `recordPayment` - manual record per SRS 2.3 (no gateway) |
| FR-19/20 pickup/return | two-sided confirmation, both parties must confirm |
| FR-21 notifications | in-app notifications on every status change |
| FR-23 ratings/reviews | after COMPLETED only, once per party (BR-08) |
| FR-24 issue reporting | `reportIssue` on active/completed bookings |
| BR-03 can't rent own cycle | checked in `create` |
| BR-10 history survives removal | listings are soft-deleted (`status=REMOVED`) |
| NFR-02 authorization | every mutation checks ownership/party membership |
| NFR-03 privacy | email/phone shared only after a booking is accepted |
| NFR-12 auditability | `created_at`/`updated_at` + timestamps on handover events |

Out of scope (per SRS section 8): admin role, GPS tracking, smart locks,
payment gateway integration, in-app chat.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:mysql://localhost:3306/cyclerental?...` | JDBC connection |
| `DB_USER` / `DB_PASSWORD` | `root` / empty | DB credentials |
| `UPLOAD_DIR` | `uploads` | photo storage folder |
| `FRONTEND_DIR` | `../frontend/dist` | built frontend to serve |

## Notes

- Photo uploads are JPEG/PNG only, max 12 MB, stored in `uploads/` and served
  at `/photos/<file>`.
- Sessions are random Bearer tokens stored in the `sessions` table; logging
  out revokes them.
- All SQL uses prepared statements with bound parameters (no string-built
  queries), so SQL injection is not possible.

## Thapar-only accounts (campus rule)

Only Thapar University email IDs (ending in `@thapar.edu`) can create an account
or log in. Registering or logging in with any other address (Gmail, Yahoo, etc.)
shows a clear error. This is an address-format rule, not mailbox verification -
the site does not send verification emails. To try the app you must register
first; there are no pre-made accounts.

## Late-return fine

Rs. 50 per overdue day (set `LATE_FEE_PER_DAY` to change it). The first overdue day is the day after the
booking's end date. Days are counted up to the day the renter initiates the return, so the owner's
confirmation delay is never charged. The amount is calculated and shown on the Bookings page; it is not
collected or enforced by the system.

## Credits

Demo photo and font credits are in `CREDITS.md`.
