/**
 * Cross-platform system dialog for approving incoming connections.
 *
 * macOS:   osascript (AppleScript dialog)
 * Linux:   zenity or kdialog
 * Windows: PowerShell MessageBox
 */

import { execFile } from 'node:child_process';

/**
 * Show a system confirmation dialog.
 * @param {string} title   - Dialog title
 * @param {string} message - Dialog body text
 * @returns {Promise<boolean>} true if user clicks OK/Yes, false otherwise
 */
export function showConfirmDialog(title, message) {
  switch (process.platform) {
    case 'darwin':
      return macDialog(title, message);
    case 'win32':
      return winDialog(title, message);
    default:
      return linuxDialog(title, message);
  }
}

function macDialog(title, message) {
  const script = `display dialog "${escapeAppleScript(message)}" with title "${escapeAppleScript(title)}" buttons {"Deny", "Allow"} default button "Allow"`;
  return new Promise((resolve) => {
    execFile('osascript', ['-e', script], (err, stdout) => {
      if (err) {
        resolve(false);
        return;
      }
      resolve(stdout.includes('Allow'));
    });
  });
}

function winDialog(title, message) {
  const ps = `
    Add-Type -AssemblyName System.Windows.Forms
    $result = [System.Windows.Forms.MessageBox]::Show("${escapePS(message)}", "${escapePS(title)}", "YesNo", "Question")
    Write-Output $result
  `;
  return new Promise((resolve) => {
    execFile('powershell', ['-NoProfile', '-Command', ps], (err, stdout) => {
      if (err) {
        resolve(false);
        return;
      }
      resolve(stdout.trim() === 'Yes');
    });
  });
}

function linuxDialog(title, message) {
  return new Promise((resolve) => {
    // Try zenity first, then kdialog
    execFile('zenity', ['--question', '--title', title, '--text', message, '--width', '300'], (err) => {
      if (err && err.code === 'ENOENT') {
        // zenity not found, try kdialog
        execFile('kdialog', ['--yesno', message, '--title', title], (err2) => {
          resolve(!err2);
        });
        return;
      }
      resolve(!err);
    });
  });
}

function escapeAppleScript(text) {
  return String(text).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function escapePS(text) {
  return String(text).replace(/`/g, '``').replace(/"/g, '`"');
}
