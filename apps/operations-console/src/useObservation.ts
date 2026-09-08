import { useEffect, useState } from 'react';

/** A bounded, non-overlapping observation loop. Failed reads retain the previous value with an explicit error. */
export function useObservation<T>(load: () => Promise<T>, refreshToken = 0) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [observedAt, setObservedAt] = useState<string>();
  useEffect(() => {
    let live = true, backoff = 5000;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      if (!live) return;
      setLoading(true);
      try {
        const value = await load();
        if (!live) return;
        setData(value); setObservedAt(new Date().toISOString()); setError(undefined); backoff = 5000;
      } catch (failure) {
        if (!live) return;
        setError(failure instanceof Error ? failure.message : 'Observations are unavailable.'); backoff = Math.min(backoff * 2, 30000);
      } finally {
        if (live) { setLoading(false); timer = setTimeout(poll, backoff); }
      }
    };
    void poll();
    return () => { live = false; clearTimeout(timer); };
  }, [load, refreshToken]);
  return { data, error, loading, observedAt };
}
