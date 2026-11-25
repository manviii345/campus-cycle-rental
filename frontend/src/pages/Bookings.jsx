import { useEffect, useState } from 'react'
import { request, getUser, formatMoney } from '../api.js'

// FR-14 booking history in two tabs: "My rentals" (as renter) and
// "Requests for my cycles" (as owner). All booking actions live here:
// accept/reject (FR-11), cancel (FR-15), payment (FR-17), pickup and return
// confirmation (FR-19/20), reviews (FR-23), issue reports (FR-24).
export default function Bookings() {
  const [tab, setTab] = useState('renter')
  const [bookings, setBookings] = useState([])
  const [error, setError] = useState('')
  const me = getUser()

  async function load(role = tab) {
    setError('')
    try { setBookings(await request('GET', `/api/bookings/mine?role=${role}`)) } catch (err) { setError(err.message) }
  }
  useEffect(() => { load(tab) }, [tab]) // eslint-disable-line react-hooks/exhaustive-deps

  async function act(id, action, body) {
    setError('')
    try {
      await request('POST', `/api/bookings/${id}/${action}`, body || {})
      load()
    } catch (err) { setError(err.message) }
  }

  async function pay(id) {
    const method = window.prompt('Payment method (e.g. UPI, Cash, Card):', 'UPI')
    if (!method) return
    const reference = window.prompt('Transaction reference (optional):', '') || undefined
    act(id, 'payment', { method, transactionReference: reference })
  }

  async function review(id) {
    const rating = Number(window.prompt('Rating (1-5):', '5'))
    if (!rating || rating < 1 || rating > 5) return
    const comment = window.prompt('Short review (optional):', '') || undefined
    act(id, 'reviews', { rating, comment })
  }

  async function report(id) {
    const description = window.prompt('Describe the issue (damage, late return, safety concern):')
    if (!description) return
    act(id, 'report', { description })
  }

  return (
    <div className="page">
      <h2>Bookings</h2>
      <div className="tabs">
        <button className={tab === 'renter' ? 'active' : ''} onClick={() => setTab('renter')}>My rentals</button>
        <button className={tab === 'owner' ? 'active' : ''} onClick={() => setTab('owner')}>Requests for my cycles</button>
      </div>
      {error && <div className="error">{error}</div>}
      {bookings.length === 0 && <p className="muted">Nothing here yet.</p>}

      {bookings.map((b) => {
        const iAmOwner = b.ownerId === me.id
        return (
          <div className="card" key={b.id}>
            <h3>{b.cycleTitle} <span className={`badge ${b.status}`}>{b.status}</span></h3>
            <p className="muted">
              {b.startDate === b.endDate
                ? `${b.startDate}${b.startTime ? `, ${b.startTime} to ${b.endTime || ''}` : ''}`
                : `${b.startDate}${b.startTime ? ` ${b.startTime}` : ''} to ${b.endDate}${b.endTime ? ` ${b.endTime}` : ''}`}
              {' '}- <strong>{formatMoney(b.totalAmount)}</strong>
              {' '}- {iAmOwner ? `renter: ${b.renterName}` : `owner: ${b.ownerName}`}
            </p>

            {b.payment && (
              <p className="muted">
                Payment: {formatMoney(b.payment.amount)} via {b.payment.method || 'n/a'}
                {b.payment.transactionReference ? ` (ref ${b.payment.transactionReference})` : ''} - {b.payment.status}
              </p>
            )}

            {b.lateFeePerDay !== undefined && b.lateDays > 0 && (
              <p className="fee">
                Late return: {b.lateDays} overdue day{b.lateDays === 1 ? '' : 's'} x {formatMoney(b.lateFeePerDay)} ={' '}
                <strong>{formatMoney(b.fineAmount)}</strong>
                {b.fineFinal ? ' fine (counted up to the day the renter marked it returned; settle it between you)' : ' so far (still growing until the renter marks it returned)'}
              </p>
            )}
            {b.status === 'ACTIVE' && b.lateFeePerDay !== undefined && !b.lateDays && (
              <p className="muted">Late fine: {formatMoney(b.lateFeePerDay)} per day after {b.endDate}</p>
            )}

            {(b.status === 'ACCEPTED' || b.status === 'ACTIVE') && (
              <p className="muted">
                Pickup: owner {b.pickupOwnerConfirmedAt ? 'confirmed' : 'pending'},
                renter {b.pickupRenterConfirmedAt ? 'confirmed' : 'pending'}
                {' | '}Return: owner {b.returnOwnerConfirmedAt ? 'confirmed' : 'pending'},
                renter {b.returnRenterConfirmedAt ? 'confirmed' : 'pending'}
              </p>
            )}

            {b.otherPartyContact && (
              <p className="muted">
                Contact: {b.otherPartyContact.email}{b.otherPartyContact.phone ? `, ${b.otherPartyContact.phone}` : ''}
              </p>
            )}

            {b.reviews.length > 0 && (
              <p className="muted">
                Reviews: {b.reviews.map((r, i) => (
                  <span key={i}>{r.reviewerName} gave <span className="stars">{'★'.repeat(r.rating)}</span>{r.comment ? ` "${r.comment}"` : ''} </span>
                ))}
              </p>
            )}

            <div className="button-row">
              {iAmOwner && b.status === 'PENDING' && (
                <>
                  <button onClick={() => act(b.id, 'accept')}>Accept</button>
                  <button className="danger" onClick={() => act(b.id, 'reject')}>Reject</button>
                </>
              )}
              {!iAmOwner && (b.status === 'PENDING' || b.status === 'ACCEPTED') && (
                <button className="secondary" onClick={() => act(b.id, 'cancel')}>Cancel</button>
              )}
              {!iAmOwner && b.status === 'ACCEPTED' && !b.payment && (
                <button onClick={() => pay(b.id)}>Record payment</button>
              )}
              {b.status === 'ACCEPTED' && !(iAmOwner ? b.pickupOwnerConfirmedAt : b.pickupRenterConfirmedAt) && (
                <button onClick={() => act(b.id, 'pickup')}>Confirm pickup</button>
              )}
              {!iAmOwner && b.status === 'ACTIVE' && !b.returnRenterConfirmedAt && (
                <button onClick={() => act(b.id, 'return')}>Initiate return</button>
              )}
              {iAmOwner && b.status === 'ACTIVE' && !b.returnOwnerConfirmedAt && (
                <button onClick={() => act(b.id, 'return')} disabled={!b.returnRenterConfirmedAt}
                  title={b.returnRenterConfirmedAt ? '' : 'The renter initiates the return first'}>Mark returned</button>
              )}
              {b.status === 'COMPLETED' && (
                <button className="secondary" onClick={() => review(b.id)}>Leave a review</button>
              )}
              {(b.status === 'ACTIVE' || b.status === 'COMPLETED') && (
                <button className="secondary" onClick={() => report(b.id)}>Report an issue</button>
              )}
            </div>
          </div>
        )
      })}
    </div>
  )
}
