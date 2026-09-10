import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import './global.css'

// Design tokens first: every component style resolves against --dsw-*.
import '../vendor/theme/styles/base.css'
import '../vendor/theme/styles/corner-shape.css'
import '../vendor/theme/styles/design-platform.css'
import '../vendor/theme/styles/gradient-shadow-text.css'
import '../vendor/theme/styles/scrollbar.css'
import '../vendor/theme/styles/shiki.css'

import { App } from './App'

const el = document.getElementById('root')
if (el === null) throw new Error('webui: missing #root')
createRoot(el).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
