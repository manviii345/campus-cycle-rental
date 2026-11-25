import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { request, setSession } from '../api.js'

// FR-01: one account type - every user can both list cycles and rent them.
export default function Register() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', email: '', phone: '', password: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value })

  async function submit(e) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const data = await request('POST', '/api/auth/register', form)
      setSession(data.token, data.user)
      navigate('/')
      window.location.reload()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-wrap">
      <div className="auth-hero">
        <div>
          <p className="eyebrow">Thapar campus rentals</p>
          <h1>List your cycle. <em>Rent theirs.</em></h1>
          <p>One account does both. Your cycle earns while you sit in class, and you get a ride when you need one.</p>
          <ul className="hero-points">
            <li>Free to join with your Thapar email ID</li>
            <li>You set your price and availability</li>
            <li>Both sides confirm pickup and return</li>
          </ul>
        </div>
        <div className="hero-bike">🚲</div>
      </div>
      <div className="auth-form">
      <div className="card">
        <h2>Create your account</h2>
        {error && <div className="error">{error}</div>}
        <form onSubmit={submit}>
          <label>Full name</label>
          <input value={form.name} onChange={set('name')} required />
          <label>Thapar email ID</label>
          <input type="email" value={form.email} onChange={set('email')} placeholder="yourname_be24@thapar.edu" required />
          <p className="muted">Only Thapar University email IDs (ending in @thapar.edu) can create an account.</p>
          <label>Phone</label>
          <input value={form.phone} onChange={set('phone')} />
          <label>Password (min 6 characters)</label>
          <input type="password" value={form.password} onChange={set('password')} required />
          <div className="button-row">
            <button type="submit" disabled={busy}>{busy ? 'Creating...' : 'Register'}</button>
          </div>
        </form>
        <p className="muted">Already have an account? <Link to="/login">Log in</Link></p>
      </div>
      </div>
    </div>
  )
}
