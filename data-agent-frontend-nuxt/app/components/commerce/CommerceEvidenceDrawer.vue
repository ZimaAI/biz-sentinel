<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { commerceApi } from '~/services/commerceApi';
import { formatCell, getCell } from '~/utils/commerceFormat';
import type { Evidence } from '~/types/commerce';

const props = defineProps<{ evidenceId: string | null }>();
const emit = defineEmits<{ close: [] }>();
const evidence = ref<Evidence | null>(null);
const loading = ref(false);
const error = ref('');
const tab = ref('summary');
const panel = ref<HTMLElement | null>(null);
const closeButton = ref<HTMLButtonElement | null>(null);
const copyFeedback = ref('');
const tabs = [
	{ id: 'summary', label: '结论引用' },
	{ id: 'rows', label: '结果表' },
	{ id: 'query', label: '查询与参数' },
	{ id: 'metadata', label: '元数据' },
];
let generation = 0;
let previousFocus: HTMLElement | null = null;
let previousOverflow = '';
let copyTimer: ReturnType<typeof setTimeout> | undefined;
const result = computed(() => evidence.value?.result);
const columns = computed(() => result.value?.columns || []);
const kindLabel = computed(
	() =>
		({
			QUERY_RESULT: '经营指标聚合',
			DECOMPOSITION: 'GMV 对称分解',
			INVENTORY_SIGNAL: '商品与库存相关信号',
		})[evidence.value?.kind || ''] || '受控聚合证据',
);

async function load() {
	const id = props.evidenceId;
	const g = ++generation;
	evidence.value = null;
	error.value = '';
	loading.value = !!id;
	if (!id) return;
	try {
		const data = await commerceApi.getEvidence(id);
		if (g === generation) evidence.value = data;
	} catch (e) {
		if (g === generation)
			error.value =
				e instanceof Error ? e.message : '证据暂时无法读取，请重试。';
	} finally {
		if (g === generation) loading.value = false;
	}
}

function keydown(event: KeyboardEvent) {
	if (!props.evidenceId) return;
	if (event.key === 'Escape') {
		event.preventDefault();
		emit('close');
		return;
	}
	if (event.key !== 'Tab') return;
	const items = Array.from(
		panel.value?.querySelectorAll<HTMLElement>(
			'button:not(:disabled), a[href], input:not(:disabled), select, textarea, [tabindex="0"]',
		) || [],
	);
	const first = items[0],
		last = items.at(-1);
	if (event.shiftKey && document.activeElement === first) {
		event.preventDefault();
		last?.focus();
	} else if (!event.shiftKey && document.activeElement === last) {
		event.preventDefault();
		first?.focus();
	}
}

function releaseFocus() {
	document.removeEventListener('keydown', keydown);
	document.body.style.overflow = previousOverflow;
	previousFocus?.focus();
	previousFocus = null;
}

watch(
	() => props.evidenceId,
	async (id, old) => {
		tab.value = 'summary';
		copyFeedback.value = '';
		if (id && !old && typeof document !== 'undefined') {
			previousFocus = document.activeElement as HTMLElement;
			previousOverflow = document.body.style.overflow;
			document.body.style.overflow = 'hidden';
			document.addEventListener('keydown', keydown);
			await nextTick();
			closeButton.value?.focus();
		}
		if (!id && old) releaseFocus();
		void load();
	},
	{ immediate: true, flush: 'post' },
);

async function copyQuery() {
	try {
		await navigator.clipboard.writeText(
			`${evidence.value?.sqlTemplate || evidence.value?.calculation || ''}\n\n${JSON.stringify(evidence.value?.redactedParameters || {}, null, 2)}`,
		);
		copyFeedback.value = '查询与脱敏参数已复制';
	} catch {
		copyFeedback.value = '复制失败，请选中文本手动复制';
	}
	clearTimeout(copyTimer);
	copyTimer = setTimeout(() => {
		copyFeedback.value = '';
	}, 3000);
}

onBeforeUnmount(() => {
	generation++;
	clearTimeout(copyTimer);
	if (props.evidenceId) releaseFocus();
});
</script>

<template>
	<Teleport to="body">
		<div
			v-if="evidenceId"
			class="cl-app cl-evidence-backdrop"
			@mousedown.self="$emit('close')"
		>
			<section
				ref="panel"
				class="cl-evidence-drawer"
				role="dialog"
				aria-modal="true"
				aria-labelledby="cl-evidence-title"
			>
				<header class="cl-evidence-head">
					<div>
						<span class="cl-evidence-kicker">EVIDENCE / 可追溯证据</span>
						<h2 id="cl-evidence-title">{{ kindLabel }}</h2>
						<p class="cl-evidence-id">{{ evidenceId }}</p>
					</div>
					<button
						ref="closeButton"
						type="button"
						class="cl-button cl-evidence-close"
						aria-label="关闭证据抽屉"
						@click="$emit('close')"
					>
						<span aria-hidden="true">×</span>
					</button>
				</header>
				<nav class="cl-evidence-tabs" aria-label="证据内容" role="tablist">
					<button
						v-for="item in tabs"
						:id="`cl-evidence-tab-${item.id}`"
						:key="item.id"
						type="button"
						role="tab"
						:aria-selected="tab === item.id"
						:class="{ active: tab === item.id }"
						@click="tab = item.id"
					>
						{{ item.label }}
					</button>
				</nav>
				<div
					class="cl-evidence-body"
					role="tabpanel"
					:aria-labelledby="`cl-evidence-tab-${tab}`"
					:aria-busy="loading"
				>
					<div v-if="loading" class="cl-evidence-loading">
						<div v-for="n in 5" :key="n" class="cl-evidence-skeleton" />
						<p>正在重新核验权限并读取证据…</p>
					</div>
					<div v-else-if="error" class="cl-banner" role="alert">
						{{ error
						}}<button class="cl-button soft" type="button" @click="load">
							重试
						</button>
					</div>
					<template v-else-if="evidence">
						<div
							v-if="result?.quality?.status !== 'COMPLETE' || result?.truncated"
							class="cl-banner warning"
						>
							{{
								result?.truncated
									? '结果已截断，不能据此解释全部分组。'
									: '证据包含缺失数据；缺失不代表零。'
							}}
						</div>
						<template v-if="tab === 'summary'">
							<p class="cl-evidence-description">
								这份证据固定于生成时的授权范围与数据版本。结论中的数值可在结果表中核对。
							</p>
							<dl class="cl-evidence-meta">
								<dt>店铺范围</dt>
								<dd>{{ evidence.scope?.storeIds?.join('、') || '未提供' }}</dd>
								<dt>分析日期</dt>
								<dd>
									{{ result?.dateRange?.start }} 至
									{{ result?.dateRange?.endExclusive }}（不含）
								</dd>
								<dt>对比日期</dt>
								<dd>
									{{
										result?.comparisonRange
											? `${result.comparisonRange.start} 至 ${result.comparisonRange.endExclusive}（不含）`
											: '不适用'
									}}
								</dd>
								<dt>数据版本</dt>
								<dd>{{ evidence.datasetVersionId }}</dd>
								<dt>指标版本</dt>
								<dd>
									{{
										result?.metricVersions
											? Object.entries(result.metricVersions)
													.map(([key, version]) => `${key} · v${version}`)
													.join(' / ')
											: '不适用'
									}}
								</dd>
								<dt>完整性</dt>
								<dd>
									<span
										class="cl-badge"
										:class="
											result?.quality?.status === 'COMPLETE'
												? 'success'
												: 'warning'
										"
										>{{
											result?.quality?.status === 'COMPLETE'
												? '完整'
												: '部分可用'
										}}</span
									>
								</dd>
							</dl>
							<div
								v-if="evidence.kind === 'INVENTORY_SIGNAL'"
								class="cl-banner warning"
							>
								库存与经营变化同时出现仅构成相关线索，不能据此证明因果或估算可追回的金额。
							</div>
							<div v-if="evidence.calculation" class="cl-banner">
								数学恒等分解使用服务端高精度运算，残差经独立验证。
							</div>
							<button
								class="cl-button soft"
								type="button"
								@click="tab = 'rows'"
							>
								查看结果明细 <span aria-hidden="true">→</span>
							</button>
						</template>
						<template v-else-if="tab === 'rows'">
							<h3>
								聚合结果
								<span class="cl-evidence-muted"
									>{{ result?.rowCount ?? result?.rows?.length ?? 0 }} 行</span
								>
							</h3>
							<div v-if="!result?.rows?.length" class="cl-empty">
								当前证据没有可展示的聚合行，请检查数据质量说明。
							</div>
							<div v-else class="cl-evidence-table-wrap">
								<table class="cl-table">
									<thead>
										<tr>
											<th>分组</th>
											<th v-for="column in columns" :key="column.field">
												{{ column.label || column.field }}
											</th>
										</tr>
									</thead>
									<tbody>
										<tr v-for="row in result.rows" :key="row.rowKey">
											<td>
												{{
													Object.values(row.dimensions || {}).join(' / ') ||
													'总体'
												}}
											</td>
											<td v-for="column in columns" :key="column.field">
												{{
													getCell(row, column.field)
														? formatCell(getCell(row, column.field))
														: (row.dimensions?.[column.field] ?? '—')
												}}
											</td>
										</tr>
									</tbody>
								</table>
							</div>
							<p class="cl-evidence-description">
								金额与比率由服务端计算。空值保留缺失含义，不替换为零。
							</p>
						</template>
						<template v-else-if="tab === 'query'">
							<div class="cl-evidence-section-heading">
								<h3>
									{{ evidence.calculation ? '确定性计算' : '已执行的受控查询' }}
								</h3>
								<button class="cl-button soft" type="button" @click="copyQuery">
									复制
								</button>
							</div>
							<pre tabindex="0">{{
								evidence.sqlTemplate ||
								evidence.calculation ||
								'此证据不包含 SQL 查询。'
							}}</pre>
							<h3>脱敏参数</h3>
							<pre tabindex="0">{{
								JSON.stringify(evidence.redactedParameters || {}, null, 2)
							}}</pre>
							<p class="cl-evidence-description">
								店铺与数据版本由服务端身份上下文绑定。查询仅供核验，不能在此修改或执行。
							</p>
							<p class="cl-evidence-copy-feedback" aria-live="polite">
								{{ copyFeedback }}
							</p>
						</template>
						<template v-else>
							<h3>来源与验证</h3>
							<dl class="cl-evidence-meta">
								<dt>登记时间</dt>
								<dd>
									{{
										evidence.createdAt
											? new Date(evidence.createdAt).toLocaleString('zh-CN')
											: '未提供'
									}}
								</dd>
								<dt>查询耗时</dt>
								<dd>
									{{
										evidence.durationMs == null
											? '未提供'
											: `${evidence.durationMs} ms`
									}}
								</dd>
								<dt>指标清单哈希</dt>
								<dd class="cl-evidence-hash">
									{{ evidence.metricManifestHash }}
								</dd>
								<dt>结果哈希</dt>
								<dd class="cl-evidence-hash">
									{{ evidence.resultHash || '未提供' }}
								</dd>
								<dt>查询产物</dt>
								<dd>{{ result?.queryArtifactId || '不适用' }}</dd>
								<dt>原始证据</dt>
								<dd>{{ evidence.sourceEvidenceId || '直接聚合' }}</dd>
							</dl>
						</template>
					</template>
				</div>
				<footer class="cl-evidence-footer">
					每次打开均重新鉴权 · 证据范围不会随页面筛选改变
				</footer>
			</section>
		</div>
	</Teleport>
</template>

<style scoped>
.cl-evidence-backdrop {
	position: fixed;
	inset: 0;
	z-index: 1600;
	display: flex;
	justify-content: flex-end;
	background: rgba(19, 30, 54, 0.25);
	backdrop-filter: blur(2px);
}
.cl-evidence-drawer {
	width: 620px;
	max-width: 94vw;
	height: 100dvh;
	display: flex;
	flex-direction: column;
	background: var(--cl-surface, #fff);
	color: var(--cl-text, #18243d);
	box-shadow: -15px 0 60px #1c2a4812;
	font-family:
		Inter,
		-apple-system,
		BlinkMacSystemFont,
		'Segoe UI',
		'Microsoft YaHei',
		sans-serif;
}
.cl-evidence-head {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	padding: 30px 26px 21px;
	border-bottom: 1px solid var(--cl-border);
	gap: 16px;
}
.cl-evidence-head h2 {
	font-size: 21px;
	font-weight: 700;
	margin: 16px 0 8px;
}
.cl-evidence-kicker {
	color: var(--cl-primary);
	font-size: 10px;
	font-weight: 700;
	letter-spacing: 1.5px;
}
.cl-evidence-id {
	color: var(--cl-secondary);
	font-size: 11px;
	word-break: break-all;
}
.cl-evidence-close {
	min-width: 34px;
	width: 34px;
	height: 34px;
	font-size: 24px;
	padding: 0;
}
.cl-evidence-tabs {
	display: flex;
	padding: 0 22px;
	border-bottom: 1px solid var(--cl-border);
}
.cl-evidence-tabs button {
	flex: 1;
	padding: 15px 4px;
	background: none;
	border: 0;
	border-bottom: 2px solid transparent;
	color: var(--cl-secondary);
	font-size: 12px;
	cursor: pointer;
}
.cl-evidence-tabs button.active {
	border-bottom-color: var(--cl-primary);
	color: var(--cl-primary);
}
.cl-evidence-body {
	flex: 1;
	overflow: auto;
	padding: 26px;
}
.cl-evidence-body h3 {
	font-size: 14px;
	margin: 0 0 14px;
}
.cl-evidence-description {
	font-size: 12px;
	color: var(--cl-secondary);
	line-height: 1.9;
	margin: 0 0 20px;
}
.cl-evidence-meta {
	display: grid;
	grid-template-columns: 102px minmax(0, 1fr);
	gap: 18px 15px;
	font-size: 12px;
	margin: 24px 0;
}
.cl-evidence-meta dt {
	color: var(--cl-muted);
}
.cl-evidence-meta dd {
	margin: 0;
	overflow-wrap: anywhere;
}
.cl-evidence-hash {
	font-family: ui-monospace, monospace;
	font-size: 11px;
}
.cl-evidence-body .cl-banner {
	margin-bottom: 20px;
}
.cl-evidence-section-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	margin-bottom: 14px;
}
.cl-evidence-section-heading h3 {
	margin: 0;
}
.cl-evidence-body pre {
	padding: 18px;
	background: #f4f6fa;
	border: 1px solid var(--cl-border);
	border-radius: 10px;
	font:
		11px/1.8 ui-monospace,
		SFMono-Regular,
		Consolas,
		monospace;
	overflow: auto;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
	margin: 0 0 26px;
	color: #4f6080;
}
.cl-evidence-table-wrap {
	overflow: auto;
	margin: 14px -5px 20px;
}
.cl-evidence-table-wrap .cl-table {
	min-width: 560px;
	font-size: 12px;
}
.cl-evidence-table-wrap td {
	white-space: nowrap;
}
.cl-evidence-muted {
	color: var(--cl-muted);
	font-size: 11px;
	font-weight: 400;
	margin-left: 10px;
}
.cl-evidence-footer {
	border-top: 1px solid var(--cl-border);
	padding: 18px 26px;
	color: var(--cl-muted);
	font-size: 10px;
}
.cl-evidence-copy-feedback {
	color: var(--cl-success);
	font-size: 12px;
	min-height: 20px;
}
.cl-evidence-skeleton {
	height: 35px;
	margin-bottom: 15px;
	border-radius: 8px;
	background: linear-gradient(90deg, #f2f4fa, #fafbfe, #f2f4fa);
	animation: evidence-pulse 1.5s infinite;
}
.cl-evidence-loading p {
	color: var(--cl-muted);
	font-size: 12px;
}
@keyframes evidence-pulse {
	50% {
		opacity: 0.45;
	}
}
</style>
