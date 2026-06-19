import db from '../db.js';

const insertUserStmt = db.prepare(`
  INSERT INTO users (username, password_hash, role, email_verified_at)
  VALUES (?, ?, ?, ?)
`);

const findByUsernameStmt = db.prepare(`
  SELECT id, username, password_hash AS passwordHash, role, disabled, email_verified_at AS emailVerifiedAt, created_at AS createdAt
  FROM users
  WHERE username = ?
`);

const findByIdStmt = db.prepare(`
  SELECT id, username, password_hash AS passwordHash, role, disabled, email_verified_at AS emailVerifiedAt, created_at AS createdAt
  FROM users
  WHERE id = ?
`);

const updateDisabledStmt = db.prepare(`
  UPDATE users
  SET disabled = ?
  WHERE id = ?
`);

const listAdminsStmt = db.prepare(`
  SELECT id, username, role, disabled, email_verified_at AS emailVerifiedAt, created_at AS createdAt
  FROM users
  WHERE role = 'admin'
`);

const verifyUserEmailStmt = db.prepare(`
  UPDATE users
  SET email_verified_at = ?
  WHERE id = ?
`);

export function createUser(username, passwordHash, role = 'user', emailVerifiedAt = null) {
  const info = insertUserStmt.run(username, passwordHash, role, emailVerifiedAt);
  return {
    id: info.lastInsertRowid,
    username,
    role,
    disabled: 0,
    emailVerifiedAt
  };
}

export function findByUsername(username) {
  return findByUsernameStmt.get(username) || null;
}

export function findById(id) {
  return findByIdStmt.get(id) || null;
}

export function setDisabled(id, disabled) {
  const result = updateDisabledStmt.run(disabled ? 1 : 0, id);
  return result.changes > 0;
}

export function listAdmins() {
  return listAdminsStmt.all();
}

export function verifyUserEmail(id, verifiedAt = new Date().toISOString()) {
  const result = verifyUserEmailStmt.run(verifiedAt, id);
  return result.changes > 0;
}

const updatePasswordStmt = db.prepare(`
  UPDATE users
  SET password_hash = ?
  WHERE id = ?
`);

export function updatePassword(id, passwordHash) {
  const result = updatePasswordStmt.run(passwordHash, id);
  return result.changes > 0;
}


