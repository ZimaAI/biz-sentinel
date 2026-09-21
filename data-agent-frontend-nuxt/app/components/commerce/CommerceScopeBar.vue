<script setup lang="ts">
import { previousDate } from '~/utils/commerceFormat';
const emit = defineEmits<{ refresh: [] }>();
const context = useCommerceContext(),
	open = ref(false),
	draftStores = ref<string[]>([]),
	day = ref(''),
	period = ref('1'),
	error = ref('');
function prepare() {
	draftStores.value = [...context.selectedStoreIds.value];
	day.value = context.dateRange.value.endExclusive
		? previousDate(context.dateRange.value.endExclusive)
		: '';
	period.value = context.comparison.value === 'PREVIOUS_PERIOD' ? '7' : '1';
	open.value = !open.value;
	error.value = '';
}
function apply() {
	if (!draftStores.value.length || !day.value) {
		error.value = '请选择至少一家店铺和有效日期';
		return;
	}
	const end = new Date(`${day.value}T00:00:00Z`);
	end.setUTCDate(end.getUTCDate() + 1);
	context.selectedStoreIds.value = [...draftStores.value];
	context.dateRange.value = {
		start: previousDate(end.toISOString().slice(0, 10), Number(period.value)),
		endExclusive: end.toISOString().slice(0, 10),
	};
	context.comparison.value =
		period.value === '7' ? 'PREVIOUS_PERIOD' : 'PREVIOUS_WEEK_SAME_DAYS';
	open.value = false;
}
</script>
<template>
	<div class="scope-controls">
		<button class="cl-button" @click="prepare" :aria-expanded="open">
			<CommerceIcon name="calendar" :size="15" />{{
				context.dateRange.value.endExclusive
					? previousDate(context.dateRange.value.endExclusive)
					: '选择日期'
			}}
			·
			{{
				context.comparison.value === 'PREVIOUS_PERIOD'
					? '近 7 日'
					: '完整业务日'
			}}<CommerceIcon name="chevron-down" :size="13" /></button
		><button class="cl-button" @click="prepare" :aria-expanded="open">
			<CommerceIcon name="store" :size="15" />{{ context.scopeLabel.value
			}}<CommerceIcon name="chevron-down" :size="13" /></button
		><button
			class="cl-icon-button"
			aria-label="刷新当前范围"
			title="刷新数据"
			@click="emit('refresh')"
		>
			<CommerceIcon name="refresh" :size="16" />
		</button>
		<div v-if="open" class="scope-popover cl-card" @keydown.esc="open = false">
			<div class="cl-card-header">
				<h3>选择分析范围</h3>
				<button
					class="cl-icon-button"
					aria-label="关闭范围选择"
					@click="open = false"
				>
					<CommerceIcon name="close" :size="15" />
				</button>
			</div>
			<div class="cl-form-grid">
				<label class="cl-field"
					><span>截止业务日</span
					><input
						v-model="day"
						type="date"
						:min="context.dataset.value?.coverage.start"
						:max="
							context.dataset.value
								? previousDate(context.dataset.value.coverage.endExclusive)
								: undefined
						" /></label
				><label class="cl-field"
					><span>统计区间</span
					><select v-model="period">
						<option value="1">单日 · 对比上周同日</option>
						<option value="7">近 7 日 · 对比前 7 日</option>
					</select></label
				>
			</div>
			<div class="scope-stores">
				<label v-for="store in context.stores.value" :key="store.storeId"
					><input
						v-model="draftStores"
						type="checkbox"
						:value="store.storeId"
					/>{{ store.name }}<small>{{ store.platform }}</small></label
				>
			</div>
			<p v-if="error" class="cl-field-error">{{ error }}</p>
			<button
				class="cl-button primary"
				style="width: 100%; margin-top: 16px"
				@click="apply"
			>
				应用范围
			</button>
		</div>
	</div>
</template>
<style scoped>
.scope-controls {
	display: flex;
	gap: 10px;
	position: relative;
	flex-wrap: wrap;
}
.scope-popover {
	position: absolute;
	right: 0;
	top: 48px;
	z-index: 45;
	width: 410px;
	max-width: calc(100vw - 32px);
	box-shadow: 0 12px 40px #18243d1a;
}
.scope-stores {
	display: flex;
	flex-direction: column;
	gap: 10px;
}
.scope-stores label {
	display: flex;
	align-items: center;
	gap: 10px;
	font-size: 13px;
}
.scope-stores small {
	margin-left: auto;
}
.scope-stores input {
	accent-color: var(--cl-primary);
}
@media (max-width: 639px) {
	.scope-controls {
		gap: 8px;
	}
	.scope-controls .cl-button {
		font-size: 11px;
		padding: 8px 10px;
	}
	.scope-popover {
		left: 0;
		right: auto;
		top: 100%;
	}
}
</style>
