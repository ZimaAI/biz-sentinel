/** Target Commerce API subset. Generate full types from contracts/openapi.yaml. */
export type DecimalString = string;
export type MetricId = 'paid_gmv' | 'paid_orders' | 'aov' | 'refund_amount'
  | 'net_receipts' | 'refund_intensity' | 'visitor_sessions' | 'order_conversion_rate';
export interface DateRange { start: string; endExclusive: string }
export interface Scope { tenantId: string; storeIds: string[]; authzVersion: number }
export interface Cell { field: string; value: DecimalString | null; unit: 'CNY_CENT'|'COUNT'|'RATIO'|'PP'; reason?: string | null }
export interface ResultRow { rowKey: string; dimensions: Record<string,string>; cells: Cell[] }
export type RunStatus = 'QUEUED'|'PLANNING'|'WAITING_APPROVAL'|'RUNNING'|'CANCEL_REQUESTED'
  |'SUCCEEDED'|'PARTIAL'|'FAILED'|'CANCELLED'|'EXPIRED'|'ACCESS_REVOKED';
export interface Plan {
  planVersion: number; planHash: string; dateRange: DateRange; scope: Scope;
  actions: { actionId: string; toolName: string; purpose: string; maxCalls: number }[];
  maxToolCalls: number; maxTokens: number; expiresAt: string;
}
export interface Run {
  runId: string; conversationId: string; status: RunStatus; runVersion: number;
  question: string; scope: Scope; datasetVersionId: string; metricManifestHash: string;
  plan: Plan | null; latestSequence: number; eventsUrl: string;
  reportId?: string | null; createdAt: string;
}
export interface CreateRun {
  question: string; requestedStoreIds: string[]; dateRange: DateRange;
  comparison: 'PREVIOUS_WEEK_SAME_DAYS'|'PREVIOUS_PERIOD'; requireApproval: boolean;
}
export interface Approval {
  decision: 'APPROVE'|'REJECT'; planVersion: number; planHash: string;
  expectedRunVersion: number; comment?: string;
}
export interface RunEvent {
  schemaVersion: '1.0'; runId: string; sequence: number; type: string;
  occurredAt: string; payload: Record<string,unknown>;
}
export interface Envelope<T> { requestId: string; data: T }
