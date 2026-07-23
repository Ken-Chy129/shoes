'use strict';

function exitCodeForRefresh({ succeeded, total }) {
  return succeeded === total ? 0 : 1;
}

async function runWithRetry(operation, options = {}) {
  const attempts = Math.max(1, Number(options.attempts) || 1);
  const delayMs = Math.max(0, Number(options.delayMs) || 0);
  const sleep = options.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));

  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await operation(attempt);
    } catch (error) {
      if (attempt === attempts || (options.shouldRetry && !options.shouldRetry(error))) {
        throw error;
      }
      if (options.onRetry) options.onRetry(error, attempt, attempts);
      await sleep(delayMs);
    }
  }

  throw new Error('retry loop ended unexpectedly');
}

module.exports = { exitCodeForRefresh, runWithRetry };
