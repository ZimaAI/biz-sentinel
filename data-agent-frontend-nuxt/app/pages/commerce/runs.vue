<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { commerceApi } from '~/services/commerceApi';
import { useCommerceContext } from '~/composables/useCommerceContext';
import {
	COMMERCE_STATUS,
	useCommerceRunEvents,
} from '~/composables/useCommerceRunEvents';
import { dateRangeLabel } from '~/utils/commerceFormat';
import type { Run, RunEvent } from '~/types/commerce';

definePageMeta({ layout: 'commerce' });
const route = useRoute(),
	router = useRouter();
const context = useCommerceContext();
const items = ref<Run[]>([]),
	selected = ref<Run | null>(null),
	events = ref<RunEvent[]>([]);
const loading = ref(true),
	detailLoading = ref(false),
	error = ref(''),
	revoked = ref(false);
const statusFilter = ref(''),
	evidenceId = ref<string | null>(null);
const currentCursor = ref<string | undefined>(),
	nextCursor = ref<string | null>(null),
	previousCursors = ref<(string | undefined)[]>([]);
let listGeneration = 0;
let selectedId = '';
const shownItems = computed(() =>
	statusFilter.value
		? items.value.filter((item) => item.status === statusFilter.value)
		: items.value,
);
const scopeNames = (ids: string[]) =>
	ids
		.map(
			(id) =>
				context.stores.value.find((store) => store.storeId === id)?.name || id,
		)
		.join('、');
const statusTone = (status: string) =>
	status === 'SUCCEEDED'
		? 'success'
		: ['FAILED', 'ACCESS_REVOKED'].includes(status)
			? 'danger'
			: 'warning';
const tokenText = computed(() => {
	if (!selected.value || selected.value.plannerMode === 'DETERMINISTIC_LOCAL')
		return '未调用模型';
	if (!selected.value.tokenAccounting?.startsWith('PROVIDER_USAGE'))
		return '未提供';
	return `${selected.value.modelInputTokens ?? '—'} / ${selected.value.modelOutputTokens ?? '—'}`;
});
const eventNames: Record<string, string> = {
	'run.created': '运行已创建',
	'run.state.changed': '运行状态变更',
	'plan.ready': '计划已生成',
	'approval.required': '等待计划审批',
	'step.started': '工具调查开始',
	'step.summary': '工具调查完成',
	'evidence.ready': '证据已登记',
	'report.ready': '报告已生成',
	'run.completed': '运行已完成',
	'run.failed': '运行失败',
	'run.cancelled': '运行已取消',
	'access.revoked': '授权已变更',
};

function clearSensitive() {
	selected.value = null;
	items.value = [];
	events.value = [];
	evidenceId.value = null;
	selectedId = '';
	detailLoading.value = false;
	revoked.value = true;
}
function applySnapshot(run: Run) {
	selected.value = run;
	detailLoading.value = false;
	const index = items.value.findIndex((item) => item.runId === run.runId);
	if (index >= 0) items.value[index] = run;
}
const stream = useCommerceRunEvents({
	onEvent: (event) => {
		if (!events.value.some((saved) => saved.sequence === event.sequence))
			events.value.push(event);
	},
	onSnapshot: applySnapshot,
	clearSensitive,
});

async function loadList(cursor?: string) {
	const g = ++listGeneration;
	loading.value = true;
	error.value = '';
	try {
		const page = await commerceApi.listRuns({ limit: 20, cursor });
		if (g !== listGeneration) return;
		items.value = page.items;
		currentCursor.value = cursor;
		nextCursor.value = page.nextCursor;
		const routeId =
			typeof route.query.runId === 'string' ? route.query.runId : '';
		if (routeId && routeId !== selectedId) await openRun(routeId, false);
		else if (!selectedId && page.items[0]) await openRun(page.items[0].runId);
	} catch (e) {
		if (g === listGeneration) {
			error.value = e instanceof Error ? e.message : '运行列表暂时无法读取。';
			items.value = [];
		}
	} finally {
		if (g === listGeneration) loading.value = false;
	}
}

async function openRun(id: string, updateRoute = true) {
	selectedId = id;
	selected.value = null;
	events.value = [];
	evidenceId.value = null;
	detailLoading.value = true;
	revoked.value = false;
	if (updateRoute)
		await router.replace({ query: { ...route.query, runId: id } });
	await stream.start(id, { replay: true });
	detailLoading.value = false;
}
function nextPage() {
	if (!nextCursor.value || loading.value) return;
	previousCursors.value.push(currentCursor.value);
	void loadList(nextCursor.value);
}
function previousPage() {
	if (loading.value || !previousCursors.value.length) return;
	void loadList(previousCursors.value.pop());
}
function eventSummary(event: RunEvent): string {
	const payload = event.payload;
	if (typeof payload.summary === 'string') return payload.summary;
	if (typeof payload.purpose === 'string') return payload.purpose;
	if (typeof payload.message === 'string') return payload.message;
	if (typeof payload.status === 'string')
		return COMMERCE_STATUS[payload.status] || payload.status;
	if (payload.planVersion != null) return `计划版本 v${payload.planVersion}`;
	if (payload.reportId) return '结构化报告与数值引用已保存';
	if (payload.evidenceId) return '聚合结果已登记，可重新鉴权查看';
	if (payload.reason === 'PLAN_REJECTED') return '计划已被拒绝';
	return '状态与事件已持久化';
}

watch(
	() => route.query.runId,
	(id) => {
		if (typeof id === 'string' && id !== selectedId) void openRun(id, false);
	},
);
watch(
	() => `${context.me.value?.subjectId}:${context.me.value?.authzVersion}`,
	(now, previous) => {
		if (previous && now !== previous && selected.value) {
			stream.stop();
			clearSensitive();
		}
	},
);
onMounted(() => {
	void loadList();
});
onBeforeUnmount(() => {
	listGeneration++;
});
</script>

<template>
	<div class="cl-runs-page">
		<header class="cl-page-heading">
			<div>
				<h1>运行审计</h1>
				<p>核对计划、工具行动、事件与证据，回溯每一次调查。</p>
			</div>
			<NuxtLink class="cl-button soft" to="/commerce/analysis"
				><CommerceIcon name="sparkles" />打开诊断工作台</NuxtLink
			>
		</header>
		<div class="cl-banner">
			<CommerceIcon
				name="shield"
			/>运行状态与事件由服务端持久保存。事件连接仅订阅进展，断开或关闭页面不会停止已批准的调查。
		</div>
		<div v-if="revoked" class="cl-banner danger" role="alert">
			原运行的授权范围已变化，相关内容已隐藏。<button
				class="cl-button soft"
				type="button"
				@click="loadList()"
			>
				重新加载可见运行
			</button>
		</div>
		<div class="cl-run-stat-grid">
			<section class="cl-card cl-run-stat">
				<p>当前页可见运行</p>
				<strong>{{ loading ? '—' : items.length }}</strong
				><span>按创建时间排列</span>
			</section>
			<section class="cl-card cl-run-stat">
				<p>所选运行模型调用 / Token</p>
				<strong>{{
					selected
						? selected.plannerMode === 'DETERMINISTIC_LOCAL'
							? '未调用'
							: `${selected.modelAttempts || 0} 次`
						: '—'
				}}</strong
				><span>{{ tokenText }}</span>
			</section>
			<section class="cl-card cl-run-stat">
				<p>所选运行已完成步骤</p>
				<strong
					>{{ selected ? selected.steps.length : '—' }}
					<small>个</small></strong
				><span>{{
					selected ? COMMERCE_STATUS[selected.status] : '选择运行查看执行轨迹'
				}}</span>
			</section>
		</div>

		<section class="cl-card cl-runs-list-card">
			<div class="cl-runs-list-heading">
				<div>
					<h2>诊断运行</h2>
					<p>历史运行始终保留创建时的分析范围</p>
				</div>
				<div class="cl-runs-list-controls">
					<label
						><span class="cl-sr-only">筛选当前页状态</span
						><select v-model="statusFilter">
							<option value="">当前页 · 全部状态</option>
							<option
								v-for="(label, value) in COMMERCE_STATUS"
								:key="value"
								:value="value"
							>
								{{ label }}
							</option>
						</select></label
					><button
						class="cl-button"
						type="button"
						:disabled="loading"
						@click="loadList(currentCursor)"
					>
						<CommerceIcon name="refresh" />刷新
					</button>
				</div>
			</div>
			<div v-if="error" class="cl-banner danger" role="alert">
				{{ error
				}}<button
					class="cl-button soft"
					type="button"
					@click="loadList(currentCursor)"
				>
					重试
				</button>
			</div>
			<div v-else-if="loading" class="cl-runs-list-loading" aria-busy="true">
				<div v-for="n in 3" :key="n" />
			</div>
			<div v-else-if="!shownItems.length" class="cl-empty">
				{{
					statusFilter
						? '当前页没有此状态的运行，可切换筛选或翻页。'
						: '尚无可见运行，发起诊断后可在这里查看。'
				}}
			</div>
			<div v-else class="cl-runs-table-wrap">
				<table class="cl-table">
					<thead>
						<tr>
							<th>问题与运行</th>
							<th>原始范围</th>
							<th>状态</th>
							<th>创建时间</th>
							<th>操作</th>
						</tr>
					</thead>
					<tbody>
						<tr
							v-for="item in shownItems"
							:key="item.runId"
							:class="{ selected: item.runId === selectedId }"
						>
							<td>
								<strong class="cl-run-question">{{ item.question }}</strong
								><span class="cl-run-id">{{ item.runId }}</span>
							</td>
							<td>
								<span>{{ scopeNames(item.scope.storeIds) }}</span
								><span class="cl-run-id">{{
									dateRangeLabel(item.dateRange)
								}}</span>
							</td>
							<td>
								<span class="cl-badge" :class="statusTone(item.status)">{{
									COMMERCE_STATUS[item.status]
								}}</span>
							</td>
							<td>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</td>
							<td>
								<button
									class="cl-button soft"
									type="button"
									@click="openRun(item.runId)"
								>
									{{ selectedId === item.runId ? '正在查看' : '查看事件' }}
								</button>
							</td>
						</tr>
					</tbody>
				</table>
			</div>
			<div
				v-if="previousCursors.length || nextCursor"
				class="cl-run-pagination"
			>
				<button
					class="cl-button"
					:disabled="!previousCursors.length || loading"
					type="button"
					@click="previousPage"
				>
					上一页</button
				><button
					class="cl-button"
					:disabled="!nextCursor || loading"
					type="button"
					@click="nextPage"
				>
					下一页
				</button>
			</div>
		</section>

		<div
			v-if="selected || detailLoading || stream.error.value"
			class="cl-run-detail-grid"
		>
			<section class="cl-card cl-run-events">
				<div class="cl-runs-list-heading">
					<div>
						<h2>运行事件</h2>
						<p v-if="selected">
							{{ selected.runId }} · runVersion {{ selected.runVersion }}
						</p>
						<p v-else>读取保存的运行状态</p>
					</div>
					<span
						v-if="selected"
						class="cl-badge"
						:class="statusTone(selected.status)"
						>{{ COMMERCE_STATUS[selected.status] }}</span
					>
				</div>
				<div v-if="stream.error.value" class="cl-banner warning" role="status">
					{{ stream.error.value
					}}<button
						v-if="selectedId"
						class="cl-button soft"
						type="button"
						@click="openRun(selectedId, false)"
					>
						重新读取
					</button>
				</div>
				<div v-if="stream.recovered.value" class="cl-banner">
					连接已由最新运行快照恢复。如需核对完整历史，可重新读取事件。
				</div>
				<div v-if="detailLoading" class="cl-empty" aria-busy="true">
					正在校验权限并重放已保存事件…
				</div>
				<div v-else-if="!events.length" class="cl-empty">
					{{
						stream.connected.value
							? '连接已建立，等待已保存事件。'
							: '此运行暂无可展示事件。'
					}}
				</div>
				<div v-else class="cl-runs-table-wrap">
					<table class="cl-table cl-event-table">
						<thead>
							<tr>
								<th>序号</th>
								<th>事件</th>
								<th>说明</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="event in events" :key="event.sequence">
								<td>{{ event.sequence }}</td>
								<td>
									<strong>{{ eventNames[event.type] || event.type }}</strong
									><small>{{ event.type }}</small
									><time>{{
										new Date(event.occurredAt).toLocaleTimeString('zh-CN')
									}}</time>
								</td>
								<td>
									<p>{{ eventSummary(event) }}</p>
									<button
										v-if="
											typeof event.payload.evidenceId === 'string' &&
											event.payload.evidenceId
										"
										class="cl-evidence-link"
										type="button"
										@click="evidenceId = event.payload.evidenceId"
									>
										查看证据 ↗
									</button>
								</td>
							</tr>
						</tbody>
					</table>
				</div>
			</section>
			<aside v-if="selected" class="cl-card cl-run-context">
				<h2>固定运行上下文</h2>
				<div class="cl-run-context-chips">
					<span>{{ scopeNames(selected.scope.storeIds) }}</span
					><span>{{ dateRangeLabel(selected.dateRange) }}</span
					><span>对比 {{ dateRangeLabel(selected.comparisonRange) }}</span
					><span>CNY · Asia/Shanghai</span>
				</div>
				<dl>
					<dt>规划方式</dt>
					<dd>
						{{
							selected.plannerMode === 'DETERMINISTIC_LOCAL'
								? '本地确定性规划（未调用模型）'
								: '模型选择受控行动'
						}}
					</dd>
					<dt>计划版本</dt>
					<dd>
						{{ selected.plan ? `v${selected.plan.planVersion}` : '尚未生成' }}
					</dd>
					<dt>计划哈希</dt>
					<dd class="cl-run-mono">{{ selected.plan?.planHash || '—' }}</dd>
					<dt>数据版本</dt>
					<dd>{{ selected.datasetVersionId }}</dd>
					<dt>指标清单</dt>
					<dd class="cl-run-mono">{{ selected.metricManifestHash }}</dd>
					<dt>审批结果</dt>
					<dd>
						{{
							selected.approval?.decision === 'APPROVE'
								? '已确认计划'
								: selected.approval?.decision === 'REJECT'
									? '已拒绝计划'
									: '尚未审批'
						}}
					</dd>
					<dt>工具剩余预算</dt>
					<dd>{{ selected.remainingBudget.toolCalls }} 次</dd>
					<dt>Token 剩余预算</dt>
					<dd>{{ selected.remainingBudget.tokens.toLocaleString() }}</dd>
					<dt>最新事件序号</dt>
					<dd>{{ selected.latestSequence }}</dd>
				</dl>
				<div class="cl-run-context-actions">
					<NuxtLink
						class="cl-button soft"
						:to="{
							path: '/commerce/analysis',
							query: { runId: selected.runId },
						}"
						>查看诊断详情<CommerceIcon name="arrow-right" /></NuxtLink
					><NuxtLink
						v-if="selected.reportId"
						class="cl-button primary"
						:to="{
							path: '/commerce/reports',
							query: { reportId: selected.reportId },
						}"
						>打开报告<CommerceIcon name="file"
					/></NuxtLink>
				</div>
			</aside>
		</div>
		<CommerceEvidenceDrawer
			:evidence-id="evidenceId"
			@close="evidenceId = null"
		/>
	</div>
</template>

<style scoped>
.cl-run-stat-grid {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 18px;
	margin-bottom: 20px;
}
.cl-run-stat-grid > .cl-card + .cl-card {
	margin-top: 0;
}
.cl-run-stat {
	padding: 23px;
}
.cl-run-stat p {
	font-size: 12px;
	color: var(--cl-secondary);
	margin-bottom: 13px;
}
.cl-run-stat strong {
	font-size: 28px;
	line-height: 1.25;
	font-weight: 650;
	display: inline-block;
	margin-right: 12px;
}
.cl-run-stat strong small {
	font-size: 12px;
	color: var(--cl-muted);
	font-weight: 400;
}
.cl-run-stat > span {
	display: block;
	font-size: 10px;
	color: var(--cl-muted);
	margin-top: 9px;
}
.cl-runs-list-card {
	padding: 0;
	overflow: hidden;
	margin-bottom: 20px;
}
.cl-runs-list-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 16px;
	padding: 21px 23px;
}
.cl-runs-list-heading h2 {
	font-size: 17px;
	margin: 0;
}
.cl-runs-list-heading p {
	font-size: 11px;
	color: var(--cl-muted);
	margin: 6px 0 0;
	overflow-wrap: anywhere;
}
.cl-runs-list-controls {
	display: flex;
	align-items: center;
	gap: 9px;
}
.cl-runs-list-controls select {
	height: 37px;
	font-size: 11px;
	background: white;
	border: 1px solid var(--cl-border);
	color: var(--cl-secondary);
	border-radius: 8px;
	padding: 6px 12px;
}
.cl-runs-table-wrap {
	overflow: auto;
}
.cl-runs-list-card .cl-table {
	min-width: 780px;
}
.cl-run-question {
	display: block;
	font-weight: 500;
	max-width: 310px;
	white-space: nowrap;
	text-overflow: ellipsis;
	overflow: hidden;
	font-size: 12px;
}
.cl-run-id {
	display: block;
	font-size: 10px;
	color: var(--cl-muted);
	margin-top: 6px;
}
.cl-runs-list-card td:first-child,
.cl-runs-list-card th:first-child {
	padding-left: 23px;
}
.cl-runs-list-card td:last-child,
.cl-runs-list-card th:last-child {
	padding-right: 23px;
}
.cl-run-pagination {
	display: flex;
	justify-content: flex-end;
	padding: 17px 23px;
	gap: 9px;
	border-top: 1px solid var(--cl-border);
}
.cl-runs-list-card > .cl-banner {
	margin: 0 23px 23px;
}
.cl-runs-list-loading {
	padding: 5px 23px 24px;
}
.cl-runs-list-loading div {
	height: 45px;
	margin: 10px 0;
	background: #f3f5fa;
	border-radius: 6px;
}
.cl-run-detail-grid {
	display: grid;
	grid-template-columns: minmax(0, 1.8fr) minmax(290px, 1fr);
	gap: 20px;
	align-items: start;
}
.cl-run-detail-grid > .cl-card + .cl-card {
	margin-top: 0;
}
.cl-run-events {
	padding: 0;
	overflow: hidden;
	min-height: 335px;
}
.cl-run-events > .cl-banner {
	margin: 0 23px 20px;
}
.cl-event-table {
	min-width: 580px;
	white-space: normal;
}
.cl-event-table th:first-child,
.cl-event-table td:first-child {
	padding-left: 23px;
	width: 58px;
}
.cl-event-table td:nth-child(2) {
	width: 180px;
}
.cl-event-table td {
	vertical-align: top;
	padding-top: 17px;
	padding-bottom: 17px;
}
.cl-event-table strong {
	display: block;
	font-size: 11px;
	font-weight: 500;
}
.cl-event-table small,
.cl-event-table time {
	display: block;
	color: var(--cl-muted);
	font-size: 10px;
	margin-top: 4px;
}
.cl-event-table p {
	font-size: 11px;
	line-height: 1.8;
	margin: 0;
}
.cl-event-table .cl-evidence-link {
	margin-top: 9px;
}
.cl-run-context {
	padding: 23px;
}
.cl-run-context > h2 {
	font-size: 17px;
	margin: 0 0 17px;
}
.cl-run-context-chips {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-bottom: 23px;
}
.cl-run-context-chips span {
	background: #f8f9fc;
	color: var(--cl-secondary);
	padding: 4px 7px;
	border-radius: 5px;
	font-size: 10px;
}
.cl-run-context-chips span:first-child {
	color: var(--cl-primary);
	background: var(--cl-primary-soft);
}
.cl-run-context dl {
	display: grid;
	grid-template-columns: 95px minmax(0, 1fr);
	gap: 17px 10px;
	font-size: 11px;
	margin: 0;
}
.cl-run-context dt {
	color: var(--cl-muted);
}
.cl-run-context dd {
	margin: 0;
	overflow-wrap: anywhere;
}
.cl-run-mono {
	font:
		10px/1.8 ui-monospace,
		Consolas,
		monospace;
}
.cl-run-context-actions {
	display: grid;
	gap: 9px;
	margin-top: 28px;
}
.cl-sr-only {
	position: absolute;
	width: 1px;
	height: 1px;
	overflow: hidden;
	clip: rect(0, 0, 0, 0);
}
@media (max-width: 1100px) {
	.cl-run-detail-grid {
		grid-template-columns: minmax(0, 1.4fr) minmax(260px, 1fr);
	}
	.cl-run-stat {
		padding: 20px;
	}
}
@media (max-width: 900px) {
	.cl-run-detail-grid {
		grid-template-columns: 1fr;
	}
	.cl-run-context dl {
		grid-template-columns: 135px minmax(0, 1fr);
	}
}
@media (max-width: 600px) {
	.cl-run-stat-grid {
		grid-template-columns: 1fr;
		gap: 12px;
	}
	.cl-run-stat {
		padding: 18px;
	}
	.cl-run-stat p {
		margin-bottom: 8px;
	}
	.cl-run-stat strong {
		font-size: 25px;
	}
	.cl-runs-list-heading {
		flex-wrap: wrap;
		padding: 18px;
	}
	.cl-runs-list-controls {
		width: 100%;
	}
	.cl-runs-list-controls label {
		flex: 1;
	}
	.cl-runs-list-controls select {
		width: 100%;
	}
}
</style>
