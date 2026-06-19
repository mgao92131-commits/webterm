export function keyEventData(event) {
  if (event?.key && event.key.length === 1) return event.key;
  return ({
    Space: " ",
    Enter: "\r",
    Tab: "\t",
    Escape: "\x1b",
  })[event?.key] || "";
}

export function modifiedInput(modifier, data) {
  if (String(data || "").length !== 1) return "";
  if (modifier === "alt") return `\x1b${data}`;
  if (modifier !== "ctrl") return "";
  if (/^[a-zA-Z]$/.test(data)) {
    return String.fromCharCode(data.toUpperCase().charCodeAt(0) - 64);
  }
  return ({
    "2": "\x00",
    "3": "\x1b",
    "4": "\x1c",
    "5": "\x1d",
    "6": "\x1e",
    "7": "\x1f",
    "8": "\x7f",
  })[data] || "";
}

export function quickbarInput(modifier, key) {
  const base = ({
    Esc: "\x1b",
    Tab: "\t",
    "Shift Tab": "\x1b[Z",
    "Ctrl C": "\x03",
    "/": "/",
    "↑": "\x1b[A",
    "↓": "\x1b[B",
    "←": "\x1b[D",
    "→": "\x1b[C",
  })[key] || "";
  if (!base) return "";
  if (modifier === "ctrl") return modifiedInput("ctrl", base) || base;
  return base;
}
