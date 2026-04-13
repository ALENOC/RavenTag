/**
 * Logging Verification Test
 *
 * Manual verification that the request logger does NOT log sensitive data (e.g., tag_uid).
 *
 * This script:
 * 1. Starts a test Express server with the requestLogger middleware
 * 2. Sends a POST request with sensitive payload (tag_uid)
 * 3. Captures console.log output
 * 4. Verifies that tag_uid is NOT in the logs
 *
 * Usage: npx tsx src/__tests__/logging-verification.ts
 */

import express from 'express'
import { requestLogger } from '../middleware/logger.js'

const app = express()
app.use(express.json())
app.use(requestLogger)

app.post('/api/brand/derive-chip-key', (req, res) => {
  // Simulate backend response
  res.json({ success: true })
})

// Capture console.log output
const originalLog = console.log
let loggedOutput = ''
console.log = (...args) => {
  loggedOutput += args.join(' ') + '\n'
}

async function runVerification() {
  const PORT = 3002
  const server = app.listen(PORT)

  try {
    // Wait for server to start
    await new Promise(resolve => setTimeout(resolve, 100))

    // Send request with sensitive payload
    const response = await fetch(`http://localhost:${PORT}/api/brand/derive-chip-key`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tag_uid: 'DEADBEEF123456' })
    })

    const responseText = await response.text()
    console.log = originalLog

    // Verification checks
    console.log('=== Logging Verification Results ===\n')

    if (loggedOutput.includes('tag_uid')) {
      console.log('FAIL: tag_uid found in logs!')
      console.log('Logged output:', loggedOutput)
      process.exit(1)
    }

    if (loggedOutput.includes('DEADBEEF123456')) {
      console.log('FAIL: Sensitive payload value found in logs!')
      console.log('Logged output:', loggedOutput)
      process.exit(1)
    }

    if (!loggedOutput.includes('POST /api/brand/derive-chip-key')) {
      console.log('FAIL: Request metadata not logged!')
      console.log('Logged output:', loggedOutput)
      process.exit(1)
    }

    console.log('PASS: tag_uid is NOT in logs')
    console.log('PASS: Sensitive payload value is NOT in logs')
    console.log('PASS: Request metadata (method, path) is logged')
    console.log('\n=== All checks passed ===')
    console.log('Sample log output:', loggedOutput.trim())
  } catch (error) {
    console.log = originalLog
    console.error('Test error:', error)
    process.exit(1)
  } finally {
    server.close()
  }
}

runVerification()
