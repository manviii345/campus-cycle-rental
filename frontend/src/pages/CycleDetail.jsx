import { useEffect, useMemo, useState } from 'react'
import { useParams, useNavigate, Link } from 'react-router-dom'
import { request, getUser, formatMoney } from '../api.js'

// FR-08 listing details + FR-10 rental request with live price preview (FR-16, BR-09).
export default function CycleDetail() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [cycle, setCycle] = useState(null)
  const [ownerProfile, setOwnerProfile] = useState(null)
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    request('GET', `/api/cycles/${id}`).then((c) => {
      setCycle(c)
      return request('GET', `/api/users/${c.ownerId}`)
    }).then(setOwnerProfile).catch((err) => setError(err.message))
  }, [id])

  // Live price preview: days (inclusive) x price per day, shown before confirming (FR-16).
  const days = useMemo(() => {
    if (!startDate || !endDate) return 0
    const d = (new Date(endDate) - new Date(startDate)) / 86400000 + 1
    return d > 0 ? d : 0
  }, [startDate, endDate])
  const total = cycle ? days * Number(cycle.pricePerDay) : 0

  async function sendRequest(e) {
    e.preventDefault()
    setError(''); setMessage('')
    try {
      await request('POST', '/api/bookings', { cycleId: Number(id), startDate, endDate })
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
        <div className="photo-strip">
          {cycle.photos.map((p) => <img key={p.id} src={p.url} alt="cycle" />)}
          {cycle.photos.length === 0 && <span className="muted">No photos uploaded</span>}
        </div>
        <table>
          <tbody>
            <tr><th>Brand / model</th><td>{cycle.brand || '-'} {cycle.model || ''}</td></tr>
            <tr><th>Type</th><td>{cycle.cycleType || '-'}</td></tr>
            <tr><th>Condition</th><td>{cycle.condition || '-'}</td></tr>
            <tr><th>Pickup area</th><td>{cycle.location || '-'}</td></tr>
            <tr><th>Available</th><td>{cycle.availableFrom} to {cycle.availableTo}</td></tr>
            <tr><th>Price</th><td><strong>{formatMoney(cycle.pricePerDay)}</strong> per day</td></tr>
          </tbody>
        </table>
        {cycle.description && <p>{cycle.description}</p>}
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

      {me && !isOwn && cycle.status === 'AVAILABLE' && (
        <div className="card">
          <h3>Request this cycle</h3>
          {message && <div className="notice">{message}</div>}
          {error && <div className="error">{error}</div>}
          <form onSubmit={sendRequest}>
            <div className="filters">
              <div><label>Start date</label><input type="date" value={startDate} min={cycle.availableFrom} max={cycle.availableTo} onChange={(e) => setStartDate(e.target.value)} required /></div>
              <div><label>End date</label><input type="date" value={endDate} min={startDate || cycle.availableFrom} max={cycle.availableTo} onChange={(e) => setEndDate(e.target.value)} required /></div>
            </div>
            {days > 0 && (
              <p>
                {days} day{days > 1 ? 's' : ''} x {formatMoney(cycle.pricePerDay)} = <strong>{formatMoney(total)}</strong>
                <span className="muted"> (payment is recorded after the owner accepts)</span>
              </p>
            )}
            <div className="button-row">
              <button type="submit" disabled={days === 0}>Send booking request</button>
            </div>
          </form>
        </div>
      )}
    </div>
  )
}
