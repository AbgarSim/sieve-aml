import { fetchJson } from './snapshot';

/** A news article that mentions a record's name in an adverse context, as news.json holds it. */
export interface NewsArticle {
  url: string;
  title?: string;
  domain?: string;
  language?: string;
  seenAt: string;
  terms?: string[];
  mentionedAs?: string;
}

/** news.json: recent articles per record key, newest first. */
export interface RawNews {
  formatVersion: number;
  generatedAt?: string;
  days?: number;
  records?: Record<string, NewsArticle[]>;
}

let pending: Promise<RawNews> | null = null;

/** Loads news.json once. A snapshot written before the file existed has no articles. */
export function loadNews(): Promise<RawNews> {
  pending ??= fetchJson<RawNews>('news.json')
    .catch(e => { if (String(e).includes('HTTP 404')) return { formatVersion: 1 } as RawNews; throw e; })
    .catch(e => { pending = null; throw e; });
  return pending;
}
