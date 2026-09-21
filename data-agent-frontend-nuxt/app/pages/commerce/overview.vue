<script setup lang="ts">
import { commerceApi } from '~/services/commerceApi';
import {
	formatCell,
	formatDecimal,
	getCell,
	dateRangeLabel,
} from '~/utils/commerceFormat';
import type { Overview, Anomaly, ResultRow } from '~/types/commerce';
definePageMeta({ layout: 'commerce' });
const context = useCommerceContext(),
	data = ref<Overview | null>(null),
	anomalies = ref<Anomaly[]>([]),
	loading = ref(true),
	error = ref(''),
	alertError = ref('');
const evidenceId = ref<string | null>(null),
	metricId = ref('paid_gmv');
const metrics = [
	{
		id: 'paid_gmv',
		label: '支付 GMV',
		icon: 'activity',
		hint: '按支付成功时间统计',
	},
	{
		id: 'net_receipts',
		label: '净收款额',
		icon: 'file',
		hint: '支付 GMV − 成功退款额',
	},
	{
		id: 'paid_orders',
		label: '支付订单数',
		icon: 'store',
		hint: '支付成功的去重订单',
	},
	{
		id: 'refund_amount',
		label: '退款金额',
		icon: 'refresh',
		hint: '按退款成功时间统计',
	},
];
const canAnalyze = computed(() =>
	context.me.value?.roles.some((role) =>
		['TENANT_ADMIN', 'OPS_MANAGER', 'STORE_OPERATOR'].includes(role),
	),
);
const currentLabel = computed(
	() => metrics.find((item) => item.id === metricId.value)?.label || '支付 GMV',
);
const summary = computed(() => data.value?.summary);
const storeName = (id?: string) =>
	context.stores.value.find((store) => store.storeId === id)?.name ||
	id ||
	'店铺';
const selectedAnomalies = computed(() =>
	anomalies.value
		.filter(
			(item) =>
				['OPEN', 'ACKNOWLEDGED'].includes(item.status) &&
				item.scope.storeIds.every((id) =>
					context.selectedStoreIds.value.includes(id),
				),
		)
		.slice(0, 3),
);
function direction(row: ResultRow | undefined, id: string) {
	const value = getCell(row, `${id}_change_ratio`)?.value;
	return value == null
		? ''
		: /^0(?:\.0*)?$/.test(value)
			? ''
			: value.startsWith('-')
				? '↓'
				: '↑';
}
function tone(row: ResultRow | undefined, id: string) {
	const value = getCell(row, `${id}_change_ratio`)?.value;
	if (value == null || /^0(?:\.0*)?$/.test(value)) return 'cl-secondary';
	return value.startsWith('-') !== (id === 'refund_amount')
		? 'cl-danger'
		: 'cl-success';
}
function analysisLink(stores?: string[]) {
	return {
		path: '/commerce/analysis',
		query: {
			question: '分析支付 GMV 的变化、主要贡献店铺和商品，并给出可核验的证据。',
			stores: (stores || context.selectedStoreIds.value).join(','),
			start: context.dateRange.value.start,
			endExclusive: context.dateRange.value.endExclusive,
			comparison: context.comparison.value,
		},
	};
}
let generation = 0,
	controller: AbortController | undefined;
async function load() {
	const g = ++generation;
	controller?.abort();
	controller = new AbortController();
	data.value = null;
	evidenceId.value = null;
	loading.value = true;
	error.value = '';
	alertError.value = '';
	const results = await Promise.allSettled([
		commerceApi.overview(
			{
				storeIds: context.selectedStoreIds.value,
				...context.dateRange.value,
				comparison: context.comparison.value,
			},
			controller.signal,
		),
		commerceApi.listAnomalies(),
	]);
	if (g !== generation) return;
	if (results[0].status === 'fulfilled') data.value = results[0].value;
	else if (results[0].reason?.name !== 'AbortError')
		error.value =
			results[0].reason instanceof Error
				? results[0].reason.message
				: '经营数据暂时无法读取';
	if (results[1].status === 'fulfilled')
		anomalies.value = results[1].value.items;
	else {
		anomalies.value = [];
		alertError.value = '异常信息暂时不可用，请稍后刷新。';
	}
	loading.value = false;
}
watch(
	() => [
		context.selectedStoreIds.value.join(','),
		context.dateRange.value.start,
		context.dateRange.value.endExclusive,
		context.comparison.value,
	],
	load,
);
onMounted(load);
onBeforeUnmount(() => {
	generation++;
	controller?.abort();
});
</script>
<template>
	<div class="overview-page">
		<header class="cl-page-heading">
			<div>
				<h1>经营总览</h1>
				<p>看清经营变化，让每一次判断有据可依。</p>
			</div>
			<CommerceScopeBar @refresh="load" />
		</header>
		<div v-if="loading" class="cl-grid four" aria-busy="true">
			<div v-for="i in 4" :key="i" class="cl-skeleton cl-skeleton-card" />
		</div>
		<div v-else-if="error" class="cl-card cl-empty" role="alert">
			<CommerceIcon name="alert" :size="32" />
			<h2>经营数据暂时无法加载</h2>
			<p>{{ error }}</p>
			<button class="cl-button primary" @click="load">重新加载</button>
		</div>
		<template v-else-if="data">
			<div
				class="cl-banner"
				:class="data.quality.status !== 'COMPLETE' ? 'warning' : ''"
			>
				<CommerceIcon name="info" :size="15" /><span
					>{{
						data.synthetic
							? '当前为合成经营数据，用于验证分析与证据流程。'
							: '已发布数据 · 所有金额以人民币展示。'
					}}
					<strong v-if="data.quality.status !== 'COMPLETE'"
						>数据部分可用，缺失不代表零。</strong
					></span
				><span class="cl-quality-meta"
					>业务水位
					{{
						data.quality.businessWatermark?.slice(0, 16).replace('T', ' ')
					}}</span
				>
			</div>
			<div class="cl-grid four overview-kpis">
				<section
					v-for="metric in metrics"
					:key="metric.id"
					class="cl-card kpi-card"
				>
					<div class="kpi-heading">
						<span>{{ metric.label }}</span
						><span class="kpi-icon"
							><CommerceIcon :name="metric.icon" :size="16"
						/></span>
					</div>
					<button
						class="kpi-value"
						:aria-label="`${metric.label} ${formatCell(getCell(data.totals, metric.id))}，查看证据`"
						@click="evidenceId = data.evidenceId"
					>
						{{ formatCell(getCell(data.totals, metric.id)) }}
					</button>
					<div class="kpi-change">
						<span :class="tone(data.totals, metric.id)"
							>{{ direction(data.totals, metric.id) }}
							{{
								formatCell(getCell(data.totals, `${metric.id}_change_ratio`))
							}}</span
						><small>较对比期</small>
					</div>
					<p>{{ metric.hint }}</p>
				</section>
			</div>
			<div class="cl-grid two overview-main">
				<section class="cl-card trend-card">
					<div class="cl-card-header">
						<div>
							<h2>经营趋势</h2>
							<p>
								{{ dateRangeLabel(data.trendRange) }} · 对比
								{{ dateRangeLabel(data.trendComparisonRange) }}
							</p>
						</div>
						<select
							v-model="metricId"
							class="trend-select"
							aria-label="趋势指标"
						>
							<option
								v-for="metric in metrics"
								:key="metric.id"
								:value="metric.id"
							>
								{{ metric.label }}
							</option>
						</select>
					</div>
					<CommerceTrendChart
						:rows="data.trend"
						:metric-id="metricId"
						:label="currentLabel"
					/><button
						class="cl-text-button trend-evidence"
						@click="evidenceId = data.trendEvidenceId"
					>
						查看趋势证据 <CommerceIcon name="arrow-right" :size="12" />
					</button>
				</section>
				<section class="cl-card insight-card">
					<div class="insight-heading">
						<span class="insight-icon"
							><CommerceIcon name="sparkles" :size="20"
						/></span>
						<div>
							<h2>经营变化摘要</h2>
							<p>从统一口径出发，发现调查方向</p>
						</div>
					</div>
					<template v-if="summary?.storeId"
						><span
							class="cl-badge"
							:class="summary.type === 'DECLINE' ? 'warning' : 'success'"
							>{{
								summary.type === 'DECLINE' ? '主要下降贡献' : '主要变化贡献'
							}}</span
						>
						<h3>{{ storeName(summary.storeId) }}值得关注</h3>
						<p class="insight-copy">
							当前范围支付 GMV 较对比期变化
							<strong>{{
								formatDecimal(summary.totalDeltaCents, 'CNY_CENT')
							}}</strong
							>。其中{{ storeName(summary.storeId) }}变化
							<strong>{{
								formatDecimal(summary.deltaCents, 'CNY_CENT')
							}}</strong
							><template v-if="summary.netContributionRatio != null"
								>，占净变化的
								<strong>{{
									formatDecimal(summary.netContributionRatio, 'RATIO')
								}}</strong></template
							>。
						</p>
						<button
							class="cl-text-button"
							@click="evidenceId = summary.evidenceId || data.evidenceId"
						>
							查看汇总证据 <CommerceIcon name="arrow-right" :size="12" />
						</button>
						<div class="insight-note">
							<CommerceIcon
								name="info"
								:size="14"
							/>贡献描述经营变化，不代表已经确认原因。
						</div></template
					>
					<div v-else class="insight-copy">
						当前范围没有可比较的变化摘要。请核对业务日期和数据完整性后再发起诊断。
					</div>
					<NuxtLink
						v-if="canAnalyze"
						class="cl-button primary insight-action"
						:to="analysisLink()"
						><CommerceIcon
							name="sparkles"
							:size="15" />生成诊断计划<CommerceIcon
							name="arrow-right"
							:size="14"
					/></NuxtLink>
				</section>
			</div>
			<section class="cl-card store-card">
				<div class="cl-card-header">
					<div>
						<h2>店铺经营表现</h2>
						<p>
							{{ dateRangeLabel(data.dateRange) }} · 对比
							{{ dateRangeLabel(data.comparisonRange) }} ·
							{{ data.scope.storeIds.length }} 家授权店铺
						</p>
					</div>
					<button class="cl-button small" @click="evidenceId = data.evidenceId">
						查看数据证据<CommerceIcon name="arrow-right" :size="12" />
					</button>
				</div>
				<div class="cl-table-wrap">
					<table class="cl-table">
						<thead>
							<tr>
								<th>店铺</th>
								<th class="numeric">支付 GMV</th>
								<th class="numeric">较对比期</th>
								<th class="numeric">支付订单</th>
								<th class="numeric">订单转化率</th>
								<th class="numeric">退款金额</th>
								<th>操作</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="row in data.stores" :key="row.rowKey">
								<td>
									<div class="store-identity">
										<span class="store-mark" :class="row.dimensions.store">{{
											storeName(row.dimensions.store).slice(0, 1)
										}}</span>
										<div>
											<strong>{{ storeName(row.dimensions.store) }}</strong
											><small>{{
												context.stores.value.find(
													(store) => store.storeId === row.dimensions.store,
												)?.platform
											}}</small>
										</div>
									</div>
								</td>
								<td class="numeric store-gmv">
									{{ formatCell(getCell(row, 'paid_gmv')) }}
								</td>
								<td class="numeric">
									<span :class="tone(row, 'paid_gmv')"
										>{{ direction(row, 'paid_gmv') }}
										{{
											formatCell(getCell(row, 'paid_gmv_change_ratio'))
										}}</span
									>
								</td>
								<td class="numeric">
									{{ formatCell(getCell(row, 'paid_orders')) }}
								</td>
								<td class="numeric">
									{{ formatCell(getCell(row, 'order_conversion_rate')) }}
								</td>
								<td class="numeric">
									{{ formatCell(getCell(row, 'refund_amount')) }}
								</td>
								<td>
									<NuxtLink
										v-if="canAnalyze"
										class="cl-text-button"
										:to="analysisLink([row.dimensions.store!])"
										>诊断<CommerceIcon
											name="arrow-right"
											:size="12" /></NuxtLink
									><span v-else class="cl-muted">只读</span>
								</td>
							</tr>
						</tbody>
					</table>
				</div>
			</section>
			<div class="cl-grid two overview-bottom">
				<section class="cl-card">
					<div class="cl-card-header">
						<div>
							<h2>需要关注的异常</h2>
							<p>当前店铺范围内尚未恢复的监控信号</p>
						</div>
						<NuxtLink class="cl-text-button" to="/commerce/anomalies"
							>全部异常<CommerceIcon name="arrow-right" :size="12"
						/></NuxtLink>
					</div>
					<p v-if="alertError" class="cl-secondary">{{ alertError }}</p>
					<div v-else-if="!selectedAnomalies.length" class="overview-empty">
						<CommerceIcon name="shield" :size="25" /><span>暂无待处理异常</span
						><small>可在异常中心配置监控规则</small>
					</div>
					<NuxtLink
						v-for="item in selectedAnomalies"
						:key="item.anomalyId"
						class="alert-row"
						:to="{
							path: '/commerce/anomalies',
							query: { anomalyId: item.anomalyId },
						}"
						><span class="alert-icon"
							><CommerceIcon name="alert" :size="17"
						/></span>
						<div>
							<strong>{{ item.reason }}</strong
							><small
								>{{ dateRangeLabel(item.window) }} ·
								{{ item.scope.storeIds.map(storeName).join('、') }}</small
							>
						</div>
						<span class="cl-badge warning">{{
							item.status === 'ACKNOWLEDGED' ? '已确认' : '待处理'
						}}</span
						><CommerceIcon name="chevron-down" :size="14"
					/></NuxtLink>
				</section>
				<section class="cl-card provenance-card">
					<div class="cl-card-header">
						<h2>数据与口径</h2>
						<NuxtLink class="cl-text-button" to="/commerce/data"
							>数据中心<CommerceIcon name="arrow-right" :size="12"
						/></NuxtLink>
					</div>
					<div>
						<span>当前完整性</span
						><span
							class="cl-badge"
							:class="
								data.quality.status === 'COMPLETE' ? 'success' : 'warning'
							"
							>{{
								data.quality.status === 'COMPLETE' ? '完整可用' : '部分可用'
							}}</span
						>
					</div>
					<div>
						<span>支付客单价</span
						><strong>{{ formatCell(getCell(data.totals, 'aov')) }}</strong>
					</div>
					<div>
						<span>访问会话</span
						><strong>{{
							formatCell(getCell(data.totals, 'visitor_sessions'))
						}}</strong>
					</div>
					<div>
						<span>数据版本</span
						><small :title="data.datasetVersionId">{{
							data.datasetVersionId
						}}</small>
					</div>
					<p>
						退款按退款成功日计入；净收款额不等同于利润。<NuxtLink
							to="/commerce/metrics"
							>查看指标定义</NuxtLink
						>
					</p>
				</section>
			</div> </template
		><CommerceEvidenceDrawer
			:evidence-id="evidenceId"
			@close="evidenceId = null"
		/>
	</div>
</template>
<style scoped>
.overview-kpis {
	margin-bottom: 20px;
}
.kpi-card {
	padding: 19px 21px;
}
.kpi-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	color: var(--cl-secondary);
	font-size: 12px;
}
.kpi-icon {
	display: grid;
	place-items: center;
	width: 31px;
	height: 31px;
	background: #f4f5ff;
	border-radius: 8px;
	color: var(--cl-primary);
}
.kpi-value {
	font-size: 29px;
	font-weight: 650;
	letter-spacing: -1px;
	background: none;
	border: 0;
	color: var(--cl-text);
	padding: 11px 0 7px;
	line-height: 1.3;
	text-align: left;
	max-width: 100%;
}
.kpi-value:hover {
	color: var(--cl-primary);
}
.kpi-change {
	display: flex;
	gap: 8px;
	align-items: center;
	font-size: 11px;
	font-weight: 550;
}
.kpi-change small {
	font-size: 10px;
	font-weight: 400;
}
.kpi-card p {
	font-size: 10px;
	color: var(--cl-muted);
	margin-top: 12px;
}
.overview-main {
	margin-bottom: 20px;
}
.trend-card .cl-card-header {
	margin-bottom: 2px;
}
.trend-select {
	font: inherit;
	font-size: 11px;
	background: #fff;
	border: 1px solid var(--cl-border);
	border-radius: 7px;
	padding: 6px 10px;
	color: var(--cl-secondary);
}
.trend-evidence {
	float: right;
	font-size: 10px;
}
.insight-card {
	background: linear-gradient(135deg, #f9faff, #fff);
	display: flex;
	flex-direction: column;
}
.insight-heading {
	display: flex;
	align-items: center;
	gap: 12px;
	margin-bottom: 20px;
}
.insight-heading h2 {
	font-size: 16px;
}
.insight-heading p {
	font-size: 10px;
	color: var(--cl-secondary);
	margin-top: 2px;
}
.insight-icon {
	display: grid;
	place-items: center;
	background: #e9ecff;
	border-radius: 11px;
	width: 42px;
	height: 42px;
	color: var(--cl-primary);
}
.insight-card > .cl-badge {
	align-self: flex-start;
}
.insight-card h3 {
	font-size: 18px;
	margin: 13px 0 9px;
}
.insight-copy {
	font-size: 12px;
	color: var(--cl-secondary);
	line-height: 1.95;
}
.insight-copy strong {
	color: var(--cl-text);
	font-weight: 600;
}
.insight-card > .cl-text-button {
	margin-top: 9px;
	font-size: 11px;
	align-self: flex-start;
}
.insight-note {
	display: flex;
	gap: 7px;
	font-size: 10px;
	color: var(--cl-muted);
	margin: 15px 0;
}
.insight-action {
	margin-top: auto;
}
.store-card {
	margin-bottom: 20px;
}
.store-identity {
	display: flex;
	align-items: center;
	gap: 10px;
}
.store-identity strong {
	font-weight: 550;
}
.store-identity small {
	display: block;
	font-size: 10px;
	margin-top: 1px;
}
.store-mark {
	display: grid;
	place-items: center;
	width: 32px;
	height: 32px;
	border-radius: 8px;
	background: #edf1ff;
	color: #586ac8;
	font-weight: 650;
}
.store-mark.s1 {
	background: #ffefe9;
	color: #c67b5c;
}
.store-mark.s3 {
	background: #e9f6f2;
	color: #5c9c89;
}
.store-gmv {
	font-weight: 600;
}
.overview-empty {
	display: flex;
	align-items: center;
	gap: 12px;
	padding: 17px 0;
	color: var(--cl-secondary);
	font-size: 12px;
}
.overview-empty small {
	margin-left: auto;
	font-size: 10px;
}
.alert-row {
	display: flex;
	gap: 12px;
	align-items: center;
	padding: 14px 0;
	border-bottom: 1px solid var(--cl-border);
	color: var(--cl-text) !important;
}
.alert-row:last-child {
	border: 0;
}
.alert-icon {
	color: var(--cl-warning);
	background: #fff4e7;
	width: 32px;
	height: 32px;
	display: grid;
	place-items: center;
	border-radius: 8px;
	flex-shrink: 0;
}
.alert-row strong {
	display: block;
	font-size: 12px;
	font-weight: 500;
}
.alert-row small {
	display: block;
	font-size: 10px;
}
.alert-row .cl-badge {
	margin-left: auto;
	white-space: nowrap;
}
.provenance-card > div:not(.cl-card-header) {
	display: flex;
	justify-content: space-between;
	gap: 12px;
	align-items: center;
	font-size: 11px;
	margin: 12px 0;
}
.provenance-card > div > span:first-child {
	color: var(--cl-secondary);
}
.provenance-card strong {
	font-weight: 550;
}
.provenance-card small {
	font-size: 10px;
	max-width: 60%;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}
.provenance-card > p {
	font-size: 10px;
	color: var(--cl-muted);
	border-top: 1px solid var(--cl-border);
	padding-top: 13px;
	margin-top: 15px;
}
@media (max-width: 639px) {
	.kpi-card {
		padding: 15px;
	}
	.kpi-value {
		font-size: 22px;
		letter-spacing: -0.6px;
	}
	.kpi-heading {
		font-size: 11px;
	}
	.kpi-icon {
		width: 26px;
		height: 26px;
	}
	.kpi-card p {
		font-size: 9px;
	}
	.kpi-change {
		font-size: 10px;
		gap: 5px;
	}
	.overview-empty {
		flex-wrap: wrap;
	}
	.overview-empty small {
		margin-left: 0;
	}
	.trend-card .cl-card-header {
		align-items: flex-start;
	}
	.alert-row .cl-badge {
		display: none;
	}
}
</style>
