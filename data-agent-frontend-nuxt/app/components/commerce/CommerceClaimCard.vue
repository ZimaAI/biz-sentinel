<script setup lang="ts">
import { computed } from 'vue';
import type { Claim } from '~/types/commerce';
const props = defineProps<{ claim: Claim }>();
defineEmits<{ evidence: [id: string] }>();
const categories: Record<string, { label: string; tone: string }> = {
	OBSERVATION: { label: '已验证事实', tone: 'fact' },
	DECOMPOSITION: { label: '数学分解', tone: 'decomposition' },
	HYPOTHESIS: { label: '相关线索', tone: 'hypothesis' },
	LIMITATION: { label: '分析边界', tone: 'limitation' },
	RECOMMENDATION: { label: '待核实建议', tone: 'recommendation' },
};
const category = computed(
	() => categories[props.claim.type] || { label: '结论', tone: 'fact' },
);
</script>

<template>
	<article class="cl-claim" :class="`cl-claim--${category.tone}`">
		<div class="cl-claim-labels">
			<span
				class="cl-badge"
				:class="{
					success: category.tone === 'decomposition',
					warning: ['hypothesis', 'limitation', 'recommendation'].includes(
						category.tone,
					),
				}"
				>{{ category.label }}</span
			>
			<span v-if="claim.support === 'CONTRADICTED'" class="cl-badge warning"
				>存在反证</span
			>
			<span v-if="claim.support === 'INSUFFICIENT'" class="cl-badge warning"
				>证据不足</span
			>
		</div>
		<p>{{ claim.text }}</p>
		<div
			v-if="claim.evidenceRefs?.length"
			class="cl-claim-refs"
			aria-label="结论引用的证据"
		>
			<button
				v-for="(id, index) in claim.evidenceRefs"
				:key="id"
				type="button"
				:title="id"
				:aria-label="`查看证据 ${id}`"
				@click="$emit('evidence', id)"
			>
				<CommerceIcon name="file" /> 证据
				{{ String(index + 1).padStart(2, '0') }} <span>{{ id.slice(-6) }}</span>
			</button>
		</div>
	</article>
</template>

<style scoped>
.cl-claim {
	padding: 18px 20px;
	border: 1px solid var(--cl-border);
	border-radius: 11px;
	background: var(--cl-surface);
}
.cl-claim--fact {
	border-color: #dce0ff;
	background: #fafaff;
}
.cl-claim--hypothesis,
.cl-claim--recommendation,
.cl-claim--limitation {
	border-color: #f1e3cc;
	background: #fffdf9;
}
.cl-claim-labels {
	display: flex;
	gap: 8px;
	margin-bottom: 11px;
}
.cl-claim p {
	margin: 0;
	color: var(--cl-text);
	line-height: 1.85;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}
.cl-claim-refs {
	display: flex;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 13px;
}
.cl-claim-refs button {
	display: inline-flex;
	align-items: center;
	gap: 5px;
	font-size: 11px;
	line-height: 1.4;
	color: var(--cl-primary);
	background: var(--cl-primary-soft);
	border: 0;
	border-radius: 5px;
	padding: 5px 7px;
	cursor: pointer;
}
.cl-claim-refs button span {
	opacity: 0.6;
	font-size: 10px;
}
.cl-claim-refs :deep(svg) {
	width: 13px;
	height: 13px;
}
</style>
