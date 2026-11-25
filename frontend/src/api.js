// Thin wrapper around fetch() that talks to the Java backend.
// The login token is kept in localStorage and sent as a Bearer token.

export function getToken() {
  return localStorage.getItem('token')
}

export function setSession(token, user) {
  localStorage.setItem('token', token)
  localStorage.setItem('user', JSON.stringify(user))
}

export function getUser() {
  const raw = localStorage.getItem('user')
  return raw ? JSON.parse(raw) : null
}

export function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
}

// request('GET', '/api/cycles') or request('POST', '/api/bookings', {cycleId: 1, ...})
// Throws Error with the server's message when the request fails.
export async function request(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = 'Bearer ' + token

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  let data = null
  try {
    data = await res.json()
  } catch {
    // non-JSON response (e.g. frontend not built yet)
  }
  if (!res.ok) {
    throw new Error((data && data.error) || `Request failed (${res.status})`)
  }
  return data
}

// Photo upload: raw image bytes, not JSON.
export async function uploadPhoto(cycleId, file) {
  const res = await fetch(`/api/cycles/${cycleId}/photos`, {
    method: 'POST',
    headers: {
      'Content-Type': file.type,
      'Authorization': 'Bearer ' + getToken(),
    },
    body: file,
  })
  const data = await res.json().catch(() => null)
  if (!res.ok) throw new Error((data && data.error) || 'Upload failed')
  return data
}

export function formatDate(value) {
  if (!value) return '-'
  return String(value).slice(0, 10)
}

export function formatMoney(value) {
  return 'Rs. ' + Number(value).toLocaleString('en-IN')
}
