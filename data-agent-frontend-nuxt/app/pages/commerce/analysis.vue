<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import { useCommerceContext } from '~/composables/useCommerceContext';
import {
	COMMERCE_STATUS,
	COMMERCE_TERMINAL,
	useCommerceRunEvents,
} from '~/composables/useCommerceRunEvents';
import { dateRangeLabel } from '~/utils/commerceFormat';
import type { DateRange, Report, Run, RunEvent } from '~/types/commerce';

definePageMeta({ layout: 'commerce' });
const route = useRoute(),
	router = useRouter();
const context = useCommerceContext();
const question = ref('分析当前范围支付 GMV 的变化和主要贡献因素，并给出证据。');
const run = ref<Run | null>(null),
	report = ref<Report | null>(null);
const loading = ref(false),
	busy = ref(false),
	error = ref(''),
	notice = ref('');
const evidenceId = ref<string | null>(null),
	events = ref<RunEvent[]>([]);
const approvalComment = ref(''),
	confirmCancel = ref(false),
	revoked = ref(false);
let reportGeneration = 0;
let requestKey = '',
	requestFingerprint = '';
const canAnalyze = computed(
	() =>
		context.me.value?.roles.some((role) =>
			['TENANT_ADMIN', 'OPS_MANAGER', 'STORE_OPERATOR'].includes(role),
		) || false,
);
const isTerminal = computed(
	() => !!run.value && COMMERCE_TERMINAL.has(run.value.status),
);
const sourceStores = computed(() =>
	typeof route.query.stores === 'string'
		? route.query.stores.split(',').filter(Boolean)
		: null,
);
const sourceDates = computed<DateRange | null>(() =>
	typeof route.query.start === 'string' &&
	typeof route.query.endExclusive === 'string'
		? { start: route.query.start, endExclusive: route.query.endExclusive }
		: null,
);
const analysisStores = computed(
	() => sourceStores.value || context.selectedStoreIds.value,
);
const analysisDates = computed(
	() => sourceDates.value || context.dateRange.value,
);
const analysisComparison = computed(() =>
	typeof route.query.comparison === 'string'
		? route.query.comparison
		: context.comparison.value,
);
const activeStep = computed(() => {
	const start = [...events.value]
		.reverse()
		.find((e) => e.type === 'step.started');
	if (
		!start ||
		run.value?.steps.some(
			(step) => step.action?.actionId === start.payload.actionId,
		)
	)
		return null;
	return start.payload;
});
const reportSummary = computed(
	() =>
		report.value?.claims
			.filter((claim) => !['LIMITATION', 'RECOMMENDATION'].includes(claim.type))
			.slice(0, 5) || [],
);
const tokenText = computed(() => {
	if (!run.value || run.value.plannerMode === 'DETERMINISTIC_LOCAL')
		return '未调用模型';
	if (!run.value.tokenAccounting?.startsWith('PROVIDER_USAGE')) return '未提供';
	return `${run.value.modelInputTokens ?? '—'} 输入 / ${run.value.modelOutputTokens ?? '—'} 输出`;
});
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

function clearSensitive() {
	reportGeneration++;
	report.value = null;
	run.value = null;
	events.value = [];
	evidenceId.value = null;
	revoked.value = true;
	loading.value = false;
}
async function loadReport(id: string) {
	const g = ++reportGeneration;
	try {
		const value = await commerceApi.getReport(id);
		if (g === reportGeneration) report.value = value;
	} catch (e) {
		if (g === reportGeneration) {
			report.value = null;
			error.value = e instanceof Error ? e.message : '报告读取失败。';
		}
	}
}
function applySnapshot(value: Run) {
	run.value = value;
	loading.value = false;
	if (value.reportId && value.reportId !== report.value?.reportId)
		void loadReport(value.reportId);
}
const stream = useCommerceRunEvents({
	onEvent: (event) => events.value.push(event),
	onSnapshot: applySnapshot,
	clearSensitive,
});

async function openRun(id: string) {
	stream.stop();
	reportGeneration++;
	run.value = null;
	report.value = null;
	events.value = [];
	evidenceId.value = null;
	loading.value = true;
	revoked.value = false;
	error.value = '';
	confirmCancel.value = false;
	await stream.start(id);
	loading.value = false;
}

async function createRun() {
	if (busy.value || !canAnalyze.value) return;
	error.value = '';
	notice.value = '';
	if (!question.value.trim()) {
		error.value = '请先输入需要调查的经营问题。';
		return;
	}
	if (!analysisDates.value.start || !analysisStores.value.length) {
		error.value = '请先选择已发布数据覆盖的日期与授权店铺。';
		return;
	}
	busy.value = true;
	const body = {
		question: question.value.trim(),
		requestedStoreIds: [...analysisStores.value],
		dateRange: { ...analysisDates.value },
		comparison: analysisComparison.value,
		requireApproval: true,
	};
	const fingerprint = JSON.stringify(body);
	if (!requestKey || requestFingerprint !== fingerprint) {
		requestKey = crypto.randomUUID();
		requestFingerprint = fingerprint;
	}
	try {
		const created = await commerceApi.createRun(body, requestKey);
		requestKey = '';
		requestFingerprint = '';
		revoked.value = false;
		await router.replace({ query: { ...route.query, runId: created.runId } });
		if (run.value?.runId !== created.runId) await openRun(created.runId);
	} catch (e) {
		error.value = e instanceof Error ? e.message : '运行创建失败，请重试。';
	} finally {
		busy.value = false;
	}
}

async function approve(decision: 'APPROVE' | 'REJECT') {
	const current = run.value;
	if (!current?.plan || busy.value) return;
	busy.value = true;
	error.value = '';
	notice.value = '';
	try {
		const updated = await commerceApi.approveRun(current.runId, {
			decision,
			planVersion: current.plan.planVersion,
			planHash: current.plan.planHash,
			expectedRunVersion: current.runVersion,
			comment: approvalComment.value.trim() || undefined,
		});
		applySnapshot(updated);
		notice.value =
			decision === 'APPROVE'
				? '计划已确认，服务端开始调查。'
				: '已拒绝此计划，可调整问题后重新发起。';
		await stream.start(current.runId);
	} catch (e) {
		error.value =
			e instanceof CommerceApiError && e.status === 409
				? '运行或计划已更新，已重新加载；请核对最新范围后再次确认。'
				: e instanceof Error
					? e.message
					: '审批未完成。';
		try {
			applySnapshot(await commerceApi.getRun(current.runId));
		} catch {
			clearSensitive();
		}
	} finally {
		busy.value = false;
	}
}

async function cancelRun() {
	const current = run.value;
	if (!current || busy.value) return;
	busy.value = true;
	error.value = '';
	try {
		applySnapshot(
			await commerceApi.cancelRun(current.runId, current.runVersion),
		);
		confirmCancel.value = false;
		await stream.start(current.runId);
	} catch (e) {
		error.value = e instanceof Error ? e.message : '取消请求未成功。';
		try {
			applySnapshot(await commerceApi.getRun(current.runId));
		} catch {
			clearSensitive();
		}
	} finally {
		busy.value = false;
	}
}

async function refreshAnalysis() {
	if (run.value) {
		await openRun(run.value.runId);
		return;
	}
	try {
		await context.refresh();
		error.value = '';
	} catch (e) {
		error.value = e instanceof Error ? e.message : '分析范围暂时无法刷新。';
	}
}

watch(
	() => route.query.runId,
	(value) => {
		if (typeof value === 'string' && value !== run.value?.runId)
			void openRun(value);
	},
);
watch(
	() => `${context.me.value?.subjectId}:${context.me.value?.authzVersion}`,
	(now, previous) => {
		if (previous && now !== previous && run.value) {
			stream.stop();
			clearSensitive();
		}
	},
);
onMounted(() => {
	if (typeof route.query.question === 'string')
		question.value = route.query.question;
	if (typeof route.query.runId === 'string') void openRun(route.query.runId);
});
onBeforeUnmount(() => {
	reportGeneration++;
});
</script>

<template>
	<div class="cl-analysis-page">
		<header class="cl-page-heading">
			<div>
				<h1>诊断工作台</h1>
				<p>从问题到计划，从证据到结论。</p>
			</div>
			<CommerceScopeBar @refresh="refreshAnalysis" />
		</header>
		<div v-if="sourceDates && !run" class="cl-banner">
			本次诊断沿用来源页面选定的范围：{{ scopeNames(analysisStores) }} ·
			{{ dateRangeLabel(analysisDates) }}。全局筛选不会改写此来源范围。
		</div>
		<div v-if="revoked" class="cl-banner danger" role="alert">
			授权范围已变化，原运行及证据已隐藏。请在当前授权范围内新建诊断。
		</div>
		<div v-if="error" class="cl-banner danger" role="alert">{{ error }}</div>
		<div v-if="notice" class="cl-banner success" role="status">
			{{ notice }}
		</div>
		<div class="cl-analysis-layout">
			<div class="cl-analysis-main">
				<section class="cl-card cl-question-card">
					<div class="cl-analysis-eyebrow">
						<CommerceIcon name="sparkles" /> ASK COMMERCE LENS
					</div>
					<label class="cl-sr-only" for="commerce-question">经营分析问题</label>
					<textarea
						id="commerce-question"
						v-model="question"
						maxlength="2000"
						rows="3"
						placeholder="例如：支付 GMV 为什么下降？哪些店铺和商品值得进一步调查？"
						:disabled="busy || !canAnalyze"
					/>
					<div class="cl-question-footer">
						<div class="cl-question-suggestions">
							<button type="button" @click="question = '支付 GMV 为什么变化？'">
								GMV 为什么变化？</button
							><button type="button" @click="question = '退款金额为什么增加？'">
								退款金额增加了？
							</button>
						</div>
						<button
							class="cl-button primary"
							type="button"
							:disabled="busy || !context.ready.value || !canAnalyze"
							@click="createRun"
						>
							{{ busy ? '正在提交…' : '生成分析计划'
							}}<CommerceIcon name="arrow-right" />
						</button>
					</div>
					<p v-if="!canAnalyze && context.ready.value" class="cl-analysis-note">
						当前角色可以查看报告，发起诊断需要运营分析权限。
					</p>
				</section>

				<section
					v-if="loading"
					class="cl-card cl-analysis-skeleton"
					aria-busy="true"
				>
					<div v-for="n in 6" :key="n" />
					<span>正在读取保存的运行状态…</span>
				</section>
				<section v-else-if="run" class="cl-card cl-plan-card">
					<div class="cl-analysis-section-heading">
						<div>
							<h2>
								分析计划
								<small v-if="run.plan">v{{ run.plan.planVersion }}</small>
							</h2>
							<p>范围在创建时固定，页面筛选不会改写此运行</p>
						</div>
						<span class="cl-badge" :class="statusTone(run.status)">{{
							COMMERCE_STATUS[run.status]
						}}</span>
					</div>
					<div class="cl-analysis-chips">
						<span>{{ scopeNames(run.scope.storeIds) }}</span
						><span>{{ dateRangeLabel(run.dateRange) }}</span
						><span>对比 {{ dateRangeLabel(run.comparisonRange) }}</span
						><span>CNY · Asia/Shanghai</span>
					</div>
					<p class="cl-run-question">{{ run.question }}</p>
					<template v-if="run.plan">
						<ol class="cl-plan-actions">
							<li
								v-for="(action, index) in run.plan.actions"
								:key="action.actionId"
							>
								<span class="cl-plan-number">{{
									String(index + 1).padStart(2, '0')
								}}</span>
								<div>
									<h3>{{ action.purpose }}</h3>
									<p>
										{{ action.toolName }}
										<span>· 最多 {{ action.maxCalls }} 次</span>
									</p>
								</div>
							</li>
						</ol>
						<div class="cl-plan-binding">
							<span
								>数据版本 <strong>{{ run.datasetVersionId }}</strong></span
							><span
								>计划哈希
								<code :title="run.plan.planHash"
									>{{ run.plan.planHash.slice(0, 16) }}…</code
								></span
							><span
								>最多 {{ run.plan.maxToolCalls }} 次工具调用 ·
								{{ run.plan.maxTokens.toLocaleString() }} Token 预算</span
							>
						</div>
						<div
							v-if="run.status === 'WAITING_APPROVAL'"
							class="cl-plan-approval"
						>
							<h3><CommerceIcon name="shield" /> 核对范围后确认执行</h3>
							<p>
								仅查询授权范围内的经营聚合；不执行退款、补货或任何经营写操作。计划有效期至
								{{ new Date(run.plan.expiresAt).toLocaleString('zh-CN') }}。
							</p>
							<label class="cl-field"
								>审批备注（可选）<input
									v-model="approvalComment"
									maxlength="500"
									placeholder="补充需要关注的调查边界"
									:disabled="busy"
							/></label>
							<div class="cl-analysis-actions">
								<button
									class="cl-button primary"
									:disabled="busy || !canAnalyze"
									type="button"
									@click="approve('APPROVE')"
								>
									{{ busy ? '正在提交…' : '确认计划并开始调查'
									}}<CommerceIcon name="arrow-right" /></button
								><button
									class="cl-button"
									:disabled="busy || !canAnalyze"
									type="button"
									@click="approve('REJECT')"
								>
									拒绝计划
								</button>
							</div>
						</div>
					</template>
					<div v-else class="cl-empty">
						服务端正在为此问题生成受控调查计划。
					</div>
					<div v-if="run.clarification" class="cl-banner warning">
						<strong>需要澄清指标或能力范围</strong>
						<p>{{ run.clarification.message }}</p>
						<button
							class="cl-button soft"
							type="button"
							@click="question = run.clarification.suggestedQuestion"
						>
							使用建议问题
						</button>
					</div>
					<div v-if="run.error" class="cl-banner danger">
						<strong>{{ run.error.message }}</strong
						><small>{{ run.error.code }}</small>
					</div>
					<div class="cl-analysis-actions cl-plan-bottom">
						<NuxtLink
							v-if="run.reportId"
							class="cl-button primary"
							:to="{
								path: '/commerce/reports',
								query: { reportId: run.reportId },
							}"
							>查看完整报告<CommerceIcon name="arrow-right" /></NuxtLink
						><NuxtLink
							class="cl-button soft"
							:to="{ path: '/commerce/runs', query: { runId: run.runId } }"
							>运行审计</NuxtLink
						>
						<button
							v-if="
								!isTerminal && run.status !== 'CANCEL_REQUESTED' && canAnalyze
							"
							class="cl-button danger"
							type="button"
							:disabled="busy"
							@click="confirmCancel = true"
						>
							取消运行
						</button>
					</div>
					<div v-if="confirmCancel" class="cl-banner warning">
						<p>取消后将停止后续调查，已登记的执行记录会保留。</p>
						<div class="cl-analysis-actions">
							<button
								class="cl-button danger"
								:disabled="busy"
								type="button"
								@click="cancelRun"
							>
								确认取消</button
							><button
								class="cl-button"
								type="button"
								@click="confirmCancel = false"
							>
								继续调查
							</button>
						</div>
					</div>
				</section>
				<section v-else class="cl-card cl-analysis-capabilities">
					<h2>让每个结论都有依据</h2>
					<p>
						先审阅分析计划，再按证据逐步调查。事实、数学分解与待核实线索会分别呈现。
					</p>
					<div class="cl-analysis-capability-grid">
						<div>
							<CommerceIcon name="book" /><strong>统一经营口径</strong
							><span>支付金额、订单、退款、流量与转化</span>
						</div>
						<div>
							<CommerceIcon name="shield" /><strong>只读与可追溯</strong
							><span>固定授权范围、数据版本与指标清单</span>
						</div>
						<div>
							<CommerceIcon name="file" /><strong>证据驱动结论</strong
							><span>每个数值都关联可核验的聚合证据</span>
						</div>
					</div>
				</section>

				<section v-if="report" class="cl-card cl-summary-card">
					<div class="cl-analysis-section-heading">
						<h2>结论摘要</h2>
						<span
							class="cl-badge"
							:class="report.status === 'PARTIAL' ? 'warning' : 'success'"
							>{{
								report.status === 'PARTIAL' ? '部分证据可用' : '数值引用已验证'
							}}</span
						>
					</div>
					<div class="cl-claims">
						<CommerceClaimCard
							v-for="claim in reportSummary"
							:key="claim.claimId"
							:claim="claim"
							@evidence="evidenceId = $event"
						/>
					</div>
					<div class="cl-summary-boundaries">
						<h3>分析边界</h3>
						<p v-for="limitation in report.limitations" :key="limitation">
							{{ limitation }}
						</p>
					</div>
				</section>
			</div>

			<aside class="cl-card cl-execution-card">
				<div class="cl-analysis-section-heading">
					<div>
						<h2>执行轨迹</h2>
						<p>
							{{
								run
									? `${run.steps?.length || 0} 个已完成步骤`
									: '确认计划后，调查将在这里展开'
							}}
						</p>
					</div>
					<span v-if="run" class="cl-badge" :class="statusTone(run.status)">{{
						COMMERCE_STATUS[run.status]
					}}</span>
				</div>
				<div v-if="!run" class="cl-execution-empty">
					<span><CommerceIcon name="activity" /></span>
					<h3>等待一个值得调查的问题</h3>
					<p>
						工具行动、证据与运行状态直接来自服务端。关闭页面后，已批准的调查仍会继续。
					</p>
				</div>
				<template v-else>
					<div class="cl-execution-mode">
						<span class="cl-badge">{{
							run.plannerMode === 'DETERMINISTIC_LOCAL'
								? '本地确定性规划'
								: '模型规划'
						}}</span
						><small>{{
							stream.connected.value
								? '实时事件已连接'
								: isTerminal
									? '运行已结束'
									: '读取持久状态'
						}}</small>
					</div>
					<ol class="cl-execution-timeline">
						<li v-for="step in run.steps" :key="step.action?.actionId">
							<span class="cl-step-mark"><CommerceIcon name="check" /></span>
							<div>
								<h3>{{ step.action?.purpose || '完成受控调查' }}</h3>
								<p>{{ step.action?.toolName }}</p>
								<button
									v-if="step.result?.evidenceId"
									class="cl-evidence-text"
									type="button"
									@click="evidenceId = step.result.evidenceId"
								>
									查看证据 <span aria-hidden="true">↗</span>
								</button>
							</div>
						</li>
						<li v-if="activeStep && !isTerminal">
							<span class="cl-step-mark running"
								><CommerceIcon name="activity"
							/></span>
							<div>
								<h3>{{ activeStep.purpose || '正在执行受控调查' }}</h3>
								<p>等待服务端提交结果</p>
							</div>
						</li>
					</ol>
					<div v-if="!run.steps.length" class="cl-execution-waiting">
						{{
							run.status === 'WAITING_APPROVAL'
								? '计划确认前不会执行数据调查。'
								: '等待已完成的步骤记录。'
						}}
					</div>
					<dl class="cl-execution-meta">
						<dt>模型调用</dt>
						<dd>
							{{
								run.plannerMode === 'DETERMINISTIC_LOCAL'
									? '未调用'
									: `${run.modelAttempts || 0} 次`
							}}
						</dd>
						<dt>实际 Token</dt>
						<dd>{{ tokenText }}</dd>
						<dt>剩余工具预算</dt>
						<dd>{{ run.remainingBudget?.toolCalls ?? '—' }} 次</dd>
						<dt>数据快照</dt>
						<dd>{{ run.datasetVersionId }}</dd>
						<dt>运行编号</dt>
						<dd>{{ run.runId }}</dd>
					</dl>
					<div v-if="stream.error.value" class="cl-banner warning">
						{{ stream.error.value
						}}<button
							class="cl-button soft"
							type="button"
							@click="stream.start(run.runId)"
						>
							重新连接
						</button>
					</div>
				</template>
			</aside>
		</div>
		<CommerceEvidenceDrawer
			:evidence-id="evidenceId"
			@close="evidenceId = null"
		/>
	</div>
</template>

<style scoped>
.cl-analysis-main > .cl-card + .cl-card {
	margin-top: 0;
}
.cl-analysis-layout {
	display: grid;
	grid-template-columns: minmax(0, 1.65fr) minmax(320px, 1fr);
	gap: 20px;
	align-items: start;
}
.cl-analysis-main {
	display: grid;
	gap: 20px;
	min-width: 0;
}
.cl-question-card {
	padding: 24px;
}
.cl-analysis-eyebrow {
	display: flex;
	align-items: center;
	gap: 8px;
	font-size: 10px;
	letter-spacing: 1.1px;
	color: var(--cl-primary);
	font-weight: 700;
	margin-bottom: 16px;
}
.cl-analysis-eyebrow :deep(svg) {
	width: 17px;
}
.cl-question-card textarea {
	width: 100%;
	min-height: 82px;
	resize: vertical;
	border: 0;
	background: none;
	color: var(--cl-text);
	outline-offset: 5px;
	font: inherit;
	font-size: 16px;
	line-height: 1.8;
}
.cl-question-footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	border-top: 1px solid var(--cl-border);
	padding-top: 16px;
	margin-top: 16px;
}
.cl-question-suggestions {
	display: flex;
	gap: 7px;
	flex-wrap: wrap;
}
.cl-question-suggestions button {
	border: 1px solid var(--cl-border);
	background: white;
	color: var(--cl-secondary);
	padding: 5px 8px;
	font-size: 10px;
	border-radius: 6px;
	cursor: pointer;
}
.cl-plan-card,
.cl-summary-card,
.cl-analysis-capabilities,
.cl-execution-card {
	padding: 24px;
}
.cl-analysis-section-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	margin-bottom: 18px;
}
.cl-analysis-section-heading h2 {
	font-size: 17px;
	margin: 0;
	font-weight: 650;
}
.cl-analysis-section-heading h2 small {
	color: var(--cl-muted);
	font-size: 12px;
	margin-left: 4px;
}
.cl-analysis-section-heading p {
	font-size: 11px;
	color: var(--cl-muted);
	margin: 6px 0 0;
}
.cl-analysis-chips {
	display: flex;
	gap: 6px;
	flex-wrap: wrap;
	margin: 18px 0;
}
.cl-analysis-chips span {
	background: var(--cl-bg);
	padding: 5px 8px;
	border-radius: 5px;
	font-size: 10px;
	color: var(--cl-secondary);
}
.cl-analysis-chips span:first-child {
	color: var(--cl-primary);
	background: var(--cl-primary-soft);
}
.cl-run-question {
	font-size: 12px;
	color: var(--cl-secondary);
	margin: 16px 0 6px;
	line-height: 1.8;
}
.cl-plan-actions {
	list-style: none;
	padding: 0;
	margin: 7px 0 0;
}
.cl-plan-actions li {
	display: flex;
	gap: 12px;
	align-items: flex-start;
	padding: 17px 0;
	border-bottom: 1px solid var(--cl-border);
}
.cl-plan-number {
	display: grid;
	place-items: center;
	min-width: 25px;
	height: 25px;
	background: var(--cl-primary-soft);
	color: var(--cl-primary);
	border-radius: 6px;
	font-size: 10px;
}
.cl-plan-actions h3 {
	margin: 0 0 6px;
	font-size: 13px;
	font-weight: 600;
}
.cl-plan-actions p {
	font-size: 10px;
	color: var(--cl-muted);
	margin: 0;
}
.cl-plan-binding {
	display: flex;
	flex-wrap: wrap;
	gap: 10px 18px;
	margin: 18px 0;
	font-size: 10px;
	color: var(--cl-muted);
}
.cl-plan-binding strong {
	font-weight: 400;
	color: var(--cl-secondary);
	margin-left: 4px;
}
.cl-plan-binding code {
	color: var(--cl-secondary);
}
.cl-plan-approval {
	padding: 18px;
	border: 1px solid #dfe2ff;
	background: #fafaff;
	border-radius: 10px;
	margin: 22px 0 5px;
}
.cl-plan-approval h3 {
	display: flex;
	align-items: center;
	gap: 8px;
	font-size: 13px;
	color: var(--cl-primary);
	margin: 0 0 10px;
}
.cl-plan-approval p {
	font-size: 11px;
	color: var(--cl-secondary);
	line-height: 1.8;
	margin-bottom: 16px;
}
.cl-plan-approval .cl-field {
	margin-bottom: 16px;
}
.cl-analysis-actions {
	display: flex;
	gap: 9px;
	flex-wrap: wrap;
}
.cl-plan-bottom {
	margin-top: 20px;
}
.cl-analysis-note {
	font-size: 11px;
	margin-top: 14px;
	color: var(--cl-warning);
}
.cl-claims {
	display: grid;
	gap: 12px;
}
.cl-summary-boundaries {
	background: #fffdf8;
	border: 1px solid #f1e4cd;
	border-radius: 10px;
	margin-top: 16px;
	padding: 17px 19px;
}
.cl-summary-boundaries h3 {
	color: var(--cl-warning);
	font-size: 12px;
	margin: 0 0 7px;
}
.cl-summary-boundaries p {
	font-size: 11px;
	color: var(--cl-secondary);
	line-height: 1.9;
	margin: 4px 0;
}
.cl-execution-card {
	min-height: 440px;
}
.cl-execution-mode {
	display: flex;
	justify-content: space-between;
	align-items: center;
	padding-bottom: 18px;
	border-bottom: 1px solid var(--cl-border);
	gap: 10px;
}
.cl-execution-mode small {
	color: var(--cl-muted);
	font-size: 10px;
}
.cl-execution-timeline {
	list-style: none;
	margin: 24px 0;
	padding: 0;
}
.cl-execution-timeline li {
	position: relative;
	display: flex;
	gap: 14px;
	padding-bottom: 26px;
}
.cl-execution-timeline li:not(:last-child)::after {
	position: absolute;
	content: '';
	top: 25px;
	bottom: 0;
	left: 12px;
	width: 1px;
	background: var(--cl-border);
}
.cl-step-mark {
	width: 25px;
	height: 25px;
	border-radius: 50%;
	background: #e9f6f1;
	color: var(--cl-success);
	display: grid;
	place-items: center;
	flex-shrink: 0;
}
.cl-step-mark :deep(svg) {
	width: 13px;
}
.cl-step-mark.running {
	background: var(--cl-primary-soft);
	color: var(--cl-primary);
}
.cl-execution-timeline h3 {
	margin: 1px 0 7px;
	font-size: 12px;
	font-weight: 550;
	line-height: 1.6;
}
.cl-execution-timeline p {
	font-size: 10px;
	color: var(--cl-muted);
	margin: 0;
}
.cl-evidence-text {
	background: none;
	border: 0;
	padding: 7px 0 0;
	color: var(--cl-primary);
	font-size: 10px;
	cursor: pointer;
}
.cl-execution-meta {
	border-top: 1px solid var(--cl-border);
	padding-top: 23px;
	display: grid;
	grid-template-columns: 92px minmax(0, 1fr);
	gap: 16px 10px;
	font-size: 11px;
}
.cl-execution-meta dt {
	color: var(--cl-muted);
}
.cl-execution-meta dd {
	margin: 0;
	overflow-wrap: anywhere;
}
.cl-execution-waiting {
	font-size: 12px;
	color: var(--cl-muted);
	padding: 35px 0;
	text-align: center;
}
.cl-execution-empty {
	text-align: center;
	padding: 44px 16px;
}
.cl-execution-empty > span {
	display: inline-grid;
	place-items: center;
	width: 58px;
	height: 58px;
	background: var(--cl-primary-soft);
	color: var(--cl-primary);
	border-radius: 17px;
	margin-bottom: 18px;
}
.cl-execution-empty h3 {
	font-size: 14px;
	margin: 0 0 12px;
}
.cl-execution-empty p {
	font-size: 12px;
	color: var(--cl-muted);
	line-height: 1.9;
}
.cl-analysis-capabilities h2 {
	font-size: 17px;
	margin: 0 0 10px;
}
.cl-analysis-capabilities > p {
	font-size: 12px;
	line-height: 1.9;
	color: var(--cl-secondary);
}
.cl-analysis-capability-grid {
	display: grid;
	gap: 20px;
	margin-top: 27px;
}
.cl-analysis-capability-grid > div {
	display: grid;
	grid-template-columns: 20px 1fr;
	column-gap: 12px;
	row-gap: 6px;
}
.cl-analysis-capability-grid :deep(svg) {
	color: var(--cl-primary);
	grid-row: span 2;
	width: 19px;
}
.cl-analysis-capability-grid strong {
	font-size: 12px;
}
.cl-analysis-capability-grid span {
	font-size: 11px;
	color: var(--cl-muted);
}
.cl-analysis-skeleton {
	padding: 24px;
}
.cl-analysis-skeleton > div {
	height: 35px;
	margin: 17px 0;
	background: #f1f3f9;
	border-radius: 8px;
}
.cl-analysis-skeleton span {
	font-size: 12px;
	color: var(--cl-muted);
}
.cl-sr-only {
	position: absolute;
	width: 1px;
	height: 1px;
	overflow: hidden;
	clip: rect(0, 0, 0, 0);
}
@media (max-width: 1150px) {
	.cl-analysis-layout {
		grid-template-columns: minmax(0, 1.4fr) minmax(280px, 1fr);
	}
	.cl-question-footer {
		align-items: flex-start;
		flex-direction: column;
	}
}
@media (max-width: 960px) {
	.cl-analysis-layout {
		grid-template-columns: 1fr;
	}
	.cl-execution-card {
		min-height: 0;
	}
	.cl-question-footer {
		flex-direction: row;
		align-items: center;
	}
}
@media (max-width: 600px) {
	.cl-question-footer {
		flex-direction: column;
		align-items: stretch;
	}
	.cl-plan-card,
	.cl-summary-card,
	.cl-question-card,
	.cl-execution-card {
		padding: 18px;
	}
	.cl-plan-approval {
		padding: 14px;
	}
}
</style>
