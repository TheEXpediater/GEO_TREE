import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

/** Filters and paging live in the URL, so a reload or a shared link keeps the same view. */
export function useUrlState<T extends Record<string, string>>(defaults: T) {
  const [params, setParams] = useSearchParams();
  const state = Object.fromEntries(Object.entries(defaults).map(([key, value]) => [key, params.get(key) ?? value])) as T;

  const update = useCallback(
    (changes: Partial<T>, resetPage = true) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          for (const [key, value] of Object.entries(changes)) {
            if (value === undefined || value === "" || value === defaults[key]) next.delete(key);
            else next.set(key, String(value));
          }
          if (resetPage && !("page" in changes)) next.delete("page");
          return next;
        },
        { replace: true },
      );
    },
    // defaults is a literal at each call site
    [setParams],
  );
  return [state, update] as const;
}

export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const handle = window.setTimeout(() => setDebounced(value), delay);
    return () => window.clearTimeout(handle);
  }, [value, delay]);
  return debounced;
}

/** A search box bound to a URL parameter, debounced before it changes the query. */
export function useSearchBox(value: string, commit: (value: string) => void) {
  const [text, setText] = useState(value);
  const debounced = useDebounced(text);
  useEffect(() => {
    if (debounced !== value) commit(debounced);
  }, [debounced]);
  useEffect(() => setText(value), [value]);
  return [text, setText] as const;
}
