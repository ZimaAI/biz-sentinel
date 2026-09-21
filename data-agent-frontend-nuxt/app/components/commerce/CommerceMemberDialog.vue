<script setup lang="ts">
import { commerceApi, CommerceApiError } from '~/services/commerceApi';
import type { Member } from '~/types/commerce';
const props = defineProps<{ open: boolean; member: Member | null }>();
const emit = defineEmits<{ close: []; saved: []; conflict: [] }>();
const { me, stores } = useCommerceContext();
const dialog = ref<HTMLDialogElement | null>(null),
	saving = ref(false),
	error = ref(''),
	conflict = ref(false);
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
	username: '',
	password: '',
	displayName: '',
	storeIds: [] as string[],
	roles: ['STORE_OPERATOR'] as string[],
	enabled: true,
});
const roles = [
	{
		id: 'TENANT_ADMIN',
		name: '租户管理员',
		help: '管理成员、授权、导入与规则',
	},
	{ id: 'OPS_MANAGER', name: '运营经理', help: '分析授权范围内的多个店铺' },
	{ id: 'STORE_OPERATOR', name: '店铺运营', help: '查询、诊断与证据复核' },
	{ id: 'VIEWER', name: '只读观察者', help: '查看基础总览与可见报告' },
];
watch(
	() => props.open,
	async (open) => {
		const activeGeneration = ++generation;
		saving.value = false;
		error.value = '';
		conflict.value = false;
		form.password = '';
		if (open) {
			Object.assign(form, {
				username: props.member?.subjectId || '',
				displayName: props.member?.displayName || '',
				storeIds: [...(props.member?.storeIds || [])],
				roles: [...(props.member?.roles || ['STORE_OPERATOR'])],
				enabled: props.member?.enabled ?? true,
			});
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
async function save() {
	if (
		saving.value ||
		conflict.value ||
		!me.value?.roles.includes('TENANT_ADMIN')
	)
		return;
	error.value = '';
	if (
		!form.roles.length ||
		(!props.member &&
			(!/^[A-Za-z0-9._-]{3,64}$/.test(form.username) ||
				form.password.length < 12 ||
				form.password.length > 64))
	) {
		error.value =
			'请选择至少一个角色。新账号使用 3–64 位字母、数字或 ._-，密码为 12–64 位。';
		return;
	}
	saving.value = true;
	const activeGeneration = generation,
		activeIdentity = identity.value;
	const current = () =>
		!disposed &&
		activeGeneration === generation &&
		activeIdentity === identity.value;
	try {
		if (props.member)
			await commerceApi.updateMember(props.member.subjectId, {
				expectedVersion: props.member.authzVersion,
				storeIds: form.storeIds,
				roles: form.roles,
				enabled: form.enabled,
			});
		else
			await commerceApi.createMember({
				username: form.username,
				password: form.password,
				displayName: form.displayName || form.username,
				storeIds: form.storeIds,
				roles: form.roles,
			});
		if (disposed || activeIdentity !== identity.value) return;
		emit('saved');
		if (current()) {
			form.password = '';
			emit('close');
		}
	} catch (cause) {
		if (!current()) return;
		if (cause instanceof CommerceApiError && cause.status === 409) {
			conflict.value = true;
			error.value = '账号或授权版本已发生变化，请关闭后刷新成员列表。';
			emit('conflict');
		} else
			error.value = cause instanceof Error ? cause.message : '保存失败，请重试';
	} finally {
		if (current()) saving.value = false;
	}
}
onBeforeUnmount(() => {
	disposed = true;
	++generation;
	form.password = '';
	dialog.value?.close();
});
</script>

<template>
	<dialog
		ref="dialog"
		class="cl-member-dialog"
		aria-labelledby="cl-member-title"
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
					<h2 id="cl-member-title">
						{{ member ? '编辑成员授权' : '添加成员' }}
					</h2>
					<p>
						{{
							member
								? `${member.displayName} · 授权版本 v${member.authzVersion}`
								: '授权范围始终限制在你可管理的店铺内'
						}}
					</p>
				</div>
				<button
					type="button"
					class="cl-button"
					aria-label="关闭成员弹窗"
					@click="emit('close')"
				>
					<CommerceIcon name="close" />
				</button>
			</header>
			<div class="cl-member-body">
				<div v-if="error" class="cl-banner danger" role="alert">
					{{ error }}
				</div>
				<template v-if="!member"
					><label class="cl-field"
						>登录账号<input
							v-model="form.username"
							autocomplete="off"
							maxlength="64"
							required /></label
					><label class="cl-field"
						>成员姓名<input
							v-model="form.displayName"
							maxlength="100"
							placeholder="用于工作台展示" /></label
					><label class="cl-field"
						>初始密码<input
							v-model="form.password"
							type="password"
							autocomplete="new-password"
							minlength="12"
							maxlength="64"
							required
						/><small>12–64 位，保存后不再展示。</small></label
					></template
				>
				<fieldset>
					<legend>角色权限</legend>
					<label v-for="role in roles" :key="role.id" class="cl-member-option"
						><input
							v-model="form.roles"
							type="checkbox"
							:value="role.id"
						/><span
							><strong>{{ role.name }}</strong
							><small>{{ role.help }}</small></span
						></label
					>
				</fieldset>
				<fieldset>
					<legend>店铺范围</legend>
					<label
						v-for="store in stores"
						:key="store.storeId"
						class="cl-member-option"
						><input
							v-model="form.storeIds"
							type="checkbox"
							:value="store.storeId"
						/><span>{{ store.name }}</span></label
					>
					<p v-if="!stores.length">当前没有可授予的店铺。</p>
				</fieldset>
				<label v-if="member" class="cl-member-option"
					><input v-model="form.enabled" type="checkbox" />启用此成员</label
				>
				<div v-if="member" class="cl-banner warning">
					授权修改立即生效。已不再覆盖完整店铺范围的运行、报告与证据将无法继续访问。<template
						v-if="member.subjectId === me?.subjectId"
						>你正在修改自己的账号，变更后可能需要重新登录。</template
					>
				</div>
			</div>
			<footer>
				<button type="button" class="cl-button" @click="emit('close')">
					取消</button
				><button
					type="submit"
					class="cl-button primary"
					:disabled="saving || conflict || !me?.roles.includes('TENANT_ADMIN')"
				>
					{{ saving ? '正在保存…' : '保存成员' }}
				</button>
			</footer>
		</form>
	</dialog>
</template>

<style scoped>
.cl-member-dialog {
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
.cl-member-dialog::backdrop {
	background: rgba(19, 30, 54, 0.25);
}
.cl-member-dialog form {
	display: flex;
	flex-direction: column;
	max-height: 90vh;
}
.cl-member-dialog header {
	display: flex;
	justify-content: space-between;
	align-items: flex-start;
	gap: 12px;
	padding: 22px 24px 18px;
	border-bottom: 1px solid var(--border);
}
.cl-member-dialog h2 {
	margin: 0;
	font-size: 18px;
}
.cl-member-dialog header p {
	font-size: 12px;
	margin: 6px 0 0;
	color: var(--text-muted);
}
.cl-member-dialog header button {
	padding: 8px;
}
.cl-member-body {
	display: grid;
	gap: 18px;
	padding: 22px 24px;
	overflow: auto;
}
.cl-member-dialog fieldset {
	border: 1px solid var(--border);
	border-radius: 10px;
	padding: 10px 14px;
}
.cl-member-dialog legend {
	font-size: 12px;
	padding: 0 6px;
}
.cl-member-option {
	display: flex;
	align-items: flex-start;
	gap: 10px;
	padding: 7px 0;
	font-size: 13px;
}
.cl-member-option input {
	width: 15px;
	height: 15px;
	accent-color: var(--primary);
	margin-top: 3px;
	flex-shrink: 0;
}
.cl-member-option strong {
	font-size: 13px;
	font-weight: 500;
}
.cl-member-option small {
	display: block;
	color: var(--text-muted);
	font-size: 11px;
	line-height: 1.8;
	margin-top: 3px;
}
.cl-member-dialog small {
	color: var(--text-muted);
	font-size: 12px;
}
.cl-member-dialog footer {
	display: flex;
	justify-content: flex-end;
	gap: 10px;
	padding: 16px 24px;
	border-top: 1px solid var(--border);
}
</style>
