<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import { useCommerceContext } from '~/composables/useCommerceContext';
import { dateRangeLabel } from '~/utils/commerceFormat';
import type { Report } from '~/types/commerce';

definePageMeta({ layout: 'commerce' });
const route = useRoute(),
	router = useRouter();
const context = useCommerceContext();
const items = ref<Report[]>([]),
	selected = ref<Report | null>(null);
const listLoading = ref(true),
	detailLoading = ref(false),
	exporting = ref(false),
	sharing = ref(false);
const listError = ref(''),
	detailError = ref(''),
	actionError = ref(''),
	notice = ref('');
const evidenceId = ref<string | null>(null),
	sharingPrompt = ref(false);
const nextCursor = ref<string | null>(null),
	currentCursor = ref<string | undefined>(),
	previousCursors = ref<(string | undefined)[]>([]);
let listGeneration = 0,
	detailGeneration = 0;
let selectedId = '';
let noticeTimer: ReturnType<typeof setTimeout> | undefined;
const mainClaims = computed(
	() =>
		selected.value?.claims.filter((claim) =>
			['OBSERVATION', 'DECOMPOSITION', 'HYPOTHESIS'].includes(claim.type),
		) || [],
);
const recommendations = computed(
	() =>
		selected.value?.claims.filter((claim) => claim.type === 'RECOMMENDATION') ||
		[],
);
const canShare = computed(
	() =>
		context.me.value?.roles.some((role) =>
			['TENANT_ADMIN', 'OPS_MANAGER', 'STORE_OPERATOR'].includes(role),
		) || false,
);
const scopeNames = (ids: string[]) =>
	ids
		.map(
			(id) =>
				context.stores.value.find((store) => store.storeId === id)?.name || id,
		)
		.join('、');

async function loadList(cursor?: string) {
	const g = ++listGeneration;
	listLoading.value = true;
	listError.value = '';
	try {
		const page = await commerceApi.listReports({ limit: 20, cursor });
		if (g !== listGeneration) return;
		items.value = page.items;
		nextCursor.value = page.nextCursor;
		currentCursor.value = cursor;
		const routeId =
			typeof route.query.reportId === 'string' ? route.query.reportId : '';
		if (routeId && routeId !== selectedId) await openReport(routeId, false);
		else if (!selectedId && page.items[0])
			await openReport(page.items[0].reportId);
	} catch (e) {
		if (g === listGeneration) {
			items.value = [];
			listError.value =
				e instanceof Error ? e.message : '报告列表暂时无法读取。';
		}
	} finally {
		if (g === listGeneration) listLoading.value = false;
	}
}

async function openReport(id: string, updateRoute = true) {
	const g = ++detailGeneration;
	selectedId = id;
	detailLoading.value = true;
	selected.value = null;
	detailError.value = '';
	actionError.value = '';
	evidenceId.value = null;
	sharingPrompt.value = false;
	if (updateRoute)
		await router.replace({ query: { ...route.query, reportId: id } });
	try {
		const report = await commerceApi.getReport(id);
		if (g === detailGeneration) selected.value = report;
	} catch (e) {
		if (g === detailGeneration)
			detailError.value =
				e instanceof Error ? e.message : '报告不存在或当前不可见。';
	} finally {
		if (g === detailGeneration) detailLoading.value = false;
	}
}

function nextPage() {
	if (!nextCursor.value || listLoading.value) return;
	previousCursors.value.push(currentCursor.value);
	void loadList(nextCursor.value);
}
function previousPage() {
	if (listLoading.value || !previousCursors.value.length) return;
	void loadList(previousCursors.value.pop());
}
function showNotice(text: string) {
	notice.value = text;
	clearTimeout(noticeTimer);
	noticeTimer = setTimeout(() => {
		notice.value = '';
	}, 3500);
}

async function download() {
	const report = selected.value;
	if (!report || exporting.value) return;
	exporting.value = true;
	actionError.value = '';
	try {
		const blob = await commerceApi.exportReport(report.reportId);
		const url = URL.createObjectURL(blob),
			anchor = document.createElement('a');
		anchor.href = url;
		anchor.download = `CommerceLens-${report.dateRange.start}.md`;
		document.body.append(anchor);
		anchor.click();
		anchor.remove();
		setTimeout(() => URL.revokeObjectURL(url), 1000);
		showNotice('报告已导出，包含当前报告固定范围的结构化结论与引用。');
	} catch (e) {
		actionError.value = e instanceof Error ? e.message : '导出失败，请重试。';
		if (e instanceof CommerceApiError && [401, 403, 404].includes(e.status)) {
			selected.value = null;
			evidenceId.value = null;
		}
	} finally {
		exporting.value = false;
	}
}

async function share() {
	const report = selected.value;
	if (!report || sharing.value) return;
	const visibility = report.visibility === 'TEAM' ? 'PRIVATE' : 'TEAM';
	sharing.value = true;
	actionError.value = '';
	try {
		const saved = await commerceApi.shareReport(report.reportId, visibility);
		if (selected.value?.reportId === report.reportId)
			selected.value = {
				...report,
				visibility: saved.visibility || visibility,
			};
		const listed = items.value.find(
			(item) => item.reportId === report.reportId,
		);
		if (listed) listed.visibility = visibility;
		sharingPrompt.value = false;
		showNotice(
			visibility === 'TEAM'
				? '已共享给具有完整范围访问权限的租户成员。'
				: '报告已恢复为私有。',
		);
	} catch (e) {
		actionError.value = e instanceof Error ? e.message : '共享范围未保存。';
	} finally {
		sharing.value = false;
	}
}

function clearSensitive() {
	listGeneration++;
	detailGeneration++;
	items.value = [];
	selected.value = null;
	selectedId = '';
	evidenceId.value = null;
	listLoading.value = false;
	detailLoading.value = false;
	detailError.value = '身份或授权范围已变化，请重新加载可见报告。';
}
watch(
	() => route.query.reportId,
	(id) => {
		if (typeof id === 'string' && id !== selectedId) void openReport(id, false);
	},
);
watch(
	() => `${context.me.value?.subjectId}:${context.me.value?.authzVersion}`,
	(now, previous) => {
		if (previous && now !== previous && selected.value) clearSensitive();
	},
);
onMounted(() => {
	void loadList();
});
onBeforeUnmount(() => {
	listGeneration++;
	detailGeneration++;
	clearTimeout(noticeTimer);
});
</script>

<template>
	<div class="cl-reports-page">
		<header class="cl-page-heading">
			<div>
				<h1>报告中心</h1>
				<p>报告固定生成时的范围、时间与数据版本，不随页面筛选改变。</p>
			</div>
			<button
				class="cl-button primary"
				type="button"
				:disabled="!selected || exporting"
				@click="download"
			>
				<CommerceIcon name="download" />{{
					exporting ? '正在导出…' : '导出 Markdown'
				}}
			</button>
		</header>
		<div v-if="notice" class="cl-banner success" role="status">
			{{ notice }}
		</div>
		<div v-if="actionError" class="cl-banner danger" role="alert">
			{{ actionError }}
		</div>
		<div class="cl-reports-layout">
			<aside class="cl-report-list" aria-label="已生成的报告">
				<div class="cl-report-list-heading">
					<span>历史报告</span
					><button
						type="button"
						aria-label="刷新报告列表"
						:disabled="listLoading"
						@click="loadList(currentCursor)"
					>
						<CommerceIcon name="refresh" />
					</button>
				</div>
				<template v-if="listLoading"
					><div v-for="n in 3" :key="n" class="cl-report-list-placeholder"
				/></template>
				<div v-else-if="listError" class="cl-banner danger" role="alert">
					{{ listError
					}}<button
						class="cl-button soft"
						type="button"
						@click="loadList(currentCursor)"
					>
						重试
					</button>
				</div>
				<div v-else-if="!items.length" class="cl-card cl-report-list-empty">
					<CommerceIcon name="file" />
					<p>尚无可见报告</p>
					<NuxtLink class="cl-button soft" to="/commerce/analysis"
						>发起诊断</NuxtLink
					>
				</div>
				<button
					v-for="item in listLoading ? [] : items"
					:key="item.reportId"
					type="button"
					class="cl-report-list-item"
					:class="{ selected: selectedId === item.reportId }"
					:aria-current="selectedId === item.reportId ? 'true' : undefined"
					@click="openReport(item.reportId)"
				>
					<div class="cl-report-list-item-top">
						<span
							class="cl-badge"
							:class="item.status === 'PARTIAL' ? 'warning' : ''"
							>{{
								item.status === 'PARTIAL'
									? '部分完成'
									: item.visibility === 'TEAM'
										? '团队共享'
										: '私有报告'
							}}</span
						><CommerceIcon name="file" />
					</div>
					<h2>{{ item.title }}</h2>
					<p>{{ scopeNames(item.scope.storeIds) }}</p>
					<p>{{ dateRangeLabel(item.dateRange) }}</p>
					<time>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</time>
				</button>
				<div
					v-if="previousCursors.length || nextCursor"
					class="cl-report-pagination"
				>
					<button
						class="cl-button"
						type="button"
						:disabled="!previousCursors.length || listLoading"
						@click="previousPage"
					>
						上一页</button
					><button
						class="cl-button"
						type="button"
						:disabled="!nextCursor || listLoading"
						@click="nextPage"
					>
						下一页
					</button>
				</div>
			</aside>

			<section
				v-if="detailLoading"
				class="cl-card cl-report-document cl-report-loading"
				aria-busy="true"
			>
				<div v-for="n in 8" :key="n" />
				<p>正在重新鉴权并校验证据引用…</p>
			</section>
			<section v-else-if="detailError" class="cl-card cl-report-document">
				<div class="cl-banner danger" role="alert">{{ detailError }}</div>
				<button
					v-if="selectedId"
					class="cl-button soft"
					type="button"
					@click="openReport(selectedId, false)"
				>
					重新读取报告</button
				><button
					v-else
					class="cl-button soft"
					type="button"
					@click="loadList()"
				>
					重新加载
				</button>
			</section>
			<article v-else-if="selected" class="cl-card cl-report-document">
				<div class="cl-report-document-top">
					<span class="cl-report-kicker">COMMERCE LENS / ANALYSIS NOTE</span
					><span v-if="selected.synthetic" class="cl-badge warning"
						>合成数据报告</span
					><span v-else class="cl-badge success">结构化诊断报告</span>
				</div>
				<h2>{{ selected.title }}</h2>
				<div class="cl-report-context">
					<p>
						{{ scopeNames(selected.scope.storeIds) }} <span>·</span>
						{{ dateRangeLabel(selected.dateRange) }}
					</p>
					<p>
						对比 {{ dateRangeLabel(selected.comparisonRange) }}
						<span>·</span> 数据版本 {{ selected.datasetVersionId }}
					</p>
					<p>
						运行 {{ selected.runId }} <span>·</span> 版本 v{{
							selected.version
						}}
					</p>
				</div>
				<div class="cl-report-document-tools">
					<span
						class="cl-badge"
						:class="selected.status === 'PARTIAL' ? 'warning' : 'success'"
						>{{
							selected.status === 'PARTIAL'
								? '部分完成 · 请留意缺失说明'
								: '证据与数值引用已验证'
						}}</span
					><button
						v-if="canShare"
						class="cl-report-share"
						type="button"
						:disabled="sharing"
						@click="sharingPrompt = !sharingPrompt"
					>
						{{
							selected.visibility === 'TEAM'
								? '团队共享 · 改为私有'
								: '私有 · 设置共享'
						}}
					</button>
				</div>
				<div v-if="sharingPrompt" class="cl-banner">
					<strong>{{
						selected.visibility === 'TEAM'
							? '将此报告恢复为私有？'
							: '共享给租户团队？'
					}}</strong>
					<p>
						团队成员仍须拥有报告全部店铺的访问权限；共享会同步作用于相关证据。
					</p>
					<div class="cl-report-share-actions">
						<button
							class="cl-button primary"
							type="button"
							:disabled="sharing"
							@click="share"
						>
							{{ sharing ? '正在保存…' : '确认修改' }}</button
						><button
							class="cl-button"
							type="button"
							@click="sharingPrompt = false"
						>
							保持当前范围
						</button>
					</div>
				</div>
				<blockquote v-if="mainClaims[0]" class="cl-report-lead">
					{{ mainClaims[0].text }}
				</blockquote>
				<div v-if="!mainClaims.length" class="cl-banner warning">
					此运行未形成足够的事实证据，请阅读下方澄清与分析边界。
				</div>
				<h3 class="cl-report-section-title">关键发现与证据</h3>
				<div class="cl-report-claims">
					<CommerceClaimCard
						v-for="claim in mainClaims"
						:key="claim.claimId"
						:claim="claim"
						@evidence="evidenceId = $event"
					/>
				</div>
				<section class="cl-report-boundaries">
					<h3>建议与分析边界</h3>
					<p v-for="claim in recommendations" :key="claim.claimId">
						{{ claim.text }}
					</p>
					<ul>
						<li v-for="limitation in selected.limitations" :key="limitation">
							{{ limitation }}
						</li>
					</ul>
				</section>
				<footer class="cl-report-document-footer">
					<p>
						生成于 {{ new Date(selected.createdAt).toLocaleString('zh-CN') }} ·
						点击任一证据引用可核对聚合结果、查询参数与版本。
					</p>
					<NuxtLink
						:to="{ path: '/commerce/runs', query: { runId: selected.runId } }"
						>查看运行审计 <span aria-hidden="true">→</span></NuxtLink
					>
				</footer>
			</article>
			<section v-else class="cl-card cl-report-empty">
				<span><CommerceIcon name="file" /></span>
				<h2>把调查沉淀为可复核的报告</h2>
				<p>完成诊断后，报告、原始范围与证据引用会保存在这里。</p>
				<NuxtLink class="cl-button primary" to="/commerce/analysis"
					>开始一次诊断<CommerceIcon name="arrow-right"
				/></NuxtLink>
			</section>
		</div>
		<CommerceEvidenceDrawer
			:evidence-id="evidenceId"
			@close="evidenceId = null"
		/>
	</div>
</template>

<style scoped>
.cl-reports-layout {
	display: grid;
	grid-template-columns: 266px minmax(0, 1fr);
	gap: 22px;
	align-items: start;
}
.cl-report-list {
	display: grid;
	gap: 12px;
}
.cl-report-list-heading {
	display: flex;
	justify-content: space-between;
	align-items: center;
	color: var(--cl-muted);
	font-size: 11px;
	padding: 0 3px 3px;
}
.cl-report-list-heading button {
	background: none;
	border: 0;
	padding: 3px;
	color: var(--cl-secondary);
	cursor: pointer;
}
.cl-report-list-heading :deep(svg) {
	width: 15px;
	height: 15px;
}
.cl-report-list-item {
	padding: 18px;
	border: 1px solid var(--cl-border);
	border-radius: 12px;
	background: var(--cl-surface);
	color: var(--cl-text);
	text-align: left;
	cursor: pointer;
	transition:
		border-color 0.15s,
		background 0.15s;
}
.cl-report-list-item:hover {
	border-color: #bfc5ff;
}
.cl-report-list-item.selected {
	background: #f5f6ff;
	border-color: #cbd1ff;
}
.cl-report-list-item-top {
	display: flex;
	justify-content: space-between;
	align-items: center;
	margin-bottom: 15px;
}
.cl-report-list-item-top :deep(svg) {
	width: 16px;
}
.cl-report-list-item h2 {
	font-size: 13px;
	font-weight: 600;
	margin: 0 0 10px;
	line-height: 1.7;
}
.cl-report-list-item p,
.cl-report-list-item time {
	display: block;
	color: var(--cl-secondary);
	font-size: 11px;
	line-height: 1.8;
	margin: 2px 0;
}
.cl-report-pagination {
	display: flex;
	justify-content: space-between;
	gap: 9px;
}
.cl-report-pagination button {
	flex: 1;
	font-size: 11px;
}
.cl-report-list-empty {
	padding: 28px 18px;
	text-align: center;
	color: var(--cl-muted);
	font-size: 12px;
}
.cl-report-list-empty :deep(svg) {
	width: 25px;
	margin-bottom: 8px;
}
.cl-report-list-empty p {
	margin: 0 0 16px;
}
.cl-report-document {
	padding: 30px;
	min-width: 0;
}
.cl-report-document-top {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 10px;
	margin-bottom: 21px;
}
.cl-report-kicker {
	font-size: 10px;
	font-weight: 700;
	letter-spacing: 1.4px;
	color: var(--cl-primary);
}
.cl-report-document > h2 {
	font-size: 24px;
	font-weight: 700;
	margin: 0 0 17px;
	line-height: 1.4;
}
.cl-report-context {
	font-size: 11px;
	color: var(--cl-secondary);
	line-height: 1.9;
	margin-bottom: 18px;
	overflow-wrap: anywhere;
}
.cl-report-context p {
	margin: 2px 0;
}
.cl-report-context span {
	color: var(--cl-muted);
	padding: 0 8px;
}
.cl-report-document-tools {
	display: flex;
	flex-wrap: wrap;
	justify-content: space-between;
	align-items: center;
	gap: 10px;
	margin: 15px 0 23px;
}
.cl-report-share {
	font-size: 11px;
	color: var(--cl-primary);
	border: 0;
	padding: 5px 0;
	background: none;
	cursor: pointer;
}
.cl-report-share-actions {
	display: flex;
	gap: 8px;
	margin-top: 12px;
}
.cl-report-lead {
	border-left: 3px solid #919aff;
	background: #f6f7ff;
	padding: 18px 20px;
	font-size: 14px;
	line-height: 1.9;
	margin: 22px 0 25px;
}
.cl-report-section-title {
	font-size: 14px;
	margin: 0 0 14px;
}
.cl-report-claims {
	display: grid;
	gap: 12px;
}
.cl-report-boundaries {
	border: 1px solid #f1e4cd;
	border-radius: 11px;
	background: #fffdf9;
	padding: 19px;
	margin-top: 18px;
}
.cl-report-boundaries h3 {
	font-size: 12px;
	color: var(--cl-warning);
	margin: 0 0 10px;
}
.cl-report-boundaries p,
.cl-report-boundaries li {
	font-size: 11px;
	color: var(--cl-secondary);
	line-height: 1.9;
	margin: 5px 0;
}
.cl-report-boundaries ul {
	margin: 8px 0 0;
	padding-left: 17px;
}
.cl-report-document-footer {
	border-top: 1px solid var(--cl-border);
	padding-top: 18px;
	margin-top: 30px;
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 18px;
}
.cl-report-document-footer p {
	font-size: 10px;
	color: var(--cl-muted);
	line-height: 1.9;
	margin: 0;
}
.cl-report-document-footer a {
	color: var(--cl-primary);
	font-size: 11px;
	white-space: nowrap;
}
.cl-report-empty {
	min-height: 430px;
	text-align: center;
	padding: 70px 30px;
}
.cl-report-empty > span {
	display: inline-grid;
	place-items: center;
	width: 65px;
	height: 65px;
	border-radius: 18px;
	background: var(--cl-primary-soft);
	color: var(--cl-primary);
	margin-bottom: 23px;
}
.cl-report-empty h2 {
	font-size: 18px;
	margin: 0 0 13px;
}
.cl-report-empty p {
	font-size: 12px;
	color: var(--cl-muted);
	line-height: 1.9;
	margin-bottom: 25px;
}
.cl-report-list-placeholder {
	height: 172px;
	border-radius: 12px;
	border: 1px solid var(--cl-border);
	background: linear-gradient(110deg, #f3f5fb, #fff);
}
.cl-report-loading > div {
	height: 48px;
	background: #f3f5fa;
	border-radius: 8px;
	margin: 18px 0;
}
.cl-report-loading > div:first-child {
	width: 60%;
	height: 28px;
}
.cl-report-loading p {
	font-size: 12px;
	color: var(--cl-muted);
}
@media (max-width: 1180px) {
	.cl-reports-layout {
		grid-template-columns: 225px minmax(0, 1fr);
	}
	.cl-report-document {
		padding: 24px;
	}
}
@media (max-width: 900px) {
	.cl-reports-layout {
		grid-template-columns: 1fr;
	}
	.cl-report-list {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
	.cl-report-list-heading,
	.cl-report-pagination,
	.cl-report-list-empty {
		grid-column: 1 / -1;
	}
	.cl-report-list-item {
		padding: 16px;
	}
}
@media (max-width: 600px) {
	.cl-report-list {
		grid-template-columns: 1fr;
	}
	.cl-report-document {
		padding: 20px 17px;
	}
	.cl-report-document > h2 {
		font-size: 21px;
	}
	.cl-report-document-top {
		flex-wrap: wrap;
	}
	.cl-report-document-footer {
		flex-direction: column;
		align-items: flex-start;
	}
	.cl-report-lead {
		padding: 14px;
		font-size: 13px;
	}
}
</style>
