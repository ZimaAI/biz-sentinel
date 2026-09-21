<script setup lang="ts">
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import type { AnomalyRule, Metric, RuleDefinition } from '~/types/commerce';

const props = defineProps<{
	open: boolean;
	rule: AnomalyRule | null;
	metrics: Metric[];
}>();
const emit = defineEmits<{
	close: [];
	saved: [rule: AnomalyRule];
	conflict: [];
}>();
const { me, stores, selectedStoreIds } = useCommerceContext();
const dialog = ref<HTMLDialogElement | null>(null);
const saving = ref(false),
	error = ref(''),
	conflicted = ref(false);
const identity = computed(() =>
	JSON.stringify([
		me.value?.tenantId,
		me.value?.subjectId,
		me.value?.authzVersion,
	]),
);
let generation = 0,
	disposed = false;
const form = reactive({
	name: '',
	metricId: 'paid_gmv',
	storeIds: [] as string[],
	direction: 'DOWN' as 'UP' | 'DOWN',
	absolute: '10000',
	relative: '15',
	useRelative: true,
	cooldownHours: 24,
	enabled: true,
});
const available = computed(() =>
	props.metrics.filter((metric) =>
		[
			'paid_gmv',
			'paid_orders',
			'refund_amount',
			'order_conversion_rate',
		].includes(metric.metricId),
	),
);
const unit = computed(() =>
	form.metricId === 'order_conversion_rate'
		? 'PP'
		: form.metricId === 'paid_orders'
			? 'COUNT'
			: 'CNY_CENT',
);
const unitLabel = computed(() =>
	unit.value === 'CNY_CENT' ? '元' : unit.value === 'PP' ? '个百分点 pp' : '单',
);
function shift(value: string, places: number) {
	const [whole = '0', fraction = ''] = value.split('.');
	const digits = whole + fraction,
		position = whole.length + places;
	const output =
		position <= 0
			? `0.${'0'.repeat(-position)}${digits}`
			: position >= digits.length
				? digits + '0'.repeat(position - digits.length)
				: `${digits.slice(0, position)}.${digits.slice(position)}`;
	return output
		.replace(/^0+(?=\d)/, '')
		.replace(/(\.\d*?)0+$/, '$1')
		.replace(/\.$/, '');
}
function initialize() {
	const definition = props.rule?.definition;
	Object.assign(form, {
		name: definition?.name || '',
		metricId: definition?.metricId || 'paid_gmv',
		storeIds: [...(definition?.storeIds || selectedStoreIds.value)],
		direction: definition?.direction || 'DOWN',
		absolute: definition
			? definition.unit === 'CNY_CENT'
				? shift(definition.absoluteThreshold, -2)
				: definition.absoluteThreshold
			: '10000',
		relative: definition?.relativeThreshold
			? shift(definition.relativeThreshold, 2)
			: '15',
		useRelative: definition ? definition.relativeThreshold !== null : true,
		cooldownHours: definition?.cooldownHours || 24,
		enabled: definition?.enabled ?? true,
	});
	error.value = '';
	conflicted.value = false;
}
watch(
	() => props.open,
	async (open) => {
		const activeGeneration = ++generation;
		saving.value = false;
		if (open) {
			initialize();
			await nextTick();
			if (
				!disposed &&
				activeGeneration === generation &&
				props.open &&
				!dialog.value?.open
			)
				dialog.value?.showModal();
		} else dialog.value?.close();
	},
);
function changeMetric() {
	form.direction = form.metricId === 'refund_amount' ? 'UP' : 'DOWN';
	form.absolute =
		unit.value === 'PP'
			? '0.5'
			: unit.value === 'COUNT'
				? '30'
				: form.metricId === 'refund_amount'
					? '5000'
					: '10000';
	form.useRelative = unit.value !== 'PP';
	form.relative = form.metricId === 'refund_amount' ? '20' : '15';
}
async function save() {
	if (
		saving.value ||
		conflicted.value ||
		!me.value?.roles.includes('TENANT_ADMIN')
	)
		return;
	error.value = '';
	const precision =
		unit.value === 'COUNT'
			? /^\d+$/
			: unit.value === 'CNY_CENT'
				? /^\d+(\.\d{1,2})?$/
				: /^\d+(\.\d{1,4})?$/;
	if (
		!form.name.trim() ||
		!form.storeIds.length ||
		!precision.test(form.absolute) ||
		(form.useRelative && !/^\d+(\.\d{1,4})?$/.test(form.relative))
	) {
		error.value =
			'请填写规则名称、至少一家店铺，以及有效的非负阈值。金额最多保留两位小数。';
		return;
	}
	if (
		!Number.isInteger(form.cooldownHours) ||
		form.cooldownHours < 1 ||
		form.cooldownHours > 168
	) {
		error.value = '冷却时间须为 1–168 小时的整数。';
		return;
	}
	const definition: RuleDefinition = {
		name: form.name.trim(),
		metricId: form.metricId,
		storeIds: [...form.storeIds],
		direction: form.direction,
		baseline: 'SAME_WEEKDAY_MEDIAN_8',
		absoluteThreshold:
			unit.value === 'CNY_CENT' ? shift(form.absolute, 2) : form.absolute,
		relativeThreshold: form.useRelative ? shift(form.relative, -2) : null,
		unit: unit.value,
		cooldownHours: form.cooldownHours,
		enabled: form.enabled,
	};
	saving.value = true;
	const activeGeneration = generation,
		activeIdentity = identity.value;
	const current = () =>
		!disposed &&
		activeGeneration === generation &&
		activeIdentity === identity.value;
	try {
		const saved = props.rule
			? await commerceApi.updateRule(props.rule.ruleId, {
					expectedVersion: props.rule.version,
					definition,
				})
			: await commerceApi.createRule(definition);
		if (disposed || activeIdentity !== identity.value) return;
		emit('saved', saved);
		if (current()) emit('close');
	} catch (cause) {
		if (!current()) return;
		if (cause instanceof CommerceApiError && cause.status === 409) {
			conflicted.value = true;
			error.value =
				'此规则已在其他页面更新。请关闭后重新打开最新规则，避免覆盖新版本。';
			emit('conflict');
		} else
			error.value =
				cause instanceof Error ? cause.message : '规则保存失败，请重试';
	} finally {
		if (current()) saving.value = false;
	}
}
onBeforeUnmount(() => {
	disposed = true;
	++generation;
	dialog.value?.close();
});
</script>

<template>
	<dialog
		ref="dialog"
		class="cl-rule-dialog"
		aria-labelledby="cl-rule-title"
		@cancel="emit('close')"
		@click="
			(event) => {
				if (event.target === dialog) emit('close');
			}
		"
	>
		<form @submit.prevent="save">
			<header>
				<div>
					<h2 id="cl-rule-title">
						{{ rule ? '编辑监控规则' : '新建监控规则' }}
					</h2>
					<p>
						日级观察 · 已发布指标 ·
						{{ rule ? `当前版本 v${rule.version}` : '完整数据优先' }}
					</p>
				</div>
				<button
					type="button"
					class="cl-button cl-close-rule"
					aria-label="关闭规则弹窗"
					@click="emit('close')"
				>
					<CommerceIcon name="close" />
				</button>
			</header>
			<div class="cl-rule-body">
				<div v-if="error" class="cl-banner danger" role="alert">
					{{ error }}
				</div>
				<label class="cl-field"
					>规则名称<input
						v-model="form.name"
						maxlength="100"
						placeholder="例如：主店支付 GMV 下跌"
						required
				/></label>
				<div class="cl-rule-columns">
					<label class="cl-field"
						>监控指标<select v-model="form.metricId" @change="changeMetric">
							<option
								v-for="metric in available"
								:key="metric.metricId"
								:value="metric.metricId"
							>
								{{ metric.displayName }} · v{{ metric.version }}
							</option>
						</select></label
					><label class="cl-field"
						>偏离方向<select v-model="form.direction">
							<option value="DOWN">下降</option>
							<option value="UP">上升</option>
						</select></label
					>
				</div>
				<fieldset class="cl-rule-stores">
					<legend>监控店铺 <span>仅当前授权范围</span></legend>
					<label v-for="store in stores" :key="store.storeId"
						><input
							v-model="form.storeIds"
							type="checkbox"
							:value="store.storeId"
						/>{{ store.name }}</label
					>
				</fieldset>
				<div class="cl-rule-reference">
					<CommerceIcon name="calendar" />
					<div>
						<strong>最近 8 个同星期完整日的中位数</strong>
						<p>
							至少 4 个有效参考日；来源缺失时暂停经营告警，显示数据质量状态。
						</p>
					</div>
				</div>
				<div class="cl-rule-columns">
					<label class="cl-field"
						>绝对变化阈值（{{ unitLabel }}）<input
							v-model="form.absolute"
							inputmode="decimal"
							required /></label
					><label class="cl-field"
						>冷却时间（小时）<input
							v-model.number="form.cooldownHours"
							type="number"
							min="1"
							max="168"
							required
					/></label>
				</div>
				<label class="cl-rule-check"
					><input
						v-model="form.useRelative"
						type="checkbox"
					/>同时要求达到相对变化阈值</label
				>
				<label v-if="form.useRelative" class="cl-field"
					>相对变化阈值（%）<input
						v-model="form.relative"
						inputmode="decimal"
						required
					/><small>绝对阈值与相对阈值同时满足时触发。</small></label
				>
				<p v-if="unit === 'PP'" class="cl-rule-help">
					转化比使用百分点差值；当前日及参考日需至少 30 笔支付订单、1,000
					次会话。
				</p>
				<label class="cl-rule-check"
					><input v-model="form.enabled" type="checkbox" />启用此规则</label
				>
			</div>
			<footer>
				<button type="button" class="cl-button" @click="emit('close')">
					取消</button
				><button
					type="submit"
					class="cl-button primary"
					:disabled="
						saving || conflicted || !me?.roles.includes('TENANT_ADMIN')
					"
				>
					{{ saving ? '正在保存…' : '保存规则' }}
				</button>
			</footer>
		</form>
	</dialog>
</template>

<style scoped>
.cl-rule-dialog {
	width: 560px;
	max-width: calc(100vw - 32px);
	max-height: 90vh;
	padding: 0;
	border: 1px solid var(--border);
	border-radius: 16px;
	background: var(--surface);
	color: var(--text);
	margin: auto;
	box-shadow: 0 24px 80px #18243d26;
}
.cl-rule-dialog::backdrop {
	background: rgba(19, 30, 54, 0.25);
}
.cl-rule-dialog form {
	display: flex;
	flex-direction: column;
	max-height: 90vh;
}
.cl-rule-dialog header {
	padding: 22px 24px 18px;
	display: flex;
	justify-content: space-between;
	gap: 16px;
	border-bottom: 1px solid var(--border);
}
.cl-rule-dialog h2 {
	font-size: 18px;
	margin: 0;
}
.cl-rule-dialog header p {
	font-size: 12px;
	color: var(--text-muted);
	margin: 6px 0 0;
}
.cl-close-rule {
	padding: 8px;
	min-width: 36px;
}
.cl-rule-body {
	padding: 22px 24px;
	overflow: auto;
	display: grid;
	gap: 18px;
}
.cl-rule-columns {
	display: grid;
	grid-template-columns: 1fr 1fr;
	gap: 16px;
}
.cl-rule-stores {
	border: 1px solid var(--border);
	border-radius: 10px;
	padding: 12px 14px;
}
.cl-rule-stores legend {
	font-size: 12px;
	padding: 0 6px;
}
.cl-rule-stores legend span {
	color: var(--text-muted);
	margin-left: 8px;
}
.cl-rule-stores label {
	display: flex;
	gap: 9px;
	align-items: center;
	font-size: 13px;
	padding: 6px 0;
}
.cl-rule-dialog input[type='checkbox'] {
	width: 15px;
	height: 15px;
	accent-color: var(--primary);
}
.cl-rule-reference {
	display: flex;
	gap: 10px;
	background: var(--surface-soft);
	border-radius: 10px;
	padding: 14px;
	font-size: 12px;
	color: var(--text-secondary);
}
.cl-rule-reference p {
	margin: 5px 0 0;
	line-height: 1.7;
}
.cl-rule-check {
	display: flex;
	gap: 9px;
	align-items: center;
	font-size: 13px;
}
.cl-rule-help,
.cl-field small {
	font-size: 12px;
	line-height: 1.7;
	color: var(--text-secondary);
	margin: 0;
}
.cl-rule-dialog footer {
	display: flex;
	justify-content: flex-end;
	gap: 10px;
	padding: 16px 24px;
	border-top: 1px solid var(--border);
	background: var(--surface);
}
@media (max-width: 500px) {
	.cl-rule-columns {
		grid-template-columns: 1fr;
	}
	.cl-rule-body {
		padding: 18px;
	}
	.cl-rule-dialog header {
		padding: 18px;
	}
}
</style>
