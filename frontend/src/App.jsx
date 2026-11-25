import { useEffect, useState } from 'react'
import { Routes, Route, Link, Navigate, useNavigate, NavLink } from 'react-router-dom'
import { getUser, clearSession, request } from './api.js'
import Login from './pages/Login.jsx'
import Register from './pages/Register.jsx'
import Browse from './pages/Browse.jsx'
import CycleDetail from './pages/CycleDetail.jsx'
import MyCycles from './pages/MyCycles.jsx'
import Bookings from './pages/Bookings.jsx'
import Notifications from './pages/Notifications.jsx'
import Profile from './pages/Profile.jsx'

// Sends the user to /login when they open a page that needs an account.
function Protected({ children }) {
  if (!getUser()) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  const navigate = useNavigate()
  const [unread, setUnread] = useState(0)
  const user = getUser()

  // Poll unread notifications lightly while logged in (FR-21).
  useEffect(() => {
    if (!user) return
    const load = () =>
      request('GET', '/api/notifications')
        .then((list) => setUnread(list.filter((n) => !n.read).length))
        .catch(() => {})
    load()
    const timer = setInterval(load, 15000)
    return () => clearInterval(timer)
  }, [user])

  async function logout() {
    try { await request('POST', '/api/auth/logout') } catch { /* session may already be gone */ }
    clearSession()
    navigate('/login')
    window.location.reload()
  }

  return (
    <>
      <nav className="nav">
        <Link className="brand" to="/">Campus Cycle Rental</Link>
        {user ? (
          <>
            <div className="nav-links">
              <NavLink to="/" end><span className="ico">&#9635;</span>Browse</NavLink>
              <NavLink to="/my-cycles"><span className="ico">&#9881;</span>My Cycles</NavLink>
              <NavLink to="/bookings"><span className="ico">&#9776;</span>Bookings</NavLink>
              <NavLink to="/notifications"><span className="ico">&#9679;</span>Notifications{unread > 0 ? ` (${unread})` : ''}</NavLink>
            </div>
            <span className="spacer" />
            <Link to="/profile">{user.name}</Link>
            <a href="#" onClick={(e) => { e.preventDefault(); logout() }}>Log out</a>
          </>
        ) : (
          <>
            <span className="spacer" />
            <Link to="/login">Log in</Link>
            <Link to="/register">Register</Link>
          </>
        )}
      </nav>

      <Routes>
        <Route path="/" element={<Browse />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/cycles/:id" element={<CycleDetail />} />
        <Route path="/my-cycles" element={<Protected><MyCycles /></Protected>} />
        <Route path="/bookings" element={<Protected><Bookings /></Protected>} />
        <Route path="/notifications" element={<Protected><Notifications /></Protected>} />
        <Route path="/profile" element={<Protected><Profile /></Protected>} />
      </Routes>
    </>
  )
}
