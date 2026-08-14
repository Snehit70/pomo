export type Hex = string;
export type FeedKey = `${string}:${string}`;

export const POMO_SUITE_1 = 1 as const;
export const POMO_SUITE_GENERATION_1 = 1 as const;

export enum OperationKind {
  SharedPreferenceSet = 1,
}

export const OPERATION_DISPOSITIONS = [
  "ACCEPTED",
  "DUPLICATE",
  "PENDING_GAP",
  "PENDING_CAUSAL",
  "QUARANTINED_FORK",
  "REJECTED_INVALID",
  "REJECTED_UNSUPPORTED_SUITE",
] as const;

export type OperationDisposition = typeof OPERATION_DISPOSITIONS[number];
export type RejectedDisposition = Extract<OperationDisposition, `REJECTED_${string}`>;

export interface FrontierEntry {
  readonly deviceId: Hex;
  readonly incarnationId: Hex;
  readonly sequence: number;
  readonly headHash: Hex;
}

export interface UnsignedOperation {
  readonly suite: number;
  readonly suiteGeneration: number;
  readonly memberId: Hex;
  readonly deviceId: Hex;
  readonly incarnationId: Hex;
  readonly sequence: number;
  readonly previousHash: Hex | null;
  readonly frontier: readonly FrontierEntry[];
  readonly authorizationEpoch: number;
  readonly payloadSchema: number;
  readonly kind: OperationKind;
  readonly payloadHash: Hex;
}

export interface AuthenticatedOperation {
  readonly unsigned: UnsignedOperation;
  readonly payload: Uint8Array;
  readonly canonicalUnsigned: Uint8Array;
  readonly operationId: Hex;
  readonly signedEnvelope: Uint8Array;
}

export interface VerifiedCheckpointFeed {
  readonly deviceId: Hex;
  readonly incarnationId: Hex;
  readonly coveredOperationIds: readonly Hex[];
}

export interface VerifiedCheckpoint {
  readonly suite: typeof POMO_SUITE_1;
  readonly suiteGeneration: typeof POMO_SUITE_GENERATION_1;
  readonly feeds: readonly VerifiedCheckpointFeed[];
  readonly materializedPreferences: readonly {
    readonly key: string;
    readonly value: string;
  }[];
}

export interface KernelSummary {
  readonly heads: ReadonlyMap<FeedKey, { readonly sequence: number; readonly headHash: Hex | null }>;
  readonly gaps: ReadonlySet<string>;
  readonly causalWaits: ReadonlySet<string>;
  readonly forks: ReadonlySet<string>;
  readonly accepted: number;
  readonly pending: number;
  readonly quarantined: number;
  readonly dispositionCounts: ReadonlyMap<OperationDisposition, number>;
}
