<script setup lang="ts">
import { commerceApi } from '~/services/commerceApi';
import type { Dataset, GuestAccess, Member } from '~/types/commerce';
import { dateRangeLabel, formatDecimal } from '~/utils/commerceFormat';

definePageMeta({ layout: 'commerce' });
const {
	me,
	ready,
	stores,
	dataset: contextDataset,
	refresh,
} = useCommerceContext();
const datasets = ref<Dataset[]>([]),
	members = ref<Member[]>([]),
	guestAccess = ref<GuestAccess | null>(null),
	selectedId = ref('');
const loading = ref(true),
	error = ref(''),
	memberError = ref(''),
	guestError = ref(''),
	feedback = ref(''),
	showImport = ref(false);
const membersLoading = ref(false);
const memberOpen = ref(false),
	editingMember = ref<Member | null>(null),
	activeTab = ref('data');
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
	memberSequence = 0,
	disposed = false;
const isAdmin = computed(
	() => me.value?.roles.includes('TENANT_ADMIN') ?? false,
);
const selected = computed(
	() =>
		datasets.value.find((item) => item.datasetVersionId === selectedId.value) ||
		datasets.value[0],
);
const roles: Record<string, string> = {
	TENANT_ADMIN: '租户管理员',
	OPS_MANAGER: '运营经理',
	STORE_OPERATOR: '店铺运营',
	VIEWER: '只读观察者',
};
const tableInfo: Record<string, { name: string; purpose: string }> = {
	stores: { name: '店铺', purpose: '店铺维度与业务时区' },
	products: { name: '商品', purpose: '商品维度与 SKU' },
	orders: { name: '订单', purpose: '订单与商品实付核验' },
	order_items: { name: '订单明细', purpose: 'SKU 实付金额分摊' },
	payments: { name: '成功支付', purpose: '按支付成功时间聚合' },
	refunds: { name: '成功退款', purpose: '按退款成功时间聚合' },
	traffic_daily: { name: '店铺日流量', purpose: '会话数，非跨店去重人数' },
	inventory_daily: { name: '商品日库存', purpose: '库存与缺货时长线索' },
};
function timestamp(value?: string) {
	if (!value) return '未提供';
	const date = new Date(value);
	return Number.isNaN(date.getTime())
		? value
		: date.toLocaleString('zh-CN', {
				timeZone: 'Asia/Shanghai',
				hour12: false,
			});
}
const storeLabel = (ids: string[]) =>
	ids
		.map((id) => stores.value.find((store) => store.storeId === id)?.name || id)
		.join('、') || '未授予店铺';
function tableStatus(name: string) {
	return selected.value?.quality.sourceStatus?.[name] || 'UNKNOWN';
}
async function loadMembers() {
	const sequence = ++memberSequence;
	if (!isAdmin.value) {
		members.value = [];
		membersLoading.value = false;
		return;
	}
	memberError.value = '';
	membersLoading.value = true;
	try {
		const response = await commerceApi.listMembers();
		if (sequence === memberSequence && isAdmin.value)
			members.value = response.items;
	} catch (cause) {
		if (sequence === memberSequence) {
			members.value = [];
			memberError.value =
				cause instanceof Error ? cause.message : '成员列表暂时不可用';
		}
	} finally {
		if (sequence === memberSequence) membersLoading.value = false;
	}
}
async function loadGuestAccess() {
	if (!isAdmin.value) {
		guestAccess.value = null;
		return;
	}
	try {
		const response = await commerceApi.getGuestAccess();
		if (isAdmin.value) {
			guestAccess.value = response.config;
			if (response.members.length) members.value = response.members;
		}
	} catch (cause) {
		guestAccess.value = null;
		guestError.value = cause instanceof Error ? cause.message : '游客范围暂时不可用';
	}
}
async function load() {
	const sequence = ++loadSequence;
	loading.value = true;
	error.value = '';
	try {
		const response = await commerceApi.listDatasets({
			limit: 20,
			cursor: cursor.value,
		});
		if (sequence !== loadSequence) return;
		datasets.value = response.items;
		nextCursor.value = response.nextCursor;
		if (
			!response.items.some((item) => item.datasetVersionId === selectedId.value)
		)
			selectedId.value =
				response.items.find(
					(item) =>
						item.datasetVersionId === contextDataset.value?.datasetVersionId,
				)?.datasetVersionId ||
				response.items[0]?.datasetVersionId ||
				'';
	} catch (cause) {
		if (sequence === loadSequence) {
			datasets.value = [];
			nextCursor.value = null;
			error.value =
				cause instanceof Error ? cause.message : '数据版本暂时不可用';
		}
	} finally {
		if (sequence === loadSequence) loading.value = false;
	}
}
function editMember(member: Member | null) {
	editingMember.value = member;
	memberOpen.value = true;
}
async function memberSaved() {
	feedback.value = '成员授权已更新。后续查询和历史资源读取会使用最新授权。';
	try {
		await refresh();
		if (!disposed) await loadMembers();
	} catch (cause) {
		if (!disposed) {
			members.value = [];
			memberError.value =
				cause instanceof Error ? cause.message : '授权已保存，刷新成员列表失败';
		}
	}
}
async function guestSaved() {
	feedback.value = '游客演示范围已更新，新的游客会话将按最新授权浏览。';
	guestError.value = '';
	await loadGuestAccess();
}
async function published() {
	feedback.value = '新的数据快照已发布。';
	cursor.value = undefined;
	previous.value = [];
	selectedId.value = '';
	try {
		await refresh();
		if (!disposed) await load();
	} catch (cause) {
		if (!disposed)
			error.value =
				cause instanceof Error
					? cause.message
					: '数据已发布，刷新版本列表失败，请重试';
	}
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
		++memberSequence;
		datasets.value = [];
		members.value = [];
		selectedId.value = '';
		membersLoading.value = false;
		cursor.value = undefined;
		nextCursor.value = null;
		previous.value = [];
		feedback.value = '';
		memberError.value = '';
		guestError.value = '';
		guestAccess.value = null;
		memberOpen.value = false;
		editingMember.value = null;
		showImport.value = false;
		if (value) {
			void load();
			void loadMembers();
			void loadGuestAccess();
		}
	},
	{ immediate: true },
);
watch(isAdmin, (value) => {
	if (!value) {
		++memberSequence;
		membersLoading.value = false;
		activeTab.value = 'data';
		showImport.value = false;
		memberOpen.value = false;
		members.value = [];
		guestAccess.value = null;
	}
});
onBeforeUnmount(() => {
	disposed = true;
	++loadSequence;
	++memberSequence;
});
</script>

<template>
	<div class="cl-data-page">
		<div class="cl-page-heading">
			<div>
				<h1>数据中心</h1>
				<p>不可变数据快照：来源可追溯，历史报告可复算。</p>
			</div>
			<button
				class="cl-button primary"
				:disabled="!isAdmin"
				:title="isAdmin ? '上传标准 CSV ZIP 包' : '数据导入需要租户管理员权限'"
				@click="
					showImport = !showImport;
					activeTab = 'data';
				"
			>
				<CommerceIcon :name="showImport ? 'close' : 'upload'" />{{
					showImport ? '收起导入' : '导入与校验'
				}}
			</button>
		</div>
		<div
			v-if="feedback"
			class="cl-banner success cl-data-feedback"
			role="status"
		>
			<CommerceIcon name="check" />{{ feedback
			}}<button aria-label="关闭提示" @click="feedback = ''">
				<CommerceIcon name="close" />
			</button>
		</div>
		<div
			v-if="isAdmin"
			class="cl-data-tabs"
			role="group"
			aria-label="数据与授权管理"
		>
			<button
				:aria-pressed="activeTab === 'data'"
				:class="{ selected: activeTab === 'data' }"
				@click="activeTab = 'data'"
			>
				数据快照</button
			><button
				:aria-pressed="activeTab === 'members'"
				:class="{ selected: activeTab === 'members' }"
				@click="activeTab = 'members'"
			>
				成员与授权
			</button>
		</div>
		<template v-if="activeTab === 'data'">
			<CommerceIngestionPanel
				v-if="showImport && isAdmin"
				class="cl-data-import"
				@published="published"
			/>
			<div v-if="error" class="cl-banner danger cl-data-feedback" role="alert">
				{{ error }}<button class="cl-button" @click="load">重试</button>
			</div>
			<div
				v-if="!loading && selected?.synthetic"
				class="cl-banner cl-data-source"
			>
				<CommerceIcon name="database" /><span
					>当前为合成数据快照，用于验证经营口径与产品流程，未连接真实电商平台。</span
				><span class="cl-badge">合成数据</span>
			</div>
			<div v-if="loading" class="cl-data-stats" aria-busy="true">
				<div v-for="index in 3" :key="index" class="cl-card cl-data-loading">
					<div />
					<div />
				</div>
			</div>
			<div v-else-if="!selected" class="cl-card cl-empty">
				<CommerceIcon name="database" />
				<h2>还没有可见的数据快照</h2>
				<p>
					{{
						isAdmin
							? '导入标准数据包并完成校验后，发布第一个版本。'
							: '请联系管理员发布数据并授予你相应店铺权限。'
					}}
				</p>
				<button
					v-if="isAdmin"
					class="cl-button primary"
					@click="showImport = true"
				>
					导入标准数据包
				</button>
			</div>
			<template v-else>
				<div class="cl-data-stats">
					<div class="cl-card">
						<span>快照内业务表</span
						><strong>{{ selected.tables.length }}<small> / 8</small></strong>
					</div>
					<div class="cl-card">
						<span>声明完整业务日</span
						><strong
							>{{ selected.coverage.completeBusinessDates?.length ?? '—'
							}}<small> 天</small></strong
						>
					</div>
					<div class="cl-card">
						<span>统一币种 / 业务时区</span
						><strong class="cl-data-currency"
							>CNY <small>/ Asia/Shanghai</small></strong
						>
					</div>
				</div>
				<section class="cl-card cl-dataset-card">
					<header>
						<div>
							<h2>规范业务数据集</h2>
							<p>
								{{ dateRangeLabel(selected.coverage) }} ·
								{{ storeLabel(selected.scope.storeIds) }}
							</p>
						</div>
						<label class="cl-dataset-select"
							><span class="cl-sr-only">选择数据版本</span
							><select v-model="selectedId">
								<option
									v-for="item in datasets"
									:key="item.datasetVersionId"
									:value="item.datasetVersionId"
								>
									{{ item.datasetVersionId
									}}{{ item.status === 'RETIRED' ? '（已退役）' : '' }}
								</option>
							</select></label
						>
					</header>
					<div class="cl-data-watermark">
						<div>
							<span>业务覆盖到</span
							><strong>{{
								timestamp(selected.quality.businessWatermark)
							}}</strong>
						</div>
						<div>
							<span>导入发布于</span
							><strong>{{ timestamp(selected.quality.publishedAt) }}</strong>
						</div>
						<span
							class="cl-badge"
							:class="
								selected.status !== 'RETIRED' &&
								selected.quality.status === 'COMPLETE'
									? 'success'
									: 'warning'
							"
							>{{
								selected.status === 'RETIRED'
									? '已退役'
									: selected.quality.status === 'COMPLETE'
										? '数据完整'
										: selected.quality.status === 'STALE'
											? '来源水位滞后'
											: '存在不完整来源'
							}}</span
						>
					</div>
					<div
						v-if="selected.quality.warnings.length"
						class="cl-banner warning cl-data-quality-warning"
					>
						<p v-for="warning in selected.quality.warnings" :key="warning">
							{{ warning }}
						</p>
					</div>
					<div class="cl-data-table-scroll">
						<table class="cl-table">
							<thead>
								<tr>
									<th>数据表</th>
									<th>规范文件</th>
									<th class="cl-data-number">快照记录数</th>
									<th>来源状态</th>
									<th>用途</th>
								</tr>
							</thead>
							<tbody>
								<tr v-for="table in selected.tables" :key="table.name">
									<td>
										<strong>{{
											tableInfo[table.name]?.name || table.name
										}}</strong>
									</td>
									<td class="cl-data-file">{{ table.name }}.csv</td>
									<td class="cl-data-number">
										{{ formatDecimal(String(table.rowCount), 'COUNT') }}
									</td>
									<td>
										<span
											class="cl-badge"
											:class="
												tableStatus(table.name) === 'COMPLETE'
													? 'success'
													: 'warning'
											"
											>{{
												tableStatus(table.name) === 'COMPLETE'
													? '完整'
													: tableStatus(table.name) === 'UNKNOWN'
														? '未提供'
														: '不完整'
											}}</span
										>
									</td>
									<td class="cl-data-purpose">
										{{ tableInfo[table.name]?.purpose || '规范业务事实' }}
									</td>
								</tr>
							</tbody>
						</table>
					</div>
					<footer>
						记录数对应当前全部授权店铺的快照范围。业务水位与导入时间分别展示；较新的导入时间不代表较新的业务数据。
					</footer>
				</section>
				<nav
					v-if="previous.length || nextCursor"
					class="cl-data-pagination"
					aria-label="数据集分页"
				>
					<button
						class="cl-button"
						:disabled="!previous.length || loading"
						@click="previousPage"
					>
						上一页</button
					><span>每页最多 20 个可见版本</span
					><button
						class="cl-button"
						:disabled="!nextCursor || loading"
						@click="nextPage"
					>
						下一页
					</button>
				</nav>
				<div class="cl-data-explanations">
					<section class="cl-card">
						<h2>发布前完成的校验</h2>
						<div class="cl-data-checks">
							<span>清单与校验和</span><span>租户 / 店铺隔离</span
							><span>唯一键与引用</span><span>实付与明细对账</span
							><span>累计退款上限</span><span>业务日与来源覆盖</span>
						</div>
						<p>
							成功支付与成功退款按各自发生时间统计。缺失数据不会补成零；发布后新增或更正数据须创建新的快照版本。
						</p>
					</section>
					<section class="cl-card">
						<h2>标准 CSV 与真实来源</h2>
						<p>
							当前接入方式为标准 CSV
							包。真实平台接口需要对应商家授权与来源能力；平台接入后仍遵守相同指标口径和质量检查。
						</p>
						<span class="cl-badge">只读经营分析</span>
					</section>
				</div>
			</template>
		</template>
		<div v-if="activeTab === 'members' && isAdmin && guestError" class="cl-banner danger" role="alert">
			{{ guestError }}
		</div>
		<CommerceGuestAccessPanel
			v-if="activeTab === 'members' && isAdmin && guestAccess"
			:access="guestAccess"
			:members="members"
			:stores="stores"
			@saved="guestSaved"
			@conflict="loadGuestAccess"
		/>
		<section
			v-if="activeTab === 'members' && isAdmin"
			class="cl-card cl-members-card"
		>
			<header>
				<div>
					<h2>成员与店铺授权</h2>
					<p>角色决定可执行的操作，店铺授权决定可访问的数据范围。</p>
				</div>
				<button class="cl-button primary" @click="editMember(null)">
					<CommerceIcon name="plus" />添加成员
				</button>
			</header>
			<div v-if="memberError" class="cl-banner danger" role="alert">
				{{ memberError
				}}<button class="cl-button" @click="loadMembers">重试</button>
			</div>
			<div
				v-if="membersLoading"
				class="cl-empty"
				role="status"
				aria-busy="true"
			>
				<p>正在加载成员与授权…</p>
			</div>
			<div v-else-if="!members.length && !memberError" class="cl-empty">
				<p>暂无可见成员。</p>
			</div>
			<div v-else-if="members.length" class="cl-data-table-scroll">
				<table class="cl-table">
					<thead>
						<tr>
							<th>成员</th>
							<th>角色</th>
							<th>授权店铺</th>
							<th>状态 / 版本</th>
							<th>操作</th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="member in members" :key="member.subjectId">
							<td>
								<strong>{{ member.displayName }}</strong
								><small
									>{{ member.subjectId
									}}{{
										member.subjectId === me?.subjectId ? ' · 当前账号' : ''
									}}</small
								>
							</td>
							<td>
								{{ member.roles.map((role) => roles[role] || role).join('、') }}
							</td>
							<td>{{ storeLabel(member.storeIds) }}</td>
							<td>
								<span
									class="cl-badge"
									:class="member.enabled ? 'success' : ''"
									>{{ member.enabled ? '启用' : '停用' }}</span
								><small>授权 v{{ member.authzVersion }}</small>
							</td>
							<td>
								<button class="cl-button soft" @click="editMember(member)">
									编辑授权
								</button>
							</td>
						</tr>
					</tbody>
				</table>
			</div>
		</section>
		<CommerceMemberDialog
			:open="memberOpen"
			:member="editingMember"
			@close="memberOpen = false"
			@saved="memberSaved"
			@conflict="loadMembers"
		/>
	</div>
</template>

<style scoped>
.cl-data-tabs {
	display: flex;
	gap: 4px;
	border-bottom: 1px solid var(--border);
	margin-bottom: 22px;
}
.cl-data-tabs button {
	padding: 12px 18px;
	font-size: 13px;
	color: var(--text-secondary);
	border-bottom: 2px solid transparent;
	cursor: pointer;
}
.cl-data-tabs button.selected {
	color: var(--primary);
	border-color: var(--primary);
	font-weight: 600;
}
.cl-data-feedback {
	display: flex;
	gap: 10px;
	align-items: center;
	margin-bottom: 18px;
}
.cl-data-feedback > button:last-child {
	margin-left: auto;
}
.cl-data-source {
	display: flex;
	gap: 10px;
	align-items: center;
	margin-bottom: 20px;
	font-size: 12px;
}
.cl-data-source > span:first-of-type {
	flex: 1;
}
.cl-data-import {
	margin-bottom: 22px;
}
.cl-data-stats {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 16px;
	margin-bottom: 20px;
}
.cl-data-stats > .cl-card {
	padding: 22px;
}
.cl-data-stats span {
	display: block;
	font-size: 12px;
	color: var(--text-secondary);
	margin-bottom: 13px;
}
.cl-data-stats strong {
	font-size: 30px;
	font-weight: 650;
	font-variant-numeric: tabular-nums;
}
.cl-data-stats small {
	font-size: 12px;
	color: var(--text-secondary);
}
.cl-data-currency {
	font-size: 24px !important;
}
.cl-dataset-card {
	padding: 0;
	overflow: hidden;
}
.cl-dataset-card > header,
.cl-members-card > header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18px;
	padding: 22px;
}
.cl-dataset-card h2,
.cl-members-card h2,
.cl-data-explanations h2 {
	font-size: 17px;
	margin: 0;
}
.cl-dataset-card header p,
.cl-members-card header p {
	font-size: 12px;
	line-height: 1.8;
	color: var(--text-secondary);
	margin: 7px 0 0;
}
.cl-dataset-select {
	max-width: 350px;
}
.cl-dataset-select select {
	width: 100%;
	font-size: 12px;
	color: var(--text-secondary);
	border: 1px solid var(--border);
	border-radius: 8px;
	padding: 10px 30px 10px 12px;
	background: var(--surface);
}
.cl-data-watermark {
	display: flex;
	gap: 24px;
	align-items: center;
	padding: 15px 22px;
	border-top: 1px solid var(--border);
	background: var(--surface-soft);
}
.cl-data-watermark > div {
	display: flex;
	flex-direction: column;
	gap: 5px;
}
.cl-data-watermark span {
	font-size: 11px;
	color: var(--text-muted);
}
.cl-data-watermark strong {
	font-size: 12px;
	font-weight: 500;
}
.cl-data-watermark > .cl-badge {
	margin-left: auto;
}
.cl-data-table-scroll {
	overflow: auto;
}
.cl-data-table-scroll .cl-table {
	min-width: 650px;
}
.cl-data-number {
	text-align: right !important;
	font-variant-numeric: tabular-nums;
}
.cl-data-file,
.cl-data-purpose {
	color: var(--text-secondary);
	font-size: 12px;
}
.cl-dataset-card > footer {
	font-size: 11px;
	line-height: 1.8;
	color: var(--text-muted);
	padding: 14px 22px;
	border-top: 1px solid var(--border);
}
.cl-data-quality-warning {
	margin: 14px 22px;
}
.cl-data-quality-warning p {
	margin: 4px 0;
}
.cl-data-explanations {
	display: grid;
	grid-template-columns: 1.7fr 1fr;
	gap: 20px;
	margin-top: 20px;
}
.cl-data-explanations > section {
	padding: 22px;
}
.cl-data-explanations p {
	font-size: 12px;
	line-height: 1.9;
	color: var(--text-secondary);
	margin: 14px 0 0;
}
.cl-data-explanations .cl-badge {
	margin-top: 14px;
}
.cl-data-checks {
	display: flex;
	gap: 6px;
	flex-wrap: wrap;
	margin-top: 16px;
}
.cl-data-checks span {
	font-size: 11px;
	background: var(--surface-soft);
	color: var(--text-secondary);
	padding: 4px 8px;
	border-radius: 5px;
}
.cl-members-card {
	padding: 0;
	overflow: hidden;
}
.cl-members-card td small {
	display: block;
	font-size: 11px;
	line-height: 1.8;
	color: var(--text-muted);
	margin-top: 4px;
}
.cl-members-card .cl-banner {
	margin: 0 22px 20px;
}
.cl-data-pagination {
	display: flex;
	justify-content: space-between;
	align-items: center;
	gap: 12px;
	padding: 16px 0;
}
.cl-data-pagination span {
	font-size: 12px;
	color: var(--text-muted);
}
.cl-data-loading {
	height: 120px;
}
.cl-data-loading div {
	height: 15px;
	width: 60%;
	border-radius: 5px;
	background: var(--surface-soft);
	margin: 0 0 14px;
}
.cl-data-loading div:last-child {
	height: 28px;
	width: 35%;
}
.cl-sr-only {
	position: absolute;
	width: 1px;
	height: 1px;
	padding: 0;
	overflow: hidden;
	clip: rect(0, 0, 0, 0);
	white-space: nowrap;
	border: 0;
}
@media (max-width: 960px) {
	.cl-data-explanations {
		grid-template-columns: 1fr;
	}
	.cl-data-stats {
		gap: 10px;
	}
	.cl-data-stats > .cl-card {
		padding: 18px;
	}
	.cl-dataset-card > header {
		flex-wrap: wrap;
	}
	.cl-dataset-select {
		width: 100%;
		max-width: none;
	}
}
@media (max-width: 640px) {
	.cl-data-stats {
		grid-template-columns: 1fr;
	}
	.cl-data-stats > .cl-card {
		display: flex;
		justify-content: space-between;
		align-items: center;
	}
	.cl-data-stats span {
		margin: 0;
	}
	.cl-data-watermark {
		flex-wrap: wrap;
		gap: 16px;
	}
	.cl-data-watermark > .cl-badge {
		margin-left: 0;
	}
	.cl-data-source {
		align-items: flex-start;
		flex-wrap: wrap;
	}
	.cl-members-card > header {
		flex-wrap: wrap;
	}
	.cl-data-pagination span {
		display: none;
	}
}
</style>
