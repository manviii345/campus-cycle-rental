import { useEffect, useMemo, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { request, getUser, formatMoney } from '../api.js'

// FR-08 listing details + FR-10 rental request with live price preview (FR-16, BR-09).
// Rentals are single-day: pick one of the owner's days, then pickup and return
// times (15-minute steps) inside that day's window; priced per hour.
export default function CycleDetail() {
  const { id } = useParams()
  const [cycle, setCycle] = useState(null)
  const [ownerProfile, setOwnerProfile] = useState(null)
  const [date, setDate] = useState('')
  const [startTime, setStartTime] = useState('09:00')
  const [endTime, setEndTime] = useState('18:00')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    request('GET', `/api/cycles/${id}`).then((c) => {
      setCycle(c)
      return request('GET', `/api/users/${c.ownerId}`)
    }).then(setOwnerProfile).catch((err) => setError(err.message))
  }, [id])

  // The owner's time window for the chosen day, if the cycle is available that day.
  const dayWindow = useMemo(() => {
    if (!cycle || !date) return null
    return (cycle.availability || []).find((a) => a.day === date) || null
  }, [cycle, date])

  function pickDate(value) {
    setDate(value)
    const w = (cycle.availability || []).find((a) => a.day === value)
    if (w) {
      setStartTime(w.pickupFrom)
      setEndTime(w.returnBy)
    }
  }

  // Live price preview: exact hours x hourly rate, proportional per 15 minutes (FR-16).
  const hours = useMemo(() => {
    if (!startTime || !endTime || endTime <= startTime) return 0
    const [sh, sm] = startTime.split(':').map(Number)
    const [eh, em] = endTime.split(':').map(Number)
    return ((eh * 60 + em) - (sh * 60 + sm)) / 60
  }, [startTime, endTime])
  const total = cycle ? Math.round(hours * Number(cycle.pricePerHour) * 100) / 100 : 0

  async function sendRequest(e) {
    e.preventDefault()
    setError(''); setMessage('')
    try {
      await request('POST', '/api/bookings', { cycleId: Number(id), date, startTime, endTime })
      setMessage('Booking request sent! The owner will accept or reject it. Track it under Bookings.')
    } catch (err) {
      setError(err.message)
    }
  }

  if (error && !cycle) return <div className="page"><div className="error">{error}</div></div>
  if (!cycle) return <div className="page"><p>Loading...</p></div>

  const me = getUser()
  const isOwn = me && me.id === cycle.ownerId

  return (
    <div className="page">
      <p><Link to="/">← Back to browse</Link></p>
      <div className="card">
        <h2>{cycle.title} <span className={`badge ${cycle.status}`}>{cycle.status}</span></h2>
        <div className="photo-strip photo-hero">
          {cycle.photos.map((p) => <img key={p.id} src={p.url} alt="cycle" />)}
          {cycle.photos.length === 0 && <span className="muted">No photos uploaded</span>}
        </div>
        <table>
          <tbody>
            <tr><th>Brand / model</th><td>{cycle.brand || '-'} {cycle.model || ''}</td></tr>
            <tr><th>Type</th><td>{cycle.cycleType || '-'}</td></tr>
            <tr><th>Condition</th><td>{cycle.condition || '-'}</td></tr>
            <tr><th>Pickup area</th><td>{cycle.location || '-'}</td></tr>
            <tr><th>Price</th><td><strong>{formatMoney(cycle.pricePerHour)}</strong> per hour</td></tr>
          </tbody>
        </table>
        {cycle.description && <p>{cycle.description}</p>}

        <h4>Available days</h4>
        {(!cycle.availability || cycle.availability.length === 0) && (
          <p className="muted">The owner has not added any upcoming days yet.</p>
        )}
        {(cycle.availability || []).length > 0 && (
          <table>
            <tbody>
              {cycle.availability.map((a) => (
                <tr key={a.id}><th>{a.day}</th><td>pickup from {a.pickupFrom}, return by {a.returnBy}</td></tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted">
          Listed by {cycle.ownerName}
          {cycle.ownerRating != null && <> - <span className="stars">★</span> {Number(cycle.ownerRating).toFixed(1)} ({cycle.ownerReviewCount} reviews)</>}
        </p>
      </div>

      {ownerProfile && ownerProfile.reviews.length > 0 && (
        <div className="card">
          <h3>Reviews of {ownerProfile.name}</h3>
          {ownerProfile.reviews.map((r, i) => (
            <p key={i}>
              <span className="stars">{'★'.repeat(r.rating)}{'☆'.repeat(5 - r.rating)}</span>{' '}
              {r.comment} <span className="muted">- {r.reviewerName}</span>
            </p>
          ))}
        </div>
      )}

      {!me && <div className="notice">Please <Link to="/login">log in</Link> to request this cycle.</div>}
      {isOwn && <div className="notice">This is your own listing - manage it under <Link to="/my-cycles">My Cycles</Link>.</div>}

      {me && !isOwn && cycle.status === 'AVAILABLE' && !cycle.pricingConfirmed && (
        <div className="notice">The owner has not set this cycle's hourly price and availability yet, so it cannot be booked right now.</div>
      )}

      {me && !isOwn && cycle.status === 'AVAILABLE' && cycle.pricingConfirmed && (
        <div className="card">
          <h3>Request this cycle</h3>
          {message && <div className="notice">{message}</div>}
          {error && <div className="error">{error}</div>}
          <form onSubmit={sendRequest}>
            <div className="filters">
              <div><label>Day</label><input type="date" value={date} onChange={(e) => pickDate(e.target.value)} required /></div>
              <div><label>Pickup time</label><input type="time" step="900" value={startTime}
                min={dayWindow ? dayWindow.pickupFrom : undefined} max={dayWindow ? dayWindow.returnBy : undefined}
                onChange={(e) => setStartTime(e.target.value)} required /></div>
              <div><label>Return time</label><input type="time" step="900" value={endTime}
                min={dayWindow ? dayWindow.pickupFrom : undefined} max={dayWindow ? dayWindow.returnBy : undefined}
                onChange={(e) => setEndTime(e.target.value)} required /></div>
            </div>
            {date && !dayWindow && (
              <p className="muted">This cycle is not available on {date} - pick one of the owner's days above.</p>
            )}
            {dayWindow && hours > 0 && (
              <p>
                {hours} hour{hours === 1 ? '' : 's'} x {formatMoney(cycle.pricePerHour)} = <strong>{formatMoney(total)}</strong>
                <span className="muted"> &nbsp;on {date}, pickup {startTime}, return by {endTime}</span>
                <span className="muted"> (payment is recorded after the owner accepts)</span>
              </p>
            )}
            <div className="button-row">
              <button type="submit" disabled={!dayWindow || hours === 0}>Send booking request</button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
