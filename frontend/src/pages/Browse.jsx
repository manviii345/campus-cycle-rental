import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { request, formatMoney } from '../api.js'
import Photo from '../Photo.jsx'

const CYCLE_TYPES = ['', 'City', 'Road', 'Mountain', 'Hybrid', 'Electric', 'Kids']

// FR-06 browse + FR-07 search filters.
export default function Browse() {
  const [filters, setFilters] = useState({ query: '', type: '', location: '', minPrice: '', maxPrice: '', date: '', timeFrom: '', timeTo: '' })
  const [cycles, setCycles] = useState([])
  const [error, setError] = useState('')

  const set = (key) => (e) => setFilters({ ...filters, [key]: e.target.value })

  async function load(f = filters) {
    setError('')
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(f)) {
      if (value) params.set(key, value)
    }
    try {
      setCycles(await request('GET', '/api/cycles?' + params.toString()))
    } catch (err) {
      setError(err.message)
    }
  }

  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="page">
      <p className="eyebrow">Campus peer-to-peer rentals</p>
      <h2 className="hero">Find a cycle to rent <em>on campus.</em></h2>
      <p className="lede">Students list their own cycles. Borrow one for a few hours and hand it back when you are done.</p>
      <div className="card">
        <div className="filters">
          <div><label>Search</label><input placeholder="brand, model, keyword" value={filters.query} onChange={set('query')} /></div>
          <div><label>Type</label>
            <select value={filters.type} onChange={set('type')}>
              {CYCLE_TYPES.map((t) => <option key={t} value={t}>{t || 'Any'}</option>)}
            </select>
          </div>
          <div><label>Pickup location</label><input placeholder="campus pickup point" value={filters.location} onChange={set('location')} /></div>
          <div><label>Min price/hour</label><input type="number" min="0" value={filters.minPrice} onChange={set('minPrice')} /></div>
          <div><label>Max price/hour</label><input type="number" min="0" value={filters.maxPrice} onChange={set('maxPrice')} /></div>
          <div><label>Day</label><input type="date" value={filters.date} onChange={set('date')} /></div>
          <div><label>Pickup time</label><input type="time" step="900" value={filters.timeFrom} onChange={set('timeFrom')} disabled={!filters.date} /></div>
          <div><label>Return time</label><input type="time" step="900" value={filters.timeTo} onChange={set('timeTo')} disabled={!filters.date} /></div>
          <div><button onClick={() => load()}>Search</button></div>
        </div>
      </div>

      {error && <div className="error">{error}</div>}
      {cycles.length === 0 && !error && <p className="muted">No cycles match these filters yet.</p>}

      <div className="grid">
        {cycles.map((c) => (
          <div className="card cycle-card" key={c.id}>
            <Photo url={c.photos.length > 0 ? c.photos[0].url : null} alt={c.title} />
            <h3><Link to={`/cycles/${c.id}`}>{c.title}</Link></h3>
            <div className="muted">{c.brand} {c.model} {c.cycleType ? `- ${c.cycleType}` : ''}</div>
            <div className="muted">
              {c.location}
              {c.availability && c.availability.length > 0
                ? ` - next: ${c.availability[0].day} ${c.availability[0].pickupFrom}-${c.availability[0].returnBy}`
                : ' - no upcoming days'}
            </div>
            <p>
              <strong>{formatMoney(c.pricePerHour)}</strong>/hour
              {c.ownerRating != null && (
                <span className="muted"> &nbsp;|&nbsp; owner <span className="stars">★</span> {Number(c.ownerRating).toFixed(1)}</span>
              )}
            </p>
          </div>
        ))}
      </div>
    </div>
  )
}
