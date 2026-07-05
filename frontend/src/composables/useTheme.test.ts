import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { reactive, nextTick } from 'vue';
import { useTheme } from './useTheme';
import type { Theme } from '../config';

function createFakeStore(theme: Theme = 'solarized') {
  return reactive({ theme });
}

describe('useTheme', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('data-theme');
    localStorage.clear();
  });

  afterEach(() => {
    document.documentElement.removeAttribute('data-theme');
    localStorage.clear();
  });

  it('sets data-theme and persists theme on initial call', async () => {
    const store = createFakeStore('dracula');

    useTheme(store);
    await nextTick();

    expect(document.documentElement.dataset.theme).toBe('dracula');
    expect(localStorage.getItem('webterm-theme')).toBe('dracula');
  });

  it('reacts to theme changes', async () => {
    const store = createFakeStore('solarized');

    useTheme(store);
    await nextTick();
    expect(document.documentElement.dataset.theme).toBe('solarized');

    store.theme = 'dracula';
    await nextTick();

    expect(document.documentElement.dataset.theme).toBe('dracula');
    expect(localStorage.getItem('webterm-theme')).toBe('dracula');
  });
});
