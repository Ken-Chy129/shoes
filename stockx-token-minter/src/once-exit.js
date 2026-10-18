'use strict';

// Only after all account operations/close handlers have settled. Give stdout a
// chance to drain, but do not let leaked upstream sockets keep a oneshot service
// alive forever (systemd timers cannot start another copy while it is active).
function finishOnce(code, graceMs = 1000) {
  process.exitCode = code;
  setTimeout(() => process.exit(code), graceMs).unref();
}

module.exports = { finishOnce };
