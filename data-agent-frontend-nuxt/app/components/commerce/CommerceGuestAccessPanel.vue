<script setup lang="ts">
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import type { GuestAccess, Member, Store } from '~/types/commerce';

const props = defineProps<{
	access: GuestAccess | null;
	members: Member[];
	stores: Store[];
}>();
const emit = defineEmits<{ saved: []; conflict: [] }>();
const targetId = ref('');
const storeIds = ref<string[]>([]);
const enabled = ref(false);
const saving = ref(false);
const error = ref('');
const target = computed(() =>
	props.members.find((member) => member.subjectId === targetId.value),
);
const availableStores = computed(() => {
	const allowed = new Set(target.value?.storeIds || []);
	return props.stores.filter((store) => allowed.has(store.storeId));
});
watch(
	() => [props.access, props.members] as const,
	() => {
		if (!props.access) return;
		targetId.value = props.access.subjectId;
		storeIds.value = [...props.access.storeIds];
		enabled.value = props.access.enabled;
		error.value = '';
	},
	{ immediate: true },
);
watch(targetId, () => {
	const allowed = new Set(availableStores.value.map((store) => store.storeId));
	storeIds.value = storeIds.value.filter((storeId) => allowed.has(storeId));
});
async function save() {
	if (!props.access || saving.value || !target.value) return;
	if (enabled.value && !storeIds.value.length) {
		error.value = '至少选择一家演示店铺。';
		return;
	}
	saving.value = true;
	error.value = '';
	try {
		await commerceApi.updateGuestAccess({
			expectedVersion: props.access.version,
			enabled: enabled.value,
			subjectId: targetId.value,
			storeIds: storeIds.value,
		});
		emit('saved');
	} catch (cause) {
		if (cause instanceof CommerceApiError && cause.status === 409) {
			error.value = '游客范围已被更新，请刷新后重试。';
			emit('conflict');
		} else error.value = cause instanceof Error ? cause.message : '保存失败，请重试';
	} finally {
		saving.value = false;
	}
}
</script>

<template>
	<section class="cl-card cl-guest-access">
		<header>
			<div>
				<h2>游客演示范围</h2>
				<p>游客登录后以只读观察者身份查看这里指定账号的经营数据。</p>
			</div>
			<span class="cl-badge" :class="enabled ? 'success' : ''">{{ enabled ? '已开放' : '已关闭' }}</span>
		</header>
		<div class="cl-guest-body">
			<div v-if="error" class="cl-banner danger" role="alert">{{ error }}</div>
			<label class="cl-field">
				<span>演示账号</span>
				<select v-model="targetId" :disabled="saving || !members.length">
					<option v-for="member in members" :key="member.subjectId" :value="member.subjectId">
						{{ member.displayName }}（{{ member.subjectId }}）
					</option>
				</select>
			</label>
			<fieldset>
				<legend>游客可见店铺</legend>
				<label v-for="store in availableStores" :key="store.storeId" class="cl-member-option">
					<input v-model="storeIds" type="checkbox" :value="store.storeId" :disabled="saving" />
					<span>{{ store.name }}</span>
				</label>
				<p v-if="!availableStores.length">该账号没有当前管理员可授予的店铺。</p>
			</fieldset>
			<label class="cl-member-option">
				<input v-model="enabled" type="checkbox" :disabled="saving" />
				<span><strong>开放游客浏览</strong><small>游客不能发起诊断、修改规则、导入数据或改变报告。</small></span>
			</label>
		</div>
		<footer>
			<button class="cl-button primary" :disabled="saving || !target" @click="save">
				{{ saving ? '正在保存…' : '保存游客范围' }}
			</button>
		</footer>
	</section>
</template>

<style scoped>
.cl-guest-access {
	padding: 0;
	overflow: hidden;
}
.cl-guest-access > header,
.cl-guest-access > footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 18px;
	padding: 20px 22px;
}
.cl-guest-access > header {
	border-bottom: 1px solid var(--border);
}
.cl-guest-access h2 {
	margin: 0;
	font-size: 16px;
}
.cl-guest-access header p {
	margin: 7px 0 0;
	font-size: 12px;
	color: var(--text-secondary);
}
.cl-guest-body {
	display: grid;
	gap: 16px;
	padding: 20px 22px 4px;
}
.cl-guest-body fieldset {
	border: 0;
	padding: 0;
	margin: 0;
}
.cl-guest-body legend {
	font-size: 12px;
	font-weight: 700;
	margin-bottom: 8px;
}
.cl-guest-body .cl-member-option {
	margin: 7px 0;
}
.cl-guest-body .cl-member-option small {
	display: block;
	margin-top: 3px;
	color: var(--text-muted);
	font-size: 11px;
}
.cl-guest-access > footer {
	justify-content: flex-end;
	border-top: 1px solid var(--border);
	margin-top: 16px;
}
</style>
