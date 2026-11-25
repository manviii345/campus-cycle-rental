import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { request, setSession } from '../api.js'

export default function Login() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const data = await request('POST', '/api/auth/login', { email, password })
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
          <h1>Your next ride is already <em>on campus.</em></h1>
          <p>Borrow a cycle from a fellow student for a class, a day or a week - and hand it back when you are done.</p>
          <ul className="hero-points">
            <li>Only Thapar email IDs can join</li>
            <li>Pick exact pickup and return times</li>
            <li>Fair late-fine rules, shown to both sides</li>
          </ul>
        </div>
        <div className="hero-bike">🚲</div>
      </div>
      <div className="auth-form">
      <div className="card">
        <h2>Log in</h2>
        {error && <div className="error">{error}</div>}
        <form onSubmit={submit}>
          <label>Thapar email ID</label>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="yourname_be24@thapar.edu" required />
          <label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          <div className="button-row">
            <button type="submit" disabled={busy}>{busy ? 'Logging in...' : 'Log in'}</button>
          </div>
        </form>
        <p className="muted">New here? <Link to="/register">Create an account</Link></p>
      </div>
      </div>
    </div>
  )
}
