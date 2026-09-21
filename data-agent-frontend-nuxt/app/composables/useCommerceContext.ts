import { commerceApi } from '~/services/commerceApi';
import type { Me, Dataset, DateRange } from '~/types/commerce';
import { previousDate } from '~/utils/commerceFormat';

export function useCommerceContext() {
	const me = useState<Me | null>('cl.me', () => null),
		dataset = useState<Dataset | null>('cl.dataset', () => null);
	const selectedStoreIds = useState<string[]>('cl.stores', () => []),
		dateRange = useState<DateRange>('cl.dates', () => ({
			start: '',
			endExclusive: '',
		}));
	const comparison = useState<string>(
			'cl.comparison',
			() => 'PREVIOUS_WEEK_SAME_DAYS',
		),
		ready = useState<boolean>('cl.ready', () => false);
	const revision = useState<number>('cl.revision', () => 0);
	const stores = computed(() => me.value?.stores || []);
	const scopeLabel = computed(() =>
		selectedStoreIds.value.length === stores.value.length
			? `全部店铺 · ${stores.value.length} 家`
			: selectedStoreIds.value.length === 1
				? stores.value.find((s) => s.storeId === selectedStoreIds.value[0])
						?.name || '所选店铺'
				: `${selectedStoreIds.value.length} 家店铺`,
	);
	async function refresh() {
		const generation = ++revision.value;
		const identity = await commerceApi.me();
		const list = await commerceApi.listDatasets();
		if (generation !== revision.value) return;
		const changed =
			me.value?.tenantId !== identity.tenantId ||
			me.value?.subjectId !== identity.subjectId ||
			me.value?.authzVersion !== identity.authzVersion;
		me.value = identity;
		dataset.value =
			list.items
				.filter((d) => d.status === 'PUBLISHED')
				.sort(
					(a, b) =>
						b.coverage.endExclusive.localeCompare(a.coverage.endExclusive) ||
						b.quality.publishedAt.localeCompare(a.quality.publishedAt),
				)[0] || null;
		if (changed || !selectedStoreIds.value.length)
			selectedStoreIds.value = identity.stores.map((s) => s.storeId);
		if ((!dateRange.value.start || changed) && dataset.value) {
			const completeDay = dataset.value.coverage.completeBusinessDates
				?.slice()
				.sort()
				.at(-1);
			dateRange.value = {
				start: completeDay || previousDate(dataset.value.coverage.endExclusive),
				endExclusive: completeDay
					? previousDate(completeDay, -1)
					: dataset.value.coverage.endExclusive,
			};
		}
		ready.value = true;
		revision.value++;
	}
	function clear() {
		me.value = null;
		dataset.value = null;
		selectedStoreIds.value = [];
		dateRange.value = { start: '', endExclusive: '' };
		comparison.value = 'PREVIOUS_WEEK_SAME_DAYS';
		ready.value = false;
		revision.value++;
	}
	return {
		me,
		dataset,
		selectedStoreIds,
		dateRange,
		comparison,
		ready,
		revision,
		stores,
		scopeLabel,
		refresh,
		clear,
	};
}
