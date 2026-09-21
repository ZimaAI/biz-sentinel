import type {
	Me,
	Metric,
	Overview,
	QueryResult,
	Page,
	Run,
	CreateRun,
	Approval,
	Evidence,
	Report,
	Dataset,
	AnomalyRule,
	Anomaly,
	RuleDefinition,
	Ingestion,
	Member,
} from '~/types/commerce';

let csrf = '';
export class CommerceApiError extends Error {
	constructor(
		public status: number,
		public code: string,
		message: string,
		public details: unknown = {},
	) {
		super(message);
	}
}
interface Options {
	method?: string;
	body?: unknown;
	signal?: AbortSignal;
	headers?: Record<string, string>;
	blob?: boolean;
}
async function request<T>(path: string, options: Options = {}): Promise<T> {
	const method = options.method || 'GET';
	if (!['GET', 'HEAD'].includes(method) && !csrf) await session();
	const form = options.body instanceof FormData;
	const response = await fetch(`/api/commerce/v1${path}`, {
		method,
		credentials: 'same-origin',
		signal: options.signal,
		headers: {
			...(!form && options.body !== undefined
				? { 'Content-Type': 'application/json' }
				: {}),
			...(!['GET', 'HEAD'].includes(method) ? { 'X-CSRF-Token': csrf } : {}),
			...options.headers,
		},
		body:
			options.body === undefined
				? undefined
				: form
					? (options.body as FormData)
					: JSON.stringify(options.body),
	});
	if (!response.ok) {
		const error = await response
			.json()
			.catch(() => ({
				message: '服务暂时不可用，请稍后重试',
				code: 'NETWORK_ERROR',
			}));
		if (response.status === 401) {
			csrf = '';
			window.dispatchEvent(new Event('commerce:session-expired'));
		}
		if (error.code === 'ACCESS_REVOKED')
			window.dispatchEvent(new Event('commerce:access-revoked'));
		throw new CommerceApiError(
			response.status,
			error.code,
			error.message,
			error.details,
		);
	}
	if (options.blob) return (await response.blob()) as T;
	return (await response.json()).data as T;
}
async function session() {
	const value = await request<{ csrfToken: string; authenticated: boolean }>(
		'/auth/session',
	);
	csrf = value.csrfToken;
	return value;
}
const queryString = (params?: Record<string, string | number | undefined>) => {
	const values = new URLSearchParams();
	Object.entries(params || {}).forEach(([k, v]) => {
		if (v !== undefined) values.set(k, String(v));
	});
	return values.size ? `?${values}` : '';
};
const list = <T>(
	path: string,
	params?: Record<string, string | number | undefined>,
) => request<Page<T>>(path + queryString({ limit: 100, ...params }));
export const commerceApi = {
	request,
	session,
	async login(username: string, password: string) {
		await session();
		const data = await request<{ csrfToken: string }>('/auth/session', {
			method: 'POST',
			body: { username, password },
		});
		csrf = data.csrfToken;
	},
	async logout() {
		await request('/auth/session', { method: 'DELETE' });
		csrf = '';
	},
	me: () => request<Me>('/me'),
	listMetrics: () => request<Metric[]>('/metrics'),
	overview: (
		params: {
			storeIds: string[];
			start: string;
			endExclusive: string;
			comparison: string;
		},
		signal?: AbortSignal,
	) =>
		request<Overview>(
			'/overview' +
				queryString({ ...params, storeIds: params.storeIds.join(',') }),
			{ signal },
		),
	query: (body: unknown) =>
		request<QueryResult>('/queries', { method: 'POST', body }),
	createRun: (body: CreateRun, key: string) =>
		request<Run>('/runs', {
			method: 'POST',
			body,
			headers: { 'Idempotency-Key': key },
		}),
	getRun: (id: string) => request<Run>(`/runs/${encodeURIComponent(id)}`),
	listRuns: (params?: Record<string, string | number | undefined>) =>
		list<Run>('/runs', params),
	approveRun: (id: string, body: Approval) =>
		request<Run>(`/runs/${encodeURIComponent(id)}/approval`, {
			method: 'POST',
			body,
		}),
	cancelRun: (id: string, version: number) =>
		request<Run>(`/runs/${encodeURIComponent(id)}/cancel`, {
			method: 'POST',
			body: { expectedRunVersion: version },
		}),
	getEvidence: (id: string) =>
		request<Evidence>(`/evidence/${encodeURIComponent(id)}`),
	listReports: (params?: Record<string, string | number | undefined>) =>
		list<Report>('/reports', params),
	getReport: (id: string) =>
		request<Report>(`/reports/${encodeURIComponent(id)}`),
	exportReport: (id: string) =>
		request<Blob>(`/reports/${encodeURIComponent(id)}/export`, { blob: true }),
	shareReport: (id: string, visibility: string) =>
		request<Report>(`/reports/${encodeURIComponent(id)}/visibility`, {
			method: 'PATCH',
			body: { visibility },
		}),
	listDatasets: (params?: Record<string, string | number | undefined>) =>
		list<Dataset>('/datasets', params),
	listRules: (params?: Record<string, string | number | undefined>) =>
		list<AnomalyRule>('/anomaly-rules', params),
	createRule: (body: RuleDefinition) =>
		request<AnomalyRule>('/anomaly-rules', { method: 'POST', body }),
	updateRule: (
		id: string,
		body: { expectedVersion: number; definition: RuleDefinition },
	) =>
		request<AnomalyRule>(`/anomaly-rules/${encodeURIComponent(id)}`, {
			method: 'PATCH',
			body,
		}),
	listAnomalies: (params?: Record<string, string | number | undefined>) =>
		list<Anomaly>('/anomalies', params),
	acknowledgeAnomaly: (id: string) =>
		request<Anomaly>(`/anomalies/${encodeURIComponent(id)}/acknowledge`, {
			method: 'POST',
			body: {},
		}),
	listMonitorStatus: () => request<Page<any>>('/monitor-status'),
	upload: (file: File, key: string) => {
		const form = new FormData();
		form.append('file', file);
		return request<Ingestion>('/ingestions', {
			method: 'POST',
			body: form,
			headers: { 'Idempotency-Key': key },
		});
	},
	getIngestion: (id: string) =>
		request<Ingestion>(`/ingestions/${encodeURIComponent(id)}`),
	publishIngestion: (id: string, version: number) =>
		request<Dataset>(`/ingestions/${encodeURIComponent(id)}/publish`, {
			method: 'POST',
			body: { expectedVersion: version },
		}),
	listMembers: () => list<Member>('/members'),
	createMember: (body: unknown) =>
		request<Member>('/members', { method: 'POST', body }),
	updateMember: (id: string, body: unknown) =>
		request<Member>(`/members/${encodeURIComponent(id)}`, {
			method: 'PATCH',
			body,
		}),
};
