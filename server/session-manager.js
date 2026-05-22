import { TerminalSession } from './terminal-session.js';

export class SessionManager {
  constructor() {
    this.sessions = new Map();
    this.nextID = 1;
  }

  list() {
    return [...this.sessions.values()].map((session) => session.info());
  }

  get(id) {
    return this.sessions.get(id);
  }

  create({ name, cwd }) {
    const id = `s${this.nextID++}`;
    const session = new TerminalSession({
      id,
      name,
      cwd,
      onExit: (sessionID) => this.sessions.delete(sessionID),
    });
    this.sessions.set(id, session);
    return session;
  }

  rename(id, name) {
    const session = this.get(id);
    if (!session) return null;
    session.rename(name);
    return session;
  }

  close(id) {
    const session = this.get(id);
    if (!session) return false;
    session.close();
    this.sessions.delete(id);
    return true;
  }
}
