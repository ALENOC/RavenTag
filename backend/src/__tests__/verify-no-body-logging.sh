#!/bin/bash
# Logging Verification Script
# Verifies that request logger does NOT log request bodies

echo "=== Backend Logging Verification ==="
echo ""

# Check 1: Verify SECURITY comment exists
if ! grep -q "SECURITY: Request logger NEVER logs request bodies" src/middleware/logger.ts; then
  echo "FAIL: SECURITY comment not found in logger.ts"
  exit 1
fi
echo "PASS: SECURITY comment found in logger.ts"

# Check 2: Verify no req.body logging
if grep -q "req\.body" src/middleware/logger.ts; then
  echo "FAIL: req.body found in logger.ts"
  echo "Lines:"
  grep -n "req\.body" src/middleware/logger.ts
  exit 1
fi
echo "PASS: No req.body logging in logger.ts"

# Check 3: Verify no res.body logging
if grep -q "res\.body" src/middleware/logger.ts; then
  echo "FAIL: res.body found in logger.ts"
  echo "Lines:"
  grep -n "res\.body" src/middleware/logger.ts
  exit 1
fi
echo "PASS: No res.body logging in logger.ts"

# Check 4: Verify only metadata is logged
if ! grep -q "method, path, status, duration, ip" src/middleware/logger.ts; then
  echo "FAIL: Metadata logging not documented"
  exit 1
fi
echo "PASS: Metadata logging documented (method, path, status, duration, ip)"

echo ""
echo "=== All checks passed ==="
echo "The request logger only logs metadata, never request bodies."
