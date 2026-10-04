// Shapes of the files written by `sieve snapshot` (see SnapshotWriter in sieve-cli).
// Empty collections and nulls are omitted from the JSON, so most fields are optional.

export type RawType = 'INDIVIDUAL' | 'ENTITY' | 'VESSEL' | 'AIRCRAFT' | 'COMPANY' | 'ORGANIZATION' | 'CRYPTO_WALLET' | 'SECURITY';
export type RawStatus = 'LOADED' | 'EMPTY' | 'FAILED' | 'NEEDS_KEY' | 'SKIPPED';

interface Header { formatVersion: number; generatedAt: string }

export interface RawProgramCount { source: string; code: string; name?: string; entities: number }

export interface RawOverview extends Header {
  commit?: string;
  sample?: boolean;
  totalEntities: number;
  totalNames: number;
  sourcesTotal: number;
  sourcesLoaded: number;
  countries: number;
  distinctPrograms: number;
  byType?: Partial<Record<RawType, number>>;
  /** Entities per risk topic across every list, PEP and RCA records included; an entity counts for each of its topics. */
  byTopic?: Record<string, number>;
  namesByScript?: Record<string, number>;
  identifiersByType?: Record<string, number>;
  topPrograms?: RawProgramCount[];
  ingest?: { sumMs: number; longestMs: number };
  /** Cross-list matching of the published records: distinct entities, how many are on several lists, and the time it took. */
  dedup?: { distinctEntities: number; onSeveralLists: number; ms: number };
}

export interface RawCompleteness {
  total: number; withDateOfBirth: number; withNationality: number; withAddress: number; withIdentifiers: number;
  withAliases: number; withProgram: number; withListedDate: number;
}

export interface RawSource {
  source: string; displayName: string; authority: string; jurisdiction: string; format: string; homepage: string;
  status: RawStatus; error?: string; fetchMs?: number; listUri?: string; lastFetched?: string; contentHash?: string; etag?: string;
  entities: number; names?: number; byType?: Partial<Record<RawType, number>>; countries?: number;
  /** Entities whose records are in entities/ and the search index; PEP and RCA records are counted but never written. */
  published?: number; byTopic?: Record<string, number>;
  /** Published records of this list that another list also carries. */
  onOtherLists?: number;
  completeness?: RawCompleteness; topPrograms?: RawProgramCount[];
}
export interface RawSources extends Header { sources: RawSource[] }

export interface RawCountry {
  name: string; entities: number; byNationality?: number; byAddress?: number;
  bySource?: Record<string, number>; byType?: Partial<Record<RawType, number>>;
}
export interface RawCountries extends Header {
  countries?: Record<string, RawCountry>;
  unresolved?: { occurrences: number; topValues?: Record<string, number> };
}

export interface RawHistoryRow { date: string; totalEntities: number; distinctEntities?: number; totalNames: number; countries: number; bySource?: Record<string, number> }

/**
 * One search index entry: key, name, aliases, type code, source, countries, programs, shard, and for a record of an
 * entity found on several lists the group's id (the key of its first record in list order).
 */
export interface RawIndexEntry { k: string; n: string; a?: string[]; t: 'I' | 'E' | 'V' | 'A' | 'C' | 'O' | 'W' | 'S'; s: string; c?: string[]; p?: string[]; f: number; g?: string }
export interface RawIndex extends Header { shardSize: number; entries?: RawIndexEntry[] }

export interface RawName { fullName: string; nameType: string; strength?: string; script?: string }
export interface RawEntity {
  id: string; entityType: RawType; listSource: string; primaryName: RawName; aliases?: RawName[];
  addresses?: { street?: string; city?: string; stateOrProvince?: string; postalCode?: string; country?: string; fullAddress?: string }[];
  identifiers?: { type: string; value: string; issuingCountry?: string; remarks?: string }[];
  nationalities?: string[]; citizenships?: string[]; datesOfBirth?: string[]; placesOfBirth?: string[]; remarks?: string;
  programs?: { code: string; name?: string; source?: string }[]; listedDate?: string; lastUpdated?: string;
}
