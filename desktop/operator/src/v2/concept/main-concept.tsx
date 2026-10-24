import React from 'react'
import ReactDOM from 'react-dom/client'

import '@/index.css'
import '../v2.css'
import SaleConcept from './SaleConcept'

// Концепция кассы v2 на подставных данных — только для показа (concept.html).
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <SaleConcept />
  </React.StrictMode>,
)
