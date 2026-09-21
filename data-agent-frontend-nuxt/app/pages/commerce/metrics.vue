<script setup lang="ts">
import { commerceApi } from '~/services/commerceApi';
import type { Metric } from '~/types/commerce';

definePageMeta({ layout: 'commerce' });
const { me, ready, selectedStoreIds, dateRange, comparison, scopeLabel } =
	useCommerceContext();
const metrics = ref<Metric[]>([]);
const search = ref('');
const selectedId = ref('net_receipts');
const loading = ref(true);
const error = ref('');
const querying = ref(false);
const queryError = ref('');
const evidenceId = ref<string | null>(null);
const identity = computed(() =>
	JSON.stringify([
		me.value?.tenantId,
		me.value?.subjectId,
		me.value?.authzVersion,
	]),
);
let loadSequence = 0,
	querySequence = 0;
const filtered = computed(() =>
	metrics.value.filter((metric) =>
		`${metric.displayName} ${metric.metricId} ${metric.definition}`
			.toLowerCase()
			.includes(search.value.toLowerCase()),
	),
);
const selected = computed(
	() =>
		filtered.value.find((metric) => metric.metricId === selectedId.value) ||
		filtered.value[0],
);
const canQuery = computed(() =>
	me.value?.roles.some((role) =>
		['TENANT_ADMIN', 'OPS_MANAGER', 'STORE_OPERATOR'].includes(role),
	),
);
const dimensions: Record<string, string> = {
	day: '日期',
	store: '店铺',
	sku: 'SKU',
};
const units: Record<string, string> = {
	CNY_CENT: '人民币 · 分',
	COUNT: '计数',
	RATIO: '比值',
};
const formulas: Record<string, string> = {
	paid_gmv: '成功支付的商品实付金额之和',
	paid_orders: '规范成功支付对应的订单数',
	aov: '支付 GMV ÷ 支付订单数',
	refund_amount: '退款成功事件的商品金额之和',
	net_receipts: '支付 GMV − 成功退款额',
	refund_intensity: '成功退款额 ÷ 支付 GMV',
	visitor_sessions: '店铺日会话数之和',
	order_conversion_rate: '支付订单数 ÷ 访客会话数',
};
async function load() {
	const sequence = ++loadSequence;
	loading.value = true;
	error.value = '';
	try {
		const catalog = await commerceApi.listMetrics();
		if (sequence === loadSequence) metrics.value = catalog;
	} catch (cause) {
		if (sequence === loadSequence) {
			metrics.value = [];
			error.value =
				cause instanceof Error ? cause.message : '指标目录暂时不可用';
		}
	} finally {
		if (sequence === loadSequence) loading.value = false;
	}
}
async function inspectEvidence() {
	if (!selected.value || !canQuery.value || querying.value) return;
	const sequence = ++querySequence;
	querying.value = true;
	queryError.value = '';
	try {
		const result = await commerceApi.query({
			requestedStoreIds: [...selectedStoreIds.value],
			spec: {
				metricIds: [selected.value.metricId],
				dimensions: ['store'],
				dateRange: { ...dateRange.value },
				comparison: comparison.value,
				filters: [],
				limit: 100,
			},
		});
		if (sequence === querySequence) evidenceId.value = result.evidenceId;
	} catch (cause) {
		if (sequence === querySequence)
			queryError.value =
				cause instanceof Error ? cause.message : '查询未完成，请重试';
	} finally {
		if (sequence === querySequence) querying.value = false;
	}
}
watch(
	[ready, identity],
	([value]) => {
		++loadSequence;
		metrics.value = [];
		if (value) void load();
	},
	{ immediate: true },
);
watch(
	() =>
		JSON.stringify([
			ready.value,
			identity.value,
			selected.value?.metricId,
			selectedStoreIds.value,
			dateRange.value,
			comparison.value,
		]),
	() => {
		++querySequence;
		querying.value = false;
		queryError.value = '';
		evidenceId.value = null;
	},
);
onBeforeUnmount(() => {
	++loadSequence;
	++querySequence;
});
</script>

<template>
	<div class="cl-metrics-page">
		<div class="cl-page-heading">
			<div>
				<h1>指标字典</h1>
				<p>统一指标口径，让经营数字可以解释、比较与复算。</p>
			</div>
			<label class="cl-metric-search"
				><CommerceIcon name="search" /><input
					v-model="search"
					type="search"
					placeholder="搜索指标名称或定义"
					aria-label="搜索指标"
			/></label>
		</div>
		<div class="cl-banner cl-metric-notice">
			<CommerceIcon name="shield" /><span
				>标准查询仅使用已发布指标。口径、版本与证据一起保留，历史报告不会随新版本改变。</span
			>
		</div>
		<div v-if="error" class="cl-banner danger" role="alert">
			{{ error }}<button class="cl-button" @click="load">重试</button>
		</div>
		<div v-else-if="loading" class="cl-metric-layout" aria-busy="true">
			<div class="cl-card cl-metric-skeleton" />
			<div class="cl-card cl-metric-skeleton" />
		</div>
		<div v-else-if="!metrics.length" class="cl-card cl-empty">
			<CommerceIcon name="book" />
			<h2>暂无已发布指标</h2>
			<p>指标发布后，会在此显示对应口径与支持范围。</p>
		</div>
		<div v-else class="cl-metric-layout">
			<section class="cl-card cl-metric-list">
				<header>
					<h2>
						已发布指标 <span>{{ metrics.length }} 项</span>
					</h2>
					<span class="cl-badge success">统一口径</span>
				</header>
				<div class="cl-metric-scroll">
					<table class="cl-table">
						<thead>
							<tr>
								<th>指标名称</th>
								<th>技术标识</th>
								<th>可用维度</th>
								<th>单位</th>
							</tr>
						</thead>
						<tbody>
							<tr
								v-for="metric in filtered"
								:key="metric.metricId"
								:class="{ selected: selected?.metricId === metric.metricId }"
							>
								<td>
									<button
										class="cl-metric-select"
										:aria-pressed="selected?.metricId === metric.metricId"
										@click="selectedId = metric.metricId"
									>
										{{ metric.displayName }}
									</button>
								</td>
								<td class="cl-metric-id">{{ metric.metricId }}</td>
								<td>
									{{
										metric.supportedDimensions
											.map((d) => dimensions[d] || d)
											.join(' / ')
									}}
								</td>
								<td>{{ units[metric.unit] || metric.unit }}</td>
							</tr>
						</tbody>
					</table>
				</div>
				<div v-if="!filtered.length" class="cl-empty">
					<p>没有找到“{{ search }}”对应的指标。</p>
					<button class="cl-button soft" @click="search = ''">清空搜索</button>
				</div>
			</section>
			<aside
				v-if="selected"
				class="cl-card cl-metric-detail"
				aria-live="polite"
			>
				<header>
					<h2>{{ selected.displayName }}</h2>
					<span class="cl-badge success">已发布</span>
				</header>
				<p class="cl-metric-id">
					{{ selected.metricId }} · v{{ selected.version }}
				</p>
				<div class="cl-formula">
					{{ formulas[selected.metricId] || selected.definition }}
				</div>
				<dl>
					<dt>业务定义</dt>
					<dd>{{ selected.definition }}</dd>
					<dt>依赖事实</dt>
					<dd>{{ selected.requiredFacts.join('、') }}</dd>
					<dt>零分母处理</dt>
					<dd>
						{{
							selected.zeroDenominatorPolicy === 'NULL'
								? '分母为 0 时返回空值，并说明原因'
								: '完整数据无事件时为 0；来源缺失时为空值'
						}}
					</dd>
					<dt>可用维度</dt>
					<dd>
						{{
							selected.supportedDimensions
								.map((d) => dimensions[d] || d)
								.join(' / ')
						}}
					</dd>
					<dt>指标版本</dt>
					<dd>v{{ selected.version }}</dd>
				</dl>
				<div class="cl-metric-boundary">
					<strong>分析边界</strong>
					<p>
						{{
							selected.note || '请结合指标定义与数据覆盖范围解读结果。'
						}}
						比率先汇总分子和分母，再计算结果。SKU 维度仅支持支付 GMV。
					</p>
				</div>
				<div v-if="queryError" class="cl-banner danger" role="alert">
					{{ queryError }}
				</div>
				<button
					class="cl-button soft cl-evidence-action"
					:disabled="
						querying ||
						!canQuery ||
						!selectedStoreIds.length ||
						!dateRange.start
					"
					:title="
						!canQuery
							? '只读观察者可浏览指标；查询需运营权限'
							: !dateRange.start
								? '发布数据快照后可查询当前范围'
								: '查询当前范围并打开真实证据'
					"
					@click="inspectEvidence"
				>
					<CommerceIcon name="arrow-right" />{{
						querying ? '正在查询当前范围…' : '查询当前范围并查看证据'
					}}
				</button>
				<p class="cl-metric-footnote">
					<template v-if="dateRange.start"
						>{{ scopeLabel }} · {{ dateRange.start }} 至
						{{ dateRange.endExclusive }}（不含结束日）</template
					><template v-else
						>发布数据快照后，可在当前授权范围内查询并复核证据。</template
					>
				</p>
			</aside>
		</div>
		<CommerceEvidenceDrawer
			:evidence-id="evidenceId"
			@close="evidenceId = null"
		/>
	</div>
</template>

<style scoped>
.cl-metric-search {
	display: flex;
	align-items: center;
	gap: 9px;
	background: var(--surface);
	border: 1px solid var(--border);
	border-radius: 10px;
	padding: 0 12px;
	width: 250px;
	color: var(--text-muted);
}
.cl-metric-search input {
	height: 38px;
	min-width: 0;
	width: 100%;
	outline: 0;
	background: transparent;
	font: inherit;
}
.cl-metric-layout {
	display: grid;
	grid-template-columns: minmax(0, 1.65fr) minmax(310px, 1fr);
	gap: 20px;
}
.cl-metric-notice {
	margin-bottom: 20px;
	display: flex;
	gap: 10px;
	align-items: center;
}
.cl-metric-list {
	padding: 0;
	overflow: hidden;
}
.cl-metric-list header,
.cl-metric-detail header {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
}
.cl-metric-list header {
	padding: 22px 22px 16px;
}
.cl-metric-list h2,
.cl-metric-detail h2 {
	font-size: 17px;
	margin: 0;
}
.cl-metric-list h2 span {
	font-size: 12px;
	color: var(--text-muted);
	font-weight: 400;
}
.cl-metric-scroll {
	overflow: auto;
}
.cl-metric-list .cl-table {
	white-space: nowrap;
}
.cl-metric-list tr.selected {
	background: var(--primary-soft);
}
.cl-metric-select {
	color: var(--text);
	font-weight: 600;
	font-size: 13px;
	cursor: pointer;
	padding: 8px 0;
	text-align: left;
}
.cl-metric-select:hover {
	color: var(--primary);
}
.cl-metric-id {
	font-size: 12px;
	color: var(--text-secondary);
	overflow-wrap: anywhere;
}
.cl-metric-detail {
	padding: 22px;
	align-self: start;
}
.cl-formula {
	padding: 17px 15px;
	border: 1px solid var(--border);
	border-radius: 10px;
	background: var(--surface-soft);
	font-size: 13px;
	margin: 20px 0;
	color: var(--text);
}
.cl-metric-detail dl {
	display: grid;
	grid-template-columns: 90px 1fr;
	gap: 15px 12px;
	font-size: 13px;
	line-height: 1.8;
}
.cl-metric-detail dt {
	color: var(--text-muted);
}
.cl-metric-detail dd {
	margin: 0;
	overflow-wrap: anywhere;
}
.cl-metric-boundary {
	margin: 22px 0;
	padding: 15px;
	border: 1px solid #f1e1c8;
	background: #fffcf7;
	border-radius: 10px;
	color: var(--warning);
	font-size: 12px;
	line-height: 1.8;
}
.cl-metric-boundary p {
	margin: 4px 0 0;
}
.cl-evidence-action {
	width: 100%;
	justify-content: center;
}
.cl-metric-footnote {
	font-size: 11px;
	line-height: 1.7;
	color: var(--text-muted);
	margin: 10px 0 0;
}
.cl-metric-skeleton {
	height: 450px;
	background: linear-gradient(
		100deg,
		var(--surface) 20%,
		var(--surface-soft) 50%,
		var(--surface) 80%
	);
	background-size: 200% 100%;
	animation: cl-shimmer 1.5s infinite;
}
@keyframes cl-shimmer {
	to {
		background-position: -200% 0;
	}
}
@media (max-width: 1100px) {
	.cl-metric-layout {
		grid-template-columns: minmax(0, 1fr);
	}
}
@media (max-width: 640px) {
	.cl-metric-search {
		width: 100%;
	}
	.cl-metric-detail dl {
		grid-template-columns: 80px 1fr;
	}
	.cl-metric-list header {
		padding: 18px;
	}
}
</style>
