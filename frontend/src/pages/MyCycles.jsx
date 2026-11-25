import { useEffect, useState } from 'react'
import { request, uploadPhoto, formatMoney } from '../api.js'

const EMPTY = {
  title: '', brand: '', model: '', cycleType: 'City', condition: 'Good',
  description: '', pricePerDay: '', location: '', availableFrom: '', availableTo: '',
}
const CYCLE_TYPES = ['City', 'Road', 'Mountain', 'Hybrid', 'Electric', 'Kids']
const CONDITIONS = ['New', 'Good', 'Fair', 'Needs service']

// FR-04 create listing, FR-05 manage it (edit, pause, remove), FR-09 availability.
export default function MyCycles() {
  const [cycles, setCycles] = useState([])
  const [form, setForm] = useState(EMPTY)
  const [editingId, setEditingId] = useState(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value })

  async function load() {
    try { setCycles(await request('GET', '/api/cycles/mine')) } catch (err) { setError(err.message) }
  }
  useEffect(() => { load() }, [])

  function startEdit(c) {
    setEditingId(c.id)
    setForm({
      title: c.title || '', brand: c.brand || '', model: c.model || '',
      cycleType: c.cycleType || 'City', condition: c.condition || 'Good',
      description: c.description || '', pricePerDay: c.pricePerDay,
      location: c.location || '', availableFrom: c.availableFrom, availableTo: c.availableTo,
    })
    window.scrollTo(0, 0)
  }

  async function submit(e) {
    e.preventDefault()
    setError(''); setNotice('')
    const payload = { ...form, pricePerDay: Number(form.pricePerDay) }
    try {
      if (editingId) {
        await request('PUT', `/api/cycles/${editingId}`, payload)
        setNotice('Listing updated.')
      } else {
        await request('POST', '/api/cycles', payload)
        setNotice('Listing created! Add photos below it.')
      }
      setForm(EMPTY); setEditingId(null)
      load()
    } catch (err) { setError(err.message) }
  }

  async function setStatus(c, status) {
    setError('')
    try { await request('PUT', `/api/cycles/${c.id}`, { status }); load() } catch (err) { setError(err.message) }
  }

  async function remove(c) {
    if (!window.confirm(`Remove "${c.title}"? Past bookings are kept for history.`)) return
    setError('')
    try { await request('DELETE', `/api/cycles/${c.id}`); load() } catch (err) { setError(err.message) }
  }

  async function addPhoto(c, file) {
    if (!file) return
    setError('')
    try { await uploadPhoto(c.id, file); load() } catch (err) { setError(err.message) }
  }

  async function deletePhoto(c, photoId) {
    setError('')
    try { await request('DELETE', `/api/cycles/${c.id}/photos/${photoId}`); load() } catch (err) { setError(err.message) }
  }

  return (
    <div className="page">
      <h2>My cycles</h2>
      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error">{error}</div>}

      <div className="card">
        <h3>{editingId ? 'Edit listing' : 'List a cycle for rent'}</h3>
        <form onSubmit={submit}>
          <div className="filters">
            <div><label>Title *</label><input value={form.title} onChange={set('title')} required placeholder="Hero Sprint 26T" /></div>
            <div><label>Brand</label><input value={form.brand} onChange={set('brand')} /></div>
            <div><label>Model</label><input value={form.model} onChange={set('model')} /></div>
            <div><label>Type</label>
              <select value={form.cycleType} onChange={set('cycleType')}>
                {CYCLE_TYPES.map((t) => <option key={t}>{t}</option>)}
              </select>
            </div>
            <div><label>Condition</label>
              <select value={form.condition} onChange={set('condition')}>
                {CONDITIONS.map((t) => <option key={t}>{t}</option>)}
              </select>
            </div>
            <div><label>Price per day (Rs.) *</label><input type="number" min="1" step="0.01" value={form.pricePerDay} onChange={set('pricePerDay')} required /></div>
            <div><label>Pickup area</label><input value={form.location} onChange={set('location')} placeholder="e.g. Main Gate, Hostel H" /></div>
            <div><label>Available from *</label><input type="date" value={form.availableFrom} onChange={set('availableFrom')} required /></div>
            <div><label>Available to *</label><input type="date" value={form.availableTo} onChange={set('availableTo')} required /></div>
          </div>
          <label>Description</label>
          <textarea value={form.description} onChange={set('description')} placeholder="Gears, lock included, ideal for campus commute..." />
          <div className="button-row">
            <button type="submit">{editingId ? 'Save changes' : 'Create listing'}</button>
            {editingId && <button type="button" className="secondary" onClick={() => { setEditingId(null); setForm(EMPTY) }}>Cancel edit</button>}
          </div>
        </form>
      </div>

      {cycles.length === 0 && <p className="muted">You have not listed any cycles yet.</p>}
      {cycles.map((c) => (
        <div className="card" key={c.id}>
          <h3>{c.title} <span className={`badge ${c.status}`}>{c.status}</span></h3>
          <p className="muted">
            {formatMoney(c.pricePerDay)}/day - {c.location || 'no area set'} - available {c.availableFrom} to {c.availableTo}
          </p>
          <div className="photo-strip">
            {c.photos.map((p) => (
              <span key={p.id}>
                <img src={p.url} alt="cycle" /><br />
                <a href="#" onClick={(e) => { e.preventDefault(); deletePhoto(c, p.id) }}>remove</a>
              </span>
            ))}
          </div>
          <div className="button-row">
            <label className="secondary" style={{ padding: '9px 16px', border: '1px solid var(--green)', borderRadius: 6, color: 'var(--green)', cursor: 'pointer', margin: 0 }}>
              Add photo
              <input type="file" accept="image/jpeg,image/png" style={{ display: 'none' }} onChange={(e) => addPhoto(c, e.target.files[0])} />
            </label>
            <button className="secondary" onClick={() => startEdit(c)}>Edit</button>
            {c.status === 'AVAILABLE'
              ? <button className="secondary" onClick={() => setStatus(c, 'PAUSED')}>Pause</button>
              : <button className="secondary" onClick={() => setStatus(c, 'AVAILABLE')}>Make available</button>}
            <button className="danger" onClick={() => remove(c)}>Remove</button>
          </div>
        </div>
      ))}
    </div>
  )
}
