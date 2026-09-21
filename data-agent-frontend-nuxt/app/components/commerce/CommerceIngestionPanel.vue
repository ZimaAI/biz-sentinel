<script setup lang="ts">
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import type { Ingestion } from '~/types/commerce';

const emit = defineEmits<{ published: [] }>();
const file = ref<File | null>(null),
	job = ref<Ingestion | null>(null);
const busy = ref(false),
	refreshing = ref(false),
	error = ref(''),
	uploadKey = ref('');
let timer: ReturnType<typeof setTimeout> | undefined;
let disposed = false,
	generation = 0,
	refreshSequence = 0;
const statusNames: Record<string, string> = {
	UPLOADED: '已接收',
	VALIDATING: '正在校验',
	READY_TO_PUBLISH: '校验完成，待发布',
	PUBLISHED: '已发布',
	REJECTED: '校验未通过',
	FAILED: '导入失败',
};
function selectFile(event: Event) {
	++generation;
	++refreshSequence;
	refreshing.value = false;
	file.value = (event.target as HTMLInputElement).files?.[0] || null;
	job.value = null;
	error.value = '';
	uploadKey.value = crypto.randomUUID();
	clearTimeout(timer);
	if (
		file.value &&
		(!file.value.name.toLowerCase().endsWith('.zip') ||
			file.value.size > 32 * 1024 * 1024)
	) {
		error.value = '请选择不超过 32 MiB 的标准 ZIP 包。';
		file.value = null;
	}
}
function schedule() {
	clearTimeout(timer);
	if (
		!disposed &&
		job.value &&
		['UPLOADED', 'VALIDATING'].includes(job.value.status)
	)
		timer = setTimeout(() => {
			void refreshJob();
		}, 1500);
}
async function refreshJob() {
	if (!job.value || disposed) return false;
	const activeGeneration = generation,
		sequence = ++refreshSequence,
		id = job.value.jobId;
	refreshing.value = true;
	clearTimeout(timer);
	const current = () =>
		!disposed &&
		generation === activeGeneration &&
		sequence === refreshSequence &&
		job.value?.jobId === id;
	try {
		const refreshed = await commerceApi.getIngestion(id);
		if (!current()) return false;
		job.value = refreshed;
		error.value = '';
		schedule();
		return true;
	} catch (cause) {
		if (current())
			error.value = cause instanceof Error ? cause.message : '无法刷新导入状态';
		return false;
	} finally {
		if (current()) refreshing.value = false;
	}
}
async function upload() {
	if (!file.value || busy.value) return;
	const activeGeneration = ++generation;
	++refreshSequence;
	clearTimeout(timer);
	refreshing.value = false;
	job.value = null;
	busy.value = true;
	error.value = '';
	const current = () => !disposed && generation === activeGeneration;
	try {
		const uploaded = await commerceApi.upload(file.value, uploadKey.value);
		if (current()) {
			job.value = uploaded;
			schedule();
		}
	} catch (cause) {
		if (current())
			error.value = cause instanceof Error ? cause.message : '上传失败，请重试';
	} finally {
		if (current()) busy.value = false;
	}
}
async function publish() {
	if (!job.value || busy.value) return;
	const activeGeneration = generation,
		activeJob = job.value;
	const current = () =>
		!disposed &&
		generation === activeGeneration &&
		job.value?.jobId === activeJob.jobId;
	++refreshSequence;
	clearTimeout(timer);
	refreshing.value = false;
	busy.value = true;
	error.value = '';
	try {
		const dataset = await commerceApi.publishIngestion(
			activeJob.jobId,
			activeJob.version,
		);
		if (!current()) return;
		job.value = {
			...activeJob,
			status: 'PUBLISHED',
			version: activeJob.version + 1,
			quality: dataset.quality,
		};
		emit('published');
		await refreshJob();
	} catch (cause) {
		if (!current()) return;
		if (cause instanceof CommerceApiError && cause.status === 409) {
			const refreshed = await refreshJob();
			if (current() && refreshed)
				error.value = '任务版本或数据版本已变化。已刷新状态，请核对后再发布。';
		} else
			error.value =
				cause instanceof Error ? cause.message : '发布未完成，请重试';
	} finally {
		if (current()) busy.value = false;
	}
}
onBeforeUnmount(() => {
	disposed = true;
	++generation;
	++refreshSequence;
	clearTimeout(timer);
});
</script>

<template>
	<section
		class="cl-card cl-ingestion-panel"
		aria-labelledby="cl-ingestion-title"
	>
		<header>
			<div>
				<h2 id="cl-ingestion-title">导入与校验</h2>
				<p>上传标准数据包，校验通过后手动发布新的不可变快照。</p>
			</div>
			<span class="cl-badge">仅管理员</span>
		</header>
		<div class="cl-upload-area">
			<div class="cl-upload-icon"><CommerceIcon name="upload" /></div>
			<div>
				<strong>标准 CSV 数据包</strong>
				<p>ZIP 根目录包含 manifest.json 和 8 个命名 CSV，最大 32 MiB。</p>
				<input
					type="file"
					accept=".zip,application/zip"
					aria-label="选择标准 CSV ZIP 包"
					:disabled="busy"
					@change="selectFile"
				/>
			</div>
			<button
				class="cl-button primary"
				:disabled="!file || busy"
				@click="upload"
			>
				{{ busy && !job ? '上传与校验中…' : '上传并校验' }}
			</button>
		</div>
		<div v-if="error" class="cl-banner danger" role="alert">{{ error }}</div>
		<div v-if="job" class="cl-ingestion-state" aria-live="polite">
			<div class="cl-ingestion-state-title">
				<span
					class="cl-badge"
					:class="
						job.status === 'PUBLISHED'
							? 'success'
							: ['REJECTED', 'FAILED'].includes(job.status)
								? 'danger'
								: 'warning'
					"
					>{{ statusNames[job.status] || job.status }}</span
				><span>{{ job.datasetVersionId || '数据版本待校验' }}</span
				><button
					class="cl-button"
					:disabled="busy || refreshing"
					@click="refreshJob"
				>
					<CommerceIcon name="refresh" />{{
						refreshing ? '正在刷新…' : '刷新状态'
					}}
				</button>
			</div>
			<p>
				已校验 {{ job.filesValidated }} / 8 个文件 · 任务 {{ job.jobId }} · v{{
					job.version
				}}
			</p>
			<div v-if="job.quality?.status === 'PARTIAL'" class="cl-banner warning">
				部分来源或业务日不完整。发布后受影响指标为空值，相应经营监控暂停。
			</div>
			<div v-if="job.errors.length" class="cl-import-errors">
				<table class="cl-table">
					<thead>
						<tr>
							<th>文件 / 行</th>
							<th>校验结果</th>
						</tr>
					</thead>
					<tbody>
						<tr v-for="(item, index) in job.errors" :key="index">
							<td>
								{{ item.file
								}}<span v-if="item.line"> · 第 {{ item.line }} 行</span>
							</td>
							<td>
								<strong>{{ item.message }}</strong
								><small>{{ item.code }}</small>
							</td>
						</tr>
					</tbody>
				</table>
			</div>
			<div v-if="job.status === 'READY_TO_PUBLISH'" class="cl-publish-action">
				<p>发布后，该版本的经营数据不可修改。修订请创建新的数据版本。</p>
				<button class="cl-button primary" :disabled="busy" @click="publish">
					{{ busy ? '正在发布…' : '发布此数据版本' }}
				</button>
			</div>
			<p v-if="job.status === 'PUBLISHED'" class="cl-published-note">
				<CommerceIcon name="check" />快照已发布，可在数据版本列表中查看。
			</p>
		</div>
		<details class="cl-import-spec">
			<summary>查看标准文件与发布校验</summary>
			<p>
				stores.csv · products.csv · orders.csv · order_items.csv · payments.csv
				· refunds.csv · traffic_daily.csv · inventory_daily.csv
			</p>
			<p>
				manifest.json 声明 schemaVersion、当前租户、独立 datasetVersionId、CNY
				币种、Asia/Shanghai 时区、覆盖范围、完整业务日、来源水位，以及每个 CSV
				的行数和 SHA-256。CSV 使用 UTF-8 和固定表头。
			</p>
			<p>
				发布前验证成功支付唯一、订单项金额分摊、累计退款上限、事件时间、店铺引用和每日来源覆盖。
			</p>
		</details>
	</section>
</template>

<style scoped>
.cl-ingestion-panel {
	padding: 24px;
}
.cl-ingestion-panel header {
	display: flex;
	justify-content: space-between;
	gap: 16px;
	align-items: flex-start;
}
.cl-ingestion-panel h2 {
	font-size: 17px;
	margin: 0;
}
.cl-ingestion-panel header p {
	font-size: 12px;
	color: var(--text-secondary);
	margin: 7px 0 0;
}
.cl-upload-area {
	display: flex;
	gap: 16px;
	align-items: center;
	padding: 22px;
	border: 1px dashed #c8cef1;
	background: var(--surface-soft);
	border-radius: 12px;
	margin: 22px 0;
}
.cl-upload-icon {
	width: 46px;
	height: 46px;
	display: grid;
	place-items: center;
	background: var(--primary-soft);
	border-radius: 12px;
	color: var(--primary);
}
.cl-upload-area > div:nth-child(2) {
	flex: 1;
	min-width: 0;
}
.cl-upload-area strong {
	font-size: 14px;
}
.cl-upload-area p {
	font-size: 12px;
	color: var(--text-muted);
	margin: 6px 0 12px;
}
.cl-upload-area input {
	font-size: 12px;
	width: 100%;
}
.cl-upload-area input::file-selector-button {
	border: 1px solid var(--border);
	padding: 7px 10px;
	border-radius: 6px;
	background: var(--surface);
	color: var(--text-secondary);
	margin-right: 10px;
	cursor: pointer;
}
.cl-ingestion-state {
	border: 1px solid var(--border);
	border-radius: 10px;
	padding: 18px;
	margin-top: 16px;
}
.cl-ingestion-state-title {
	display: flex;
	gap: 12px;
	align-items: center;
	flex-wrap: wrap;
	font-size: 13px;
}
.cl-ingestion-state-title button {
	margin-left: auto;
	height: 32px;
	min-height: 32px;
	font-size: 12px;
}
.cl-ingestion-state > p {
	font-size: 12px;
	color: var(--text-muted);
	overflow-wrap: anywhere;
	margin: 12px 0;
}
.cl-import-errors {
	overflow: auto;
	margin: 16px 0;
}
.cl-import-errors strong {
	font-size: 12px;
	font-weight: 500;
}
.cl-import-errors small {
	display: block;
	color: var(--text-muted);
	margin-top: 4px;
}
.cl-publish-action {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 14px;
	margin-top: 16px;
}
.cl-publish-action p {
	font-size: 12px;
	color: var(--text-secondary);
	line-height: 1.7;
}
.cl-published-note {
	display: flex;
	align-items: center;
	gap: 8px;
	color: var(--success) !important;
}
.cl-import-spec {
	font-size: 12px;
	line-height: 1.8;
	color: var(--text-secondary);
	margin-top: 18px;
}
.cl-import-spec summary {
	color: var(--primary);
	cursor: pointer;
}
.cl-import-spec p {
	overflow-wrap: anywhere;
}
@media (max-width: 700px) {
	.cl-upload-area {
		flex-wrap: wrap;
		padding: 16px;
	}
	.cl-upload-area > button {
		width: 100%;
		justify-content: center;
	}
	.cl-ingestion-panel {
		padding: 18px;
	}
	.cl-publish-action {
		flex-wrap: wrap;
	}
	.cl-publish-action > button {
		width: 100%;
		justify-content: center;
	}
}
</style>
