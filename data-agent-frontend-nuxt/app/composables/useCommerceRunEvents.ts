import { onBeforeUnmount, ref } from 'vue';
import { commerceApi } from '../services/commerceApi';
import type { Run, RunEvent } from '../types/commerce';

const EVENT_TYPES = [
	'run.created',
	'run.state.changed',
	'plan.ready',
	'approval.required',
	'step.started',
	'step.summary',
	'evidence.ready',
	'report.ready',
	'run.completed',
	'run.failed',
	'run.cancelled',
	'access.revoked',
];
export const COMMERCE_TERMINAL = new Set([
	'SUCCEEDED',
	'PARTIAL',
	'FAILED',
	'CANCELLED',
	'EXPIRED',
	'ACCESS_REVOKED',
]);
export const COMMERCE_STATUS: Record<string, string> = {
	QUEUED: '等待执行',
	PLANNING: '生成计划中',
	WAITING_APPROVAL: '等待审批',
	RUNNING: '调查进行中',
	CANCEL_REQUESTED: '正在取消',
	SUCCEEDED: '已完成',
	PARTIAL: '部分完成',
	FAILED: '执行失败',
	CANCELLED: '已取消',
	EXPIRED: '审批已过期',
	ACCESS_REVOKED: '授权已变更',
};

/** A read-only subscription. Worker execution never depends on this browser connection. */
export function useCommerceRunEvents(handlers: {
	onEvent: (event: RunEvent) => void;
	onSnapshot: (run: Run) => void;
	clearSensitive: () => void;
}) {
	const connected = ref(false);
	const error = ref('');
	const lastSequence = ref(0);
	const recovered = ref(false);
	let source: EventSource | null = null;
	let retry: ReturnType<typeof setTimeout> | undefined;
	let generation = 0;
	let runId = '';
	let attempts = 0;
	let recovering = false;
	let snapshotVersion = 0;

	function stop() {
		generation++;
		source?.close();
		source = null;
		connected.value = false;
		clearTimeout(retry);
		retry = undefined;
		recovering = false;
	}

	async function snapshot(g: number) {
		const run = await commerceApi.getRun(runId);
		if (g !== generation) return null;
		if (run.status === 'ACCESS_REVOKED') {
			handlers.clearSensitive();
			stop();
			return null;
		}
		if (run.runVersion >= snapshotVersion) {
			snapshotVersion = run.runVersion;
			handlers.onSnapshot(run);
		}
		return run;
	}

	function connect(g: number) {
		if (g !== generation || typeof EventSource === 'undefined') return;
		source = new EventSource(
			`/api/commerce/v1/runs/${encodeURIComponent(runId)}/events?after=${lastSequence.value}`,
		);
		source.onopen = () => {
			if (g === generation) {
				connected.value = true;
				error.value = '';
			}
		};
		for (const type of EVENT_TYPES)
			source.addEventListener(type, (raw) => {
				void consume(raw as MessageEvent, type, g);
			});
		source.onerror = () => {
			void recover(g);
		};
	}

	async function consume(raw: MessageEvent, type: string, g: number) {
		if (g !== generation) return;
		let event: RunEvent;
		try {
			event = JSON.parse(raw.data) as RunEvent;
		} catch {
			error.value = '事件格式异常，请重新连接。';
			stop();
			return;
		}
		if (
			event.schemaVersion !== '1.0' ||
			event.runId !== runId ||
			event.type !== type ||
			!Number.isSafeInteger(event.sequence) ||
			event.sequence < 1
		) {
			error.value = '事件校验失败，请重新连接。';
			stop();
			return;
		}
		if (event.sequence <= lastSequence.value) return;
		// Revocation intentionally omits historical business events, so it may skip their sequence numbers.
		if (type === 'access.revoked') {
			lastSequence.value = event.sequence;
			handlers.clearSensitive();
			error.value = '授权范围已变化，运行内容已隐藏。';
			stop();
			return;
		}
		if (event.sequence !== lastSequence.value + 1) {
			await recover(g);
			return;
		}
		lastSequence.value = event.sequence;
		attempts = 0;
		handlers.onEvent(event);
		if (
			[
				'run.state.changed',
				'plan.ready',
				'approval.required',
				'step.summary',
				'report.ready',
				'run.completed',
				'run.failed',
				'run.cancelled',
			].includes(type)
		) {
			try {
				await snapshot(g);
			} catch (e) {
				if (g === generation) handleError(e);
			}
		}
		if (
			g === generation &&
			['run.completed', 'run.failed', 'run.cancelled'].includes(type)
		)
			stop();
	}

	function handleError(e: unknown) {
		const status = (e as { status?: number }).status;
		if (status && [401, 403, 404].includes(status)) handlers.clearSensitive();
		error.value = e instanceof Error ? e.message : '运行状态暂时无法读取。';
		stop();
	}

	async function recover(g: number) {
		if (g !== generation || recovering) return;
		recovering = true;
		source?.close();
		source = null;
		connected.value = false;
		try {
			const run = await snapshot(g);
			if (!run || g !== generation) return;
			lastSequence.value = run.latestSequence;
			recovered.value = true;
			if (COMMERCE_TERMINAL.has(run.status)) {
				stop();
				return;
			}
			if (++attempts > 6) {
				error.value = '连接暂未恢复；服务端仍会继续执行，可手动重连。';
				stop();
				return;
			}
			error.value = '连接中断，正在根据已保存状态恢复…';
			retry = setTimeout(
				() => connect(g),
				Math.min(1000 * 2 ** (attempts - 1), 15000),
			);
		} catch (e) {
			if (g === generation) handleError(e);
		} finally {
			if (g === generation) recovering = false;
		}
	}

	async function start(id: string, options: { replay?: boolean } = {}) {
		stop();
		runId = id;
		lastSequence.value = 0;
		snapshotVersion = 0;
		attempts = 0;
		error.value = '';
		recovered.value = false;
		const g = generation;
		try {
			const run = await snapshot(g);
			if (!run || g !== generation) return;
			lastSequence.value = options.replay ? 0 : run.latestSequence;
			if (options.replay || !COMMERCE_TERMINAL.has(run.status)) connect(g);
		} catch (e) {
			if (g === generation) handleError(e);
		}
	}

	onBeforeUnmount(stop);
	return { connected, error, recovered, lastSequence, start, stop };
}
