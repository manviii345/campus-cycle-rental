import { useState } from 'react'

// Cycle photo with a graceful fallback: a missing file or broken upload shows
// the placeholder instead of the browser's broken-image icon.
export default function Photo({ url, alt }) {
  const [broken, setBroken] = useState(false)
  if (!url || broken) return <div className="no-photo">🚲</div>
  return <img src={url} alt={alt || 'cycle'} onError={() => setBroken(true)} />
}
