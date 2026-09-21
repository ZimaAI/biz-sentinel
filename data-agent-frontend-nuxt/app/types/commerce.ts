export type DecimalString = string;
export type MetricId =
	| 'paid_gmv'
	| 'paid_orders'
	| 'aov'
	| 'refund_amount'
	| 'net_receipts'
	| 'refund_intensity'
	| 'visitor_sessions'
	| 'order_conversion_rate';
export interface DateRange {
	start: string;
	endExclusive: string;
}
export interface Scope {
	tenantId: string;
	storeIds: string[];
	authzVersion: number;
}
export interface Store {
	storeId: string;
	name: string;
	platform: string;
	businessTimezone?: string;
}
export interface Me {
	subjectId: string;
	tenantId: string;
	displayName: string;
	roles: string[];
	authzVersion: number;
	stores: Store[];
}
export interface Cell {
	field: string;
	value: string | null;
	unit: 'CNY_CENT' | 'COUNT' | 'RATIO' | 'PP';
	reason?: string | null;
}
export interface ResultRow {
	rowKey: string;
	dimensions: Record<string, string>;
	cells: Cell[];
}
export interface Quality {
	status: 'COMPLETE' | 'PARTIAL' | 'STALE' | 'INVALID';
	businessWatermark: string;
	publishedAt: string;
	warnings: string[];
	missingCoverage?: {
		source: string;
		storeId: string;
		date?: string;
		reason: string;
	}[];
	sourceStatus?: Record<string, string>;
}
export interface Metric {
	metricId: MetricId;
	version: number;
	displayName: string;
	unit: Cell['unit'];
	definition: string;
	supportedDimensions: string[];
	requiredFacts: string[];
	zeroDenominatorPolicy: string;
	note: string;
	compilerKey: string;
	state: string;
}
export interface QuerySpec {
	metricIds: string[];
	dimensions: string[];
	dateRange: DateRange;
	comparison: string;
	filters: unknown[];
	limit: number;
}
export interface QueryResult {
	columns: { field: string; label: string; unit?: string }[];
	rows: ResultRow[];
	totals: ResultRow;
	dateRange: DateRange;
	comparisonRange: DateRange | null;
	scope: Scope;
	datasetVersionId: string;
	metricVersions: Record<string, number>;
	quality: Quality;
	evidenceId: string;
	queryArtifactId: string;
	rowCount: number;
	truncated: boolean;
}
export interface Overview {
	scope: Scope;
	dateRange: DateRange;
	comparisonRange: DateRange;
	datasetVersionId: string;
	synthetic: boolean;
	quality: Quality;
	totals: ResultRow;
	baselineTotals: ResultRow;
	trend: ResultRow[];
	stores: ResultRow[];
	evidenceId: string;
	trendEvidenceId: string;
	trendRange: DateRange;
	trendComparisonRange: DateRange;
	metricVersions: Record<string, number>;
	metricManifestHash: string;
	summary: {
		storeId?: string;
		storeName?: string;
		deltaCents?: string;
		totalDeltaCents?: string;
		netContributionRatio?: string | null;
		evidenceId?: string;
		type?: string;
	} | null;
}
export interface Page<T> {
	items: T[];
	nextCursor: string | null;
}
export type RunStatus =
	| 'QUEUED'
	| 'PLANNING'
	| 'WAITING_APPROVAL'
	| 'RUNNING'
	| 'CANCEL_REQUESTED'
	| 'SUCCEEDED'
	| 'PARTIAL'
	| 'FAILED'
	| 'CANCELLED'
	| 'EXPIRED'
	| 'ACCESS_REVOKED';
export interface Plan {
	planVersion: number;
	planHash: string;
	scope: Scope;
	dateRange: DateRange;
	actions: {
		actionId: string;
		toolName: string;
		purpose: string;
		maxCalls: number;
	}[];
	maxToolCalls: number;
	maxTokens: number;
	expiresAt: string;
}
export interface Run {
	runId: string;
	conversationId: string;
	status: RunStatus;
	runVersion: number;
	question: string;
	scope: Scope;
	datasetVersionId: string;
	metricManifestHash: string;
	dateRange: DateRange;
	comparisonRange: DateRange;
	comparison: string;
	plan: Plan | null;
	latestSequence: number;
	eventsUrl: string;
	reportId?: string | null;
	createdAt: string;
	plannerMode: string;
	steps: any[];
	remainingBudget: { toolCalls: number; tokens: number; activeMillis: number };
	usage?: any;
	clarification?: { message: string; suggestedQuestion: string };
	error?: { code: string; message: string };
	[key: string]: any;
}
export interface CreateRun {
	question: string;
	requestedStoreIds: string[];
	dateRange: DateRange;
	comparison: string;
	requireApproval: boolean;
}
export interface Approval {
	decision: 'APPROVE' | 'REJECT';
	planVersion: number;
	planHash: string;
	expectedRunVersion: number;
	comment?: string;
}
export interface RunEvent {
	schemaVersion: '1.0';
	runId: string;
	sequence: number;
	type: string;
	occurredAt: string;
	payload: Record<string, any>;
}
export interface Claim {
	claimId: string;
	type:
		| 'OBSERVATION'
		| 'DECOMPOSITION'
		| 'HYPOTHESIS'
		| 'LIMITATION'
		| 'RECOMMENDATION';
	text: string;
	evidenceRefs: string[];
	numericBindings: {
		evidenceId: string;
		rowKey: string;
		field: string;
		transformId: string;
	}[];
	support: 'SUPPORTED' | 'CONTRADICTED' | 'INSUFFICIENT';
}
export interface Report {
	reportId: string;
	version: number;
	runId: string;
	title: string;
	status: string;
	createdAt: string;
	scope: Scope;
	dateRange: DateRange;
	comparisonRange: DateRange;
	datasetVersionId: string;
	metricManifestHash: string;
	claims: Claim[];
	limitations: string[];
	synthetic: boolean;
	visibility?: 'PRIVATE' | 'TEAM';
	[key: string]: any;
}
export interface Evidence {
	evidenceId: string;
	runId: string | null;
	kind: string;
	datasetVersionId: string;
	metricManifestHash: string;
	scope: Scope;
	result: QueryResult;
	sqlTemplate: string | null;
	redactedParameters: Record<string, unknown>;
	resultHash: string;
	durationMs: number;
	createdAt: string;
	supportedClaims?: unknown[];
	[key: string]: any;
}
export interface Dataset {
	datasetVersionId: string;
	status: string;
	coverage: DateRange & { completeBusinessDates?: string[] };
	quality: Quality;
	tables: { name: string; rowCount: number }[];
	stores: Store[];
	synthetic: boolean;
	scope: Scope;
	[key: string]: any;
}
export interface RuleDefinition {
	name: string;
	metricId: string;
	storeIds: string[];
	direction: 'UP' | 'DOWN';
	baseline: 'SAME_WEEKDAY_MEDIAN_8';
	relativeThreshold: string | null;
	absoluteThreshold: string;
	unit: 'CNY_CENT' | 'COUNT' | 'PP';
	cooldownHours: number;
	enabled: boolean;
}
export interface AnomalyRule {
	ruleId: string;
	version: number;
	definition: RuleDefinition;
	scope?: Scope;
	[key: string]: any;
}
export interface Anomaly {
	anomalyId: string;
	ruleId: string;
	ruleVersion: number;
	status: string;
	severity: string;
	scope: Scope;
	metricId: string;
	window: DateRange;
	referenceDates: string[];
	current: Cell;
	baseline: Cell;
	datasetVersionId: string;
	reason: string;
	[key: string]: any;
}
export interface Ingestion {
	jobId: string;
	version: number;
	status: string;
	datasetVersionId: string;
	filesValidated: number;
	errors: { file: string; line: number; code: string; message: string }[];
	[key: string]: any;
}
export interface Member {
	subjectId: string;
	displayName: string;
	storeIds: string[];
	roles: string[];
	authzVersion: number;
	enabled: boolean;
}
