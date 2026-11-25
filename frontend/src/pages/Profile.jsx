import { useEffect, useState } from 'react'
import { request, setSession, getUser, getToken } from '../api.js'

// FR-03 profile management + the user's public rating summary (FR-23).
export default function Profile() {
  const [me, setMe] = useState(getUser())
  const [profile, setProfile] = useState(null)
  const [form, setForm] = useState({ name: getUser()?.name || '', phone: getUser()?.phone || '' })
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    request('GET', `/api/users/${me.id}`).then(setProfile).catch(() => {})
  }, [me.id])

  async function save(e) {
    e.preventDefault()
    setError(''); setNotice('')
    try {
      const updated = await request('PUT', '/api/me', form)
      setSession(getToken(), updated)
      setMe(updated)
      setNotice('Profile updated.')
    } catch (err) { setError(err.message) }
  }

  return (
    <div className="page-narrow">
      <div className="card">
        <h2>My profile</h2>
        {notice && <div className="notice">{notice}</div>}
        {error && <div className="error">{error}</div>}
        <p className="muted">{me.email}</p>
        {profile && (
          <p className="muted">
            {profile.reviewCount > 0
              ? <>Rating: <span className="stars">★</span> {Number(profile.avgRating).toFixed(1)} from {profile.reviewCount} reviews</>
              : 'No reviews yet'}
          </p>
        )}
        <form onSubmit={save}>
          <label>Full name</label>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          <label>Phone</label>
          <input value={form.phone || ''} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          <div className="button-row"><button type="submit">Save</button></div>
        </form>
      </div>
    </div>
  )
}
