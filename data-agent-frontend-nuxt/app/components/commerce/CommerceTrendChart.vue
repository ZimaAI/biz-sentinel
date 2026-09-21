<script setup lang="ts">
import { use, init, type EChartsType } from 'echarts/core';
import { LineChart } from 'echarts/charts';
import {
	GridComponent,
	TooltipComponent,
	AriaComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { ResultRow } from '~/types/commerce';
import { chartValue, getCell, formatCell } from '~/utils/commerceFormat';
use([
	LineChart,
	GridComponent,
	TooltipComponent,
	AriaComponent,
	CanvasRenderer,
]);
const props = defineProps<{
	rows: ResultRow[];
	metricId: string;
	label: string;
}>();
const target = ref<HTMLElement | null>(null),
	table = ref(false);
let chart: EChartsType | undefined, observer: ResizeObserver | undefined;
function draw() {
	if (!target.value) return;
	chart ??= init(target.value);
	const unit = getCell(props.rows[0], props.metricId)?.unit;
	chart.setOption(
		{
			animation: false,
			aria: {
				enabled: true,
				description: `${props.label}的当前期与对比期趋势，可切换下方数据表查阅精确值。`,
			},
			grid: { top: 35, left: 60, right: 18, bottom: 32 },
			tooltip: {
				trigger: 'axis',
				renderMode: 'richText',
				backgroundColor: '#fff',
				borderColor: '#e8ecf3',
				textStyle: { color: '#18243d', fontSize: 12 },
			},
			xAxis: {
				type: 'category',
				data: props.rows.map((row) => row.dimensions.day?.slice(5)),
				boundaryGap: false,
				axisTick: { show: false },
				axisLine: { lineStyle: { color: '#edf0f6' } },
				axisLabel: { color: '#8d97a9', fontSize: 10 },
			},
			yAxis: {
				type: 'value',
				name:
					unit === 'CNY_CENT'
						? '金额（元）'
						: unit === 'RATIO'
							? '百分比（%）'
							: '数量',
				nameTextStyle: { color: '#8d97a9', fontSize: 10 },
				axisLabel: {
					color: '#8d97a9',
					fontSize: 10,
					formatter: (value: number) =>
						Math.abs(value) >= 10000 ? `${value / 10000}万` : String(value),
				},
				splitLine: { lineStyle: { color: '#edf0f6', type: 'dashed' } },
			},
			series: [
				{
					name: '当前期',
					type: 'line',
					data: props.rows.map((row) =>
						chartValue(getCell(row, props.metricId)),
					),
					symbol: 'circle',
					symbolSize: 6,
					connectNulls: false,
					smooth: false,
					itemStyle: { color: '#5965e9' },
					lineStyle: { width: 2.5 },
					areaStyle: {
						color: {
							type: 'linear',
							x: 0,
							y: 0,
							x2: 0,
							y2: 1,
							colorStops: [
								{ offset: 0, color: 'rgba(89,101,233,.13)' },
								{ offset: 1, color: 'rgba(89,101,233,0)' },
							],
						},
					},
				},
				{
					name: '对比期',
					type: 'line',
					data: props.rows.map((row) =>
						chartValue(getCell(row, `${props.metricId}_baseline`)),
					),
					symbol: 'none',
					connectNulls: false,
					lineStyle: { color: '#c5cbdc', type: 'dashed', width: 2 },
					itemStyle: { color: '#c5cbdc' },
				},
			],
		},
		true,
	);
}
watch(
	() => [props.rows, props.metricId],
	() => nextTick(draw),
);
watch(table, () => nextTick(() => chart?.resize()));
onMounted(() => {
	draw();
	observer = new ResizeObserver(() => chart?.resize());
	if (target.value) observer.observe(target.value);
});
onBeforeUnmount(() => {
	observer?.disconnect();
	chart?.dispose();
});
</script>
<template>
	<div class="trend-chart">
		<div class="trend-legend">
			<span><i />当前期</span><span><i class="baseline" />对比期</span
			><button class="cl-text-button" @click="table = !table">
				{{ table ? '显示趋势图' : '查看数据表' }}
			</button>
		</div>
		<div
			v-show="!table"
			ref="target"
			class="trend-canvas"
			role="img"
			:aria-label="`${label}七日趋势，精确金额可切换数据表查看`"
		/>
		<div v-if="table" class="cl-table-wrap trend-data">
			<table class="cl-table">
				<thead>
					<tr>
						<th>业务日期</th>
						<th class="numeric">当前期</th>
						<th class="numeric">对比期</th>
					</tr>
				</thead>
				<tbody>
					<tr v-for="row in rows" :key="row.rowKey">
						<td>{{ row.dimensions.day }}</td>
						<td class="numeric">{{ formatCell(getCell(row, metricId)) }}</td>
						<td class="numeric">
							{{ formatCell(getCell(row, `${metricId}_baseline`)) }}
						</td>
					</tr>
				</tbody>
			</table>
		</div>
	</div>
</template>
<style scoped>
.trend-legend {
	display: flex;
	gap: 18px;
	align-items: center;
	color: var(--cl-secondary);
	font-size: 10px;
	margin-top: 4px;
}
.trend-legend span {
	display: flex;
	gap: 7px;
	align-items: center;
}
.trend-legend i {
	display: block;
	width: 16px;
	height: 3px;
	background: var(--cl-primary);
	border-radius: 2px;
}
.trend-legend i.baseline {
	background: #c5cbdc;
}
.trend-legend button {
	margin-left: auto;
	font-size: 10px;
}
.trend-canvas {
	height: 248px;
	width: 100%;
}
.trend-data {
	height: 248px;
	margin-top: 12px;
}
.trend-data td {
	height: 32px;
	padding: 6px 14px;
}
</style>
