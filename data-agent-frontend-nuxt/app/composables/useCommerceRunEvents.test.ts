import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Run, RunEvent } from '../types/commerce';

const mocks = vi.hoisted(() => ({
	getRun: vi.fn(),
	cleanup: [] as (() => void)[],
}));
vi.mock('../services/commerceApi', () => ({
	commerceApi: { getRun: mocks.getRun },
}));
vi.mock('vue', async (importOriginal) => ({
	...(await importOriginal<typeof import('vue')>()),
	onBeforeUnmount: (callback: () => void) => {
		mocks.cleanup.push(callback);
	},
}));

import { useCommerceRunEvents } from './useCommerceRunEvents';

class FakeEventSource {
	static instances: FakeEventSource[] = [];
	onopen: (() => void) | null = null;
	onerror: (() => void) | null = null;
	listeners = new Map<string, ((event: MessageEvent) => void)[]>();
	close = vi.fn();
	constructor(public url: string) {
		FakeEventSource.instances.push(this);
	}
	addEventListener(type: string, listener: (event: MessageEvent) => void) {
		this.listeners.set(type, [...(this.listeners.get(type) || []), listener]);
	}
	emit(type: string, value: RunEvent | string) {
		const raw = {
			data: typeof value === 'string' ? value : JSON.stringify(value),
		} as MessageEvent;
		this.listeners.get(type)?.forEach((listener) => listener(raw));
	}
}

function run(overrides: Partial<Run> = {}): Run {
	return {
		runId: 'run-allowed',
		status: 'RUNNING',
		runVersion: 1,
		latestSequence: 0,
		steps: [],
		...overrides,
	} as Run;
}
function event(
	sequence: number,
	type = 'step.started',
	overrides: Partial<RunEvent> = {},
): RunEvent {
	return {
		schemaVersion: '1.0',
		runId: 'run-allowed',
		sequence,
		type,
		occurredAt: '2026-09-21T01:00:00Z',
		payload: {},
		...overrides,
	};
}
function harness() {
	const handlers = {
		onEvent: vi.fn(),
		onSnapshot: vi.fn(),
		clearSensitive: vi.fn(),
	};
	return { stream: useCommerceRunEvents(handlers), handlers };
}
async function flush() {
	for (let i = 0; i < 12; i++) await Promise.resolve();
}

describe('Commerce SSE subscription', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		mocks.getRun.mockReset();
		mocks.getRun.mockResolvedValue(run());
		mocks.cleanup.length = 0;
		FakeEventSource.instances = [];
		vi.stubGlobal('EventSource', FakeEventSource);
	});
	afterEach(() => {
		mocks.cleanup.forEach((callback) => callback());
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	it('uses the canonical snapshot cursor and discards duplicate committed events', async () => {
		mocks.getRun.mockResolvedValue(run({ latestSequence: 8 }));
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		const source = FakeEventSource.instances[0]!;
		expect(source.url).toBe('/api/commerce/v1/runs/run-allowed/events?after=8');
		expect(source.url).not.toMatch(/token|session|authorization/i);
		source.onopen?.();
		source.emit('step.started', event(9));
		source.emit('step.started', event(9));
		await flush();
		expect(handlers.onEvent).toHaveBeenCalledTimes(1);
		expect(stream.lastSequence.value).toBe(9);
		expect(stream.connected.value).toBe(true);
	});

	it('rebuilds from a snapshot on a sequence gap and reconnects after the saved cursor', async () => {
		mocks.getRun
			.mockResolvedValueOnce(run())
			.mockResolvedValue(run({ latestSequence: 7, runVersion: 3 }));
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		const first = FakeEventSource.instances[0]!;
		first.emit('step.started', event(3));
		await flush();
		expect(first.close).toHaveBeenCalled();
		expect(handlers.onEvent).not.toHaveBeenCalled();
		expect(handlers.onSnapshot).toHaveBeenLastCalledWith(
			expect.objectContaining({ latestSequence: 7 }),
		);
		expect(stream.recovered.value).toBe(true);
		expect(stream.lastSequence.value).toBe(7);
		await vi.advanceTimersByTimeAsync(1000);
		expect(FakeEventSource.instances[1]?.url).toBe(
			'/api/commerce/v1/runs/run-allowed/events?after=7',
		);
	});

	it('recovers transport failure with bounded backoff without creating or approving runs', async () => {
		const { stream } = harness();
		await stream.start('run-allowed');
		for (let attempt = 0; attempt < 7; attempt++) {
			const source = FakeEventSource.instances.at(-1)!;
			source.onerror?.();
			await flush();
			if (attempt < 6)
				await vi.advanceTimersByTimeAsync(Math.min(1000 * 2 ** attempt, 15000));
		}
		expect(stream.error.value).toContain('手动重连');
		expect(stream.connected.value).toBe(false);
		const count = FakeEventSource.instances.length;
		await vi.advanceTimersByTimeAsync(60000);
		expect(FakeEventSource.instances).toHaveLength(count);
		expect(mocks.getRun).toHaveBeenCalledTimes(8);
	});

	it('clears sensitive caches immediately on ACCESS_REVOKED even if its sequence skips history', async () => {
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		const source = FakeEventSource.instances[0]!;
		source.emit('access.revoked', event(42, 'access.revoked'));
		await flush();
		expect(handlers.clearSensitive).toHaveBeenCalledTimes(1);
		expect(handlers.onEvent).not.toHaveBeenCalled();
		expect(source.close).toHaveBeenCalledTimes(1);
		expect(stream.lastSequence.value).toBe(42);
		// A queued stale business event must not repopulate the page after clearance.
		source.emit('step.started', event(43));
		expect(handlers.onEvent).not.toHaveBeenCalled();
		expect(mocks.getRun).toHaveBeenCalledTimes(1);
	});

	it('loads the final snapshot and closes after a terminal event', async () => {
		mocks.getRun
			.mockResolvedValueOnce(run())
			.mockResolvedValue(
				run({
					status: 'SUCCEEDED',
					latestSequence: 1,
					runVersion: 5,
					reportId: 'report-allowed',
				}),
			);
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		const source = FakeEventSource.instances[0]!;
		source.emit('run.completed', event(1, 'run.completed'));
		await flush();
		expect(handlers.onSnapshot).toHaveBeenLastCalledWith(
			expect.objectContaining({
				reportId: 'report-allowed',
				status: 'SUCCEEDED',
			}),
		);
		expect(source.close).toHaveBeenCalled();
		expect(stream.connected.value).toBe(false);
		const count = FakeEventSource.instances.length;
		source.onerror?.();
		await vi.advanceTimersByTimeAsync(30000);
		expect(FakeEventSource.instances).toHaveLength(count);
	});

	it('replays historical events for audit even when the snapshot is already terminal', async () => {
		mocks.getRun.mockResolvedValue(
			run({ status: 'CANCELLED', latestSequence: 2 }),
		);
		const { stream, handlers } = harness();
		await stream.start('run-allowed', { replay: true });
		const source = FakeEventSource.instances[0]!;
		expect(source.url).toContain('after=0');
		source.emit('run.created', event(1, 'run.created'));
		source.emit('run.cancelled', event(2, 'run.cancelled'));
		await flush();
		expect(
			handlers.onEvent.mock.calls.map(([value]) => value.sequence),
		).toEqual([1, 2]);
		expect(source.close).toHaveBeenCalled();
	});

	it('hides cached content when a reconnect snapshot becomes forbidden', async () => {
		mocks.getRun
			.mockResolvedValueOnce(run())
			.mockRejectedValueOnce(
				Object.assign(new Error('资源不可见'), { status: 404 }),
			);
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		FakeEventSource.instances[0]!.onerror?.();
		await flush();
		expect(handlers.clearSensitive).toHaveBeenCalledTimes(1);
		expect(stream.error.value).toBe('资源不可见');
		await vi.advanceTimersByTimeAsync(30000);
		expect(FakeEventSource.instances).toHaveLength(1);
	});

	it('rejects a mismatched run envelope and closes listeners during component disposal', async () => {
		const { stream, handlers } = harness();
		await stream.start('run-allowed');
		const source = FakeEventSource.instances[0]!;
		source.emit(
			'step.started',
			event(1, 'step.started', { runId: 'other-run' }),
		);
		expect(stream.error.value).toContain('校验失败');
		expect(handlers.onEvent).not.toHaveBeenCalled();
		await stream.start('run-allowed');
		const next = FakeEventSource.instances[1]!;
		mocks.cleanup[0]!();
		next.emit('step.started', event(1));
		expect(next.close).toHaveBeenCalled();
		expect(handlers.onEvent).not.toHaveBeenCalled();
	});
});
