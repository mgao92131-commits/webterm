export function keyEventData(event: KeyboardEvent): string {
  if (event?.key && event.key.length === 1) return event.key;
  return ({
    Space: " ",
    Enter: "\r",
    Tab: "\t",
    Escape: "\x1b",
  } as Record<string, string>)[event?.key] || "";
}

export function modifiedInput(modifier: string, data: string): string {
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
  } as Record<string, string>)[data] || "";
}

export function quickbarInput(modifier: string | null, key: string): string {
  const base = ({
    Esc: "\x1b",
    Tab: "\t",
    "Shift Tab": "\x1b[Z",
    "Ctrl C": "\x03",
    "Ctrl D": "\x04",
    "Ctrl Z": "\x1a",
    "Ctrl X": "\x18",
    Enter: "\r",
    "/": "/",
    "↑": "\x1b[A",
    "↓": "\x1b[B",
    "←": "\x1b[D",
    "→": "\x1b[C",
  } as Record<string, string>)[key] || (key.length === 1 ? key : "");
  
  if (!base) return "";
  if (modifier === "ctrl") return modifiedInput("ctrl", base) || base;
  return base;
}
