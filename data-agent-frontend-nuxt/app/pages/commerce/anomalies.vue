<script setup lang="ts">
import { commerceApi } from '~/services/commerceApi';
import type { Anomaly, AnomalyRule, Metric } from '~/types/commerce';
import {
	formatCell,
	formatDecimal,
	dateRangeLabel,
} from '~/utils/commerceFormat';

definePageMeta({ layout: 'commerce' });
const { me, stores, selectedStoreIds, ready, scopeLabel } =
	useCommerceContext();
const anomalies = ref<Anomaly[]>([]),
	rules = ref<AnomalyRule[]>([]),
	metrics = ref<Metric[]>([]);
type Monitor = {
	monitorId: string;
	ruleId: string;
	ruleVersion: number;
	status: string;
	sampleCount: number;
	createdAt: string;
	scope: { storeIds: string[] };
	window: { start: string; endExclusive: string };
};
const monitors = ref<Monitor[]>([]);
const loading = ref(true),
	error = ref(''),
	feedback = ref(''),
	actionId = ref('');
const statusFilter = ref('ALL'),
	ruleOpen = ref(false),
	editingRule = ref<AnomalyRule | null>(null);
const nextCursor = ref<string | null>(null),
	cursor = ref<string | undefined>(),
	previous = ref<(string | undefined)[]>([]);
const identity = computed(() =>
	JSON.stringify([
		me.value?.tenantId,
		me.value?.subjectId,
		me.value?.authzVersion,
	]),
);
let loadSequence = 0,
	actionSequence = 0;
const isAdmin = computed(
	() => me.value?.roles.includes('TENANT_ADMIN') ?? false,
);
const canAnalyze = computed(
	() =>
		me.value?.roles.some((role) =>
			['TENANT_ADMIN', 'OPS_MANAGER', 'STORE_OPERATOR'].includes(role),
		) ?? false,
);
const inScope = (ids: string[]) =>
	ids.length > 0 && ids.every((id) => selectedStoreIds.value.includes(id));
const scoped = computed(() =>
	anomalies.value.filter((item) => inScope(item.scope.storeIds)),
);
const visible = computed(() =>
	scoped.value.filter(
		(item) =>
			statusFilter.value === 'ALL' ||
			(statusFilter.value === 'ACTIVE'
				? ['OPEN', 'ACKNOWLEDGED'].includes(item.status)
				: item.status === statusFilter.value),
	),
);
const scopedRules = computed(() =>
	rules.value.filter((rule) => inScope(rule.definition.storeIds)),
);
const blocked = computed(() => {
	const latest = new Map<string, Monitor>();
	for (const item of monitors.value) {
		if (
			!inScope(item.scope.storeIds) ||
			!rules.value.some(
				(rule) =>
					rule.ruleId === item.ruleId &&
					rule.version === item.ruleVersion &&
					rule.definition.enabled,
			)
		)
			continue;
		const previous = latest.get(item.ruleId);
		if (
			!previous ||
			item.window.start > previous.window.start ||
			(item.window.start === previous.window.start &&
				item.createdAt > previous.createdAt)
		)
			latest.set(item.ruleId, item);
	}
	return [...latest.values()]
		.filter((item) => !['NORMAL', 'TRIGGERED'].includes(item.status))
		.slice(0, 4);
});
const statusNames: Record<string, string> = {
	OPEN: '待处理',
	ACKNOWLEDGED: '已知晓',
	RESOLVED: '已恢复',
	SUPPRESSED: '已合并 / 替代',
};
const gateNames: Record<string, string> = {
	INCOMPLETE_DATA: '数据来源不完整',
	INSUFFICIENT_HISTORY: '完整参考日不足',
	SMALL_SAMPLE: '样本量不足',
	METRIC_VERSION_CHANGED: '指标版本已变化',
};
const metricName = (id: string) =>
	metrics.value.find((metric) => metric.metricId === id)?.displayName || id;
const storeLabel = (ids: string[]) =>
	ids
		.map((id) => stores.value.find((store) => store.storeId === id)?.name || id)
		.join('、');
const ruleName = (id: string) =>
	rules.value.find((rule) => rule.ruleId === id)?.definition.name || '监控规则';
async function load() {
	const sequence = ++loadSequence;
	loading.value = true;
	error.value = '';
	try {
		const [events, definitions, catalog, observations] = await Promise.all([
			commerceApi.listAnomalies({ limit: 20, cursor: cursor.value }),
			commerceApi.listRules({ limit: 100 }),
			commerceApi.listMetrics(),
			commerceApi.listMonitorStatus(),
		]);
		if (sequence !== loadSequence) return;
		anomalies.value = events.items;
		nextCursor.value = events.nextCursor;
		rules.value = definitions.items;
		metrics.value = catalog;
		monitors.value = observations.items;
	} catch (cause) {
		if (sequence === loadSequence) {
			anomalies.value = [];
			rules.value = [];
			monitors.value = [];
			nextCursor.value = null;
			error.value =
				cause instanceof Error ? cause.message : '异常中心暂时不可用';
		}
	} finally {
		if (sequence === loadSequence) loading.value = false;
	}
}
async function acknowledge(item: Anomaly) {
	if (actionId.value) return;
	const sequence = ++actionSequence;
	actionId.value = item.anomalyId;
	error.value = '';
	try {
		const updated = await commerceApi.acknowledgeAnomaly(item.anomalyId);
		if (sequence !== actionSequence) return;
		anomalies.value = anomalies.value.map((event) =>
			event.anomalyId === item.anomalyId ? updated : event,
		);
		feedback.value = '已记录知晓状态。是否恢复仍由后续完整观察日判断。';
	} catch (cause) {
		if (sequence === actionSequence)
			error.value = cause instanceof Error ? cause.message : '确认失败，请重试';
	} finally {
		if (sequence === actionSequence) actionId.value = '';
	}
}
function diagnose(item: Anomaly) {
	return navigateTo({
		path: '/commerce/analysis',
		query: {
			stores: item.scope.storeIds.join(','),
			start: item.window.start,
			endExclusive: item.window.endExclusive,
			comparison: 'PREVIOUS_WEEK_SAME_DAYS',
			question: `${dateRangeLabel(item.window)} ${storeLabel(item.scope.storeIds)}的${metricName(item.metricId)}发生变化，请分析变化贡献和相关线索。`,
			anomalyId: item.anomalyId,
		},
	});
}
function edit(rule: AnomalyRule | null) {
	editingRule.value = rule;
	ruleOpen.value = true;
}
function saved() {
	feedback.value = '监控规则已保存，新版本将用于后续完整数据观察。';
	void load();
}
function nextPage() {
	if (!nextCursor.value) return;
	previous.value.push(cursor.value);
	cursor.value = nextCursor.value;
	void load();
}
function previousPage() {
	cursor.value = previous.value.pop();
	void load();
}
watch(
	[ready, identity],
	([value]) => {
		++loadSequence;
		++actionSequence;
		anomalies.value = [];
		rules.value = [];
		metrics.value = [];
		monitors.value = [];
		cursor.value = undefined;
		nextCursor.value = null;
		previous.value = [];
		actionId.value = '';
		feedback.value = '';
		ruleOpen.value = false;
		editingRule.value = null;
		if (value) void load();
	},
	{ immediate: true },
);
onBeforeUnmount(() => {
	++loadSequence;
	++actionSequence;
});
</script>

<template>
	<div class="cl-anomalies-page">
		<div class="cl-page-heading">
			<div>
				<h1>异常中心</h1>
				<p>先发现变化，再用证据定位值得调查的线索。</p>
			</div>
			<button
				class="cl-button primary"
				:disabled="!isAdmin || !metrics.length"
				:title="isAdmin ? '配置新监控规则' : '新建规则需要租户管理员权限'"
				@click="edit(null)"
			>
				<CommerceIcon name="plus" />新建监控规则
			</button>
		</div>
		<div
			v-if="feedback"
			class="cl-banner success cl-anomaly-feedback"
			role="status"
		>
			<CommerceIcon name="check" />{{ feedback
			}}<button aria-label="关闭提示" @click="feedback = ''">
				<CommerceIcon name="close" />
			</button>
		</div>
		<div v-if="error" class="cl-banner danger cl-anomaly-feedback" role="alert">
			{{ error }}<button class="cl-button" @click="load">重试</button>
		</div>
		<div class="cl-anomaly-summary">
			<div class="cl-card">
				<span>待处理 · 当前页</span
				><strong>{{
					loading ? '—' : scoped.filter((a) => a.status === 'OPEN').length
				}}</strong
				><small>需要核查的变化</small>
			</div>
			<div class="cl-card">
				<span>已知晓 · 当前页</span
				><strong>{{
					loading
						? '—'
						: scoped.filter((a) => a.status === 'ACKNOWLEDGED').length
				}}</strong
				><small>已记录处理意向</small>
			</div>
			<div class="cl-card">
				<span>已恢复 · 当前页</span
				><strong>{{
					loading ? '—' : scoped.filter((a) => a.status === 'RESOLVED').length
				}}</strong
				><small>连续两个完整日恢复正常</small>
			</div>
			<div class="cl-card">
				<span>当前范围启用规则</span
				><strong>{{
					loading ? '—' : scopedRules.filter((r) => r.definition.enabled).length
				}}</strong
				><small>{{ scopeLabel }}</small>
			</div>
		</div>
		<section v-if="!loading && blocked.length" class="cl-card cl-monitor-gates">
			<div class="cl-monitor-title">
				<CommerceIcon name="shield" />
				<h2>数据质量优先</h2>
				<span class="cl-badge warning">以下观察未生成经营告警</span>
			</div>
			<div v-for="item in blocked" :key="item.monitorId" class="cl-monitor-row">
				<span>{{ ruleName(item.ruleId) }} · {{ item.window.start }}</span
				><span
					>{{ gateNames[item.status] || item.status
					}}<small v-if="item.status === 'INSUFFICIENT_HISTORY'">
						· 有效参考 {{ item.sampleCount }} / 至少 4 日</small
					></span
				>
			</div>
		</section>
		<div class="cl-anomaly-layout">
			<section class="cl-anomaly-events">
				<div class="cl-anomaly-section-heading">
					<h2>
						经营异常 <span>{{ scopeLabel }}</span>
					</h2>
					<select v-model="statusFilter" aria-label="筛选异常状态">
						<option value="ALL">全部状态</option>
						<option value="ACTIVE">处理中</option>
						<option value="RESOLVED">已恢复</option>
						<option value="SUPPRESSED">已合并 / 替代</option>
					</select>
				</div>
				<div v-if="loading" class="cl-card cl-anomaly-loading" aria-busy="true">
					<div />
					<div />
					<div />
				</div>
				<div v-else-if="!visible.length" class="cl-card cl-empty">
					<CommerceIcon name="check" />
					<h3>当前页没有匹配的异常</h3>
					<p>
						异常保留触发时的完整店铺范围。可切换状态、店铺范围或继续查看下一页。
					</p>
					<button class="cl-button soft" @click="load">刷新观察结果</button>
				</div>
				<article
					v-for="item in visible"
					v-else
					:key="item.anomalyId"
					class="cl-card cl-anomaly-event"
				>
					<header>
						<div
							class="cl-event-symbol"
							:class="item.status === 'RESOLVED' ? 'resolved' : ''"
						>
							<CommerceIcon
								:name="item.status === 'RESOLVED' ? 'check' : 'alert'"
							/>
						</div>
						<div class="cl-event-title">
							<h3>
								{{ metricName(item.metricId)
								}}{{ item.status === 'RESOLVED' ? '已恢复' : '偏离参考范围' }}
							</h3>
							<p>
								{{ storeLabel(item.scope.storeIds) }} ·
								{{ dateRangeLabel(item.window) }}
							</p>
						</div>
						<span
							class="cl-badge"
							:class="
								item.status === 'RESOLVED'
									? 'success'
									: item.status === 'OPEN'
										? 'danger'
										: 'warning'
							"
							>{{ statusNames[item.status] || item.status }}</span
						>
					</header>
					<div class="cl-event-values">
						<div>
							<span>当前值</span><strong>{{ formatCell(item.current) }}</strong>
						</div>
						<div>
							<span>同星期历史中位数</span
							><strong>{{ formatCell(item.baseline) }}</strong>
						</div>
						<div>
							<span>有效参考</span
							><strong
								>{{ item.referenceDates.length }}<small> 天</small></strong
							>
						</div>
					</div>
					<p class="cl-event-reason">{{ item.reason }}</p>
					<details class="cl-event-meta">
						<summary>查看参考日期与数据版本</summary>
						<p>参考日：{{ item.referenceDates.join('、') }}</p>
						<p>
							数据版本：{{ item.datasetVersionId }} · 规则 v{{
								item.ruleVersion
							}}
						</p>
						<p v-if="item.scoreUnavailable === 'MAD_ZERO'">
							历史值稳定，使用绝对与相对阈值判断。
						</p>
					</details>
					<footer>
						<span>{{ ruleName(item.ruleId) }}</span>
						<div>
							<button
								v-if="item.status === 'OPEN'"
								class="cl-button"
								:disabled="!canAnalyze || !!actionId"
								@click="acknowledge(item)"
							>
								{{
									actionId === item.anomalyId ? '正在记录…' : '标记已知晓'
								}}</button
							><button
								class="cl-button soft"
								:disabled="!canAnalyze"
								:title="
									canAnalyze
										? '使用此异常的原日期与店铺范围'
										: '诊断需要运营权限'
								"
								@click="diagnose(item)"
							>
								<CommerceIcon name="sparkles" />发起诊断
							</button>
						</div>
					</footer>
				</article>
				<nav
					v-if="previous.length || nextCursor"
					class="cl-anomaly-pagination"
					aria-label="异常分页"
				>
					<button
						class="cl-button"
						:disabled="!previous.length || loading"
						@click="previousPage"
					>
						上一页</button
					><span>每页最多 20 条授权记录</span
					><button
						class="cl-button"
						:disabled="!nextCursor || loading"
						@click="nextPage"
					>
						下一页
					</button>
				</nav>
			</section>
			<aside class="cl-card cl-rule-list">
				<header>
					<h2>监控规则</h2>
					<span class="cl-badge">日级观察</span>
				</header>
				<p class="cl-rule-intro">
					在完整快照上运行。规则告警由确定性计算产生。
				</p>
				<div v-if="!loading && !scopedRules.length" class="cl-empty">
					<p>当前范围还没有监控规则。</p>
				</div>
				<article v-for="rule in scopedRules" :key="rule.ruleId">
					<div>
						<h3>{{ rule.definition.name }}</h3>
						<span
							class="cl-badge"
							:class="rule.definition.enabled ? 'success' : ''"
							>{{ rule.definition.enabled ? '启用' : '停用' }}</span
						>
					</div>
					<p>
						{{ metricName(rule.definition.metricId) }} ·
						{{ rule.definition.direction === 'DOWN' ? '下降' : '上升' }}
					</p>
					<p>
						绝对变化 ≥
						{{
							formatDecimal(
								rule.definition.absoluteThreshold,
								rule.definition.unit,
							)
						}}<template v-if="rule.definition.relativeThreshold !== null"
							><br />且相对变化 ≥
							{{
								formatDecimal(rule.definition.relativeThreshold, 'RATIO')
							}}</template
						>
					</p>
					<footer>
						<small
							>v{{ rule.version }} · 冷却
							{{ rule.definition.cooldownHours }}h</small
						><button
							class="cl-button soft"
							:disabled="!isAdmin"
							:title="isAdmin ? '编辑规则并发布新版本' : '编辑需要管理员权限'"
							@click="edit(rule)"
						>
							编辑
						</button>
					</footer>
				</article>
				<div class="cl-rule-note">
					<CommerceIcon name="shield" />
					<p>
						“已知晓”表示有人开始关注；连续两个完整正常观察日后，系统才标记“已恢复”。
					</p>
				</div>
			</aside>
		</div>
		<CommerceRuleDialog
			:open="ruleOpen"
			:rule="editingRule"
			:metrics="metrics"
			@close="ruleOpen = false"
			@saved="saved"
			@conflict="load"
		/>
	</div>
</template>

<style scoped>
.cl-anomaly-feedback {
	display: flex;
	align-items: center;
	gap: 10px;
	margin-bottom: 18px;
}
.cl-anomaly-feedback > button:last-child {
	margin-left: auto;
}
.cl-anomaly-summary {
	display: grid;
	grid-template-columns: repeat(4, minmax(0, 1fr));
	gap: 16px;
	margin-bottom: 20px;
}
.cl-anomaly-summary > div {
	padding: 20px;
}
.cl-anomaly-summary span,
.cl-anomaly-summary small {
	display: block;
	color: var(--text-secondary);
	font-size: 12px;
}
.cl-anomaly-summary strong {
	display: block;
	font-size: 30px;
	font-weight: 650;
	margin: 8px 0 5px;
	font-variant-numeric: tabular-nums;
}
.cl-anomaly-layout {
	display: grid;
	grid-template-columns: minmax(0, 1.7fr) minmax(290px, 1fr);
	gap: 20px;
}
.cl-anomaly-section-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	margin-bottom: 14px;
}
.cl-anomaly-section-heading h2,
.cl-rule-list h2,
.cl-monitor-title h2 {
	font-size: 17px;
	margin: 0;
}
.cl-anomaly-section-heading h2 span {
	font-size: 12px;
	font-weight: 400;
	color: var(--text-muted);
	margin-left: 8px;
}
.cl-anomaly-section-heading select {
	border: 1px solid var(--border);
	border-radius: 8px;
	padding: 8px 12px;
	background: white;
	font: inherit;
	font-size: 12px;
}
.cl-anomaly-event {
	padding: 22px;
	margin-bottom: 16px;
}
.cl-anomaly-event header {
	display: flex;
	align-items: center;
	gap: 12px;
}
.cl-event-symbol {
	width: 40px;
	height: 40px;
	background: #fdf0ef;
	color: var(--danger);
	border-radius: 11px;
	display: grid;
	place-items: center;
	flex-shrink: 0;
}
.cl-event-symbol.resolved {
	background: #eaf7f3;
	color: var(--success);
}
.cl-event-title {
	flex: 1;
	min-width: 0;
}
.cl-event-title h3 {
	margin: 0;
	font-size: 16px;
}
.cl-event-title p {
	font-size: 12px;
	color: var(--text-secondary);
	margin: 5px 0 0;
}
.cl-event-values {
	display: grid;
	grid-template-columns: 1fr 1.25fr 0.7fr;
	gap: 12px;
	background: var(--surface-soft);
	border-radius: 10px;
	padding: 17px;
	margin: 20px 0 16px;
}
.cl-event-values span {
	display: block;
	color: var(--text-muted);
	font-size: 11px;
	margin-bottom: 6px;
}
.cl-event-values strong {
	font-size: 21px;
	font-weight: 650;
	font-variant-numeric: tabular-nums;
	overflow-wrap: anywhere;
}
.cl-event-values small {
	font-size: 12px;
	font-weight: 400;
}
.cl-event-reason {
	color: var(--text-secondary);
	font-size: 13px;
	line-height: 1.8;
	margin: 0 0 10px;
}
.cl-event-meta {
	font-size: 12px;
	color: var(--text-secondary);
	line-height: 1.8;
}
.cl-event-meta summary {
	cursor: pointer;
	color: var(--primary);
}
.cl-event-meta p {
	overflow-wrap: anywhere;
	margin: 8px 0;
}
.cl-anomaly-event > footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 10px;
	border-top: 1px solid var(--border);
	padding-top: 15px;
	margin-top: 16px;
}
.cl-anomaly-event > footer > span {
	font-size: 12px;
	color: var(--text-muted);
}
.cl-anomaly-event > footer > div {
	display: flex;
	gap: 8px;
}
.cl-rule-list {
	padding: 22px;
	align-self: start;
}
.cl-rule-list > header {
	display: flex;
	justify-content: space-between;
	align-items: center;
}
.cl-rule-intro {
	font-size: 12px;
	color: var(--text-muted);
	line-height: 1.8;
	margin: 12px 0 18px;
}
.cl-rule-list article {
	padding: 18px 0;
	border-top: 1px solid var(--border);
}
.cl-rule-list article > div {
	display: flex;
	justify-content: space-between;
	gap: 8px;
	align-items: center;
}
.cl-rule-list h3 {
	font-size: 14px;
	margin: 0;
}
.cl-rule-list article p {
	font-size: 12px;
	line-height: 1.8;
	color: var(--text-secondary);
	margin: 8px 0;
}
.cl-rule-list article footer {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 8px;
}
.cl-rule-list small {
	font-size: 11px;
	color: var(--text-muted);
}
.cl-rule-list article button {
	height: 30px;
	min-height: 30px;
	font-size: 12px;
}
.cl-rule-note {
	display: flex;
	gap: 10px;
	padding: 14px;
	background: var(--primary-soft);
	border-radius: 10px;
	color: var(--primary);
}
.cl-rule-note p {
	margin: 0;
	font-size: 12px;
	line-height: 1.8;
}
.cl-monitor-gates {
	padding: 20px;
	margin-bottom: 20px;
}
.cl-monitor-title {
	display: flex;
	align-items: center;
	gap: 10px;
	margin-bottom: 10px;
}
.cl-monitor-row {
	display: flex;
	justify-content: space-between;
	gap: 10px;
	font-size: 12px;
	padding: 8px 0;
	color: var(--text-secondary);
}
.cl-monitor-row > span:last-child {
	color: var(--warning);
}
.cl-anomaly-pagination {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	padding: 10px 0;
}
.cl-anomaly-pagination span {
	font-size: 12px;
	color: var(--text-muted);
}
.cl-anomaly-loading {
	padding: 24px;
}
.cl-anomaly-loading > div {
	height: 24px;
	background: var(--surface-soft);
	margin: 15px 0;
	border-radius: 6px;
}
.cl-anomaly-loading > div:nth-child(2) {
	height: 90px;
}
@media (max-width: 1100px) {
	.cl-anomaly-layout {
		grid-template-columns: 1fr;
	}
	.cl-rule-list {
		display: block;
	}
}
@media (max-width: 640px) {
	.cl-anomaly-summary {
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 10px;
	}
	.cl-anomaly-event {
		padding: 16px;
	}
	.cl-event-values {
		padding: 12px;
		gap: 8px;
	}
	.cl-event-values strong {
		font-size: 16px;
	}
	.cl-anomaly-event header {
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.cl-anomaly-event > footer {
		flex-wrap: wrap;
	}
	.cl-monitor-row {
		flex-direction: column;
	}
	.cl-anomaly-section-heading h2 span {
		display: block;
		margin: 5px 0 0;
	}
	.cl-anomaly-pagination span {
		display: none;
	}
}
</style>
