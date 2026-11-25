import { useEffect, useState } from 'react'
import { request } from '../api.js'

// FR-21 notifications: booking requests, decisions, payment and handover updates.
export default function Notifications() {
  const [items, setItems] = useState([])
  const [error, setError] = useState('')

  async function load() {
    try {
      const list = await request('GET', '/api/notifications')
      setItems(list)
      if (list.some((n) => !n.read)) await request('POST', '/api/notifications/read-all')
    } catch (err) { setError(err.message) }
  }
  useEffect(() => { load() }, [])

  return (
    <div className="page">
      <h2>Notifications</h2>
      {error && <div className="error">{error}</div>}
      {items.length === 0 && <p className="muted">No notifications yet.</p>}
      {items.map((n) => (
        <div className="card" key={n.id} style={{ opacity: n.read ? 0.75 : 1 }}>
          <strong>{n.message}</strong>
          <div className="muted">{n.type.replaceAll('_', ' ').toLowerCase()} - {n.createdAt}</div>
        </div>
      ))}
    </div>
  )
}
