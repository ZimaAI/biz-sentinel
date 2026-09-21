<script setup lang="ts">
import '~/assets/css/commerce.css';
import { commerceApi } from '~/services/commerceApi';
const route = useRouter().currentRoute,
	context = useCommerceContext(),
	mobile = ref(false),
	error = ref('');
const login = computed(() => route.value.path === '/commerce/login');
const pages = [
	{ path: 'overview', name: '经营总览', icon: 'grid', group: '工作空间' },
	{ path: 'analysis', name: '诊断工作台', icon: 'sparkles' },
	{ path: 'anomalies', name: '异常中心', icon: 'alert' },
	{ path: 'metrics', name: '指标字典', icon: 'book', group: '数据与治理' },
	{ path: 'data', name: '数据中心', icon: 'database' },
	{ path: 'reports', name: '报告中心', icon: 'file' },
	{ path: 'runs', name: '运行审计', icon: 'activity' },
];
const title = computed(
	() =>
		pages.find((page) => route.value.path.includes(`/commerce/${page.path}`))
			?.name || '商脉工作台',
);
async function initialize() {
	error.value = '';
	try {
		await context.refresh();
	} catch (e) {
		if ((e as { status?: number }).status === 401)
			await navigateTo('/commerce/login');
		else error.value = e instanceof Error ? e.message : '初始化失败';
	}
}
async function logout() {
	try {
		await commerceApi.logout();
	} finally {
		context.clear();
		await navigateTo('/commerce/login');
	}
}
function expired() {
	context.clear();
	void navigateTo('/commerce/login');
}
onMounted(() => {
	window.addEventListener('commerce:session-expired', expired);
	window.addEventListener('commerce:access-revoked', expired);
	if (!login.value && !context.ready.value) void initialize();
});
onBeforeUnmount(() => {
	window.removeEventListener('commerce:session-expired', expired);
	window.removeEventListener('commerce:access-revoked', expired);
});
watch(
	() => route.value.path,
	() => {
		mobile.value = false;
	},
);
useHead({
	title: computed(() => `${title.value} · 商脉 CommerceLens`),
	htmlAttrs: { lang: 'zh-CN' },
});
</script>
<template>
	<div class="cl-app">
		<slot v-if="login" />
		<template v-else>
			<div v-if="mobile" class="cl-mobile-backdrop" @click="mobile = false" />
			<aside class="cl-sidebar" :class="{ open: mobile }" aria-label="主导航">
				<NuxtLink to="/commerce/overview" class="cl-brand"
					><span class="cl-logo"
						><svg
							width="23"
							height="24"
							viewBox="0 0 23 24"
							fill="none"
							aria-hidden="true"
						>
							<path
								d="M5 10v9M11.5 5v14M18 8v11"
								stroke="white"
								stroke-width="3.5"
								stroke-linecap="round"
							/></svg></span
					><span
						><strong>商脉</strong><small>COMMERCE LENS</small></span
					></NuxtLink
				>
				<nav class="cl-nav">
					<template v-for="page in pages" :key="page.path"
						><div v-if="page.group" class="cl-nav-label">{{ page.group }}</div>
						<NuxtLink
							:to="`/commerce/${page.path}`"
							:class="{ active: route.path.includes(`/commerce/${page.path}`) }"
							:aria-current="
								route.path.includes(`/commerce/${page.path}`)
									? 'page'
									: undefined
							"
							><CommerceIcon :name="page.icon" />{{ page.name
							}}<small v-if="page.path === 'analysis'">Agent</small></NuxtLink
						></template
					>
				</nav>
				<div class="cl-sidebar-bottom">
					<div class="cl-trust">
						<strong
							><CommerceIcon name="shield" :size="16" />可信分析 ·
							证据可追溯</strong
						>
						<p>数字有口径，结论有证据。<br />以已发布快照为分析依据。</p>
						<span
							v-if="context.dataset.value?.synthetic"
							class="cl-badge"
							style="margin-top: 12px"
							>合成演示数据</span
						>
					</div>
					<div class="cl-account">
						<span class="cl-avatar">{{
							context.me.value?.displayName?.slice(0, 1) || '商'
						}}</span>
						<div>
							<strong>{{ context.me.value?.displayName || '工作空间' }}</strong
							><small>{{ context.stores.value.length }} 家授权店铺</small>
						</div>
						<button
							class="cl-icon-button"
							aria-label="退出登录"
							title="退出登录"
							@click="logout"
						>
							<CommerceIcon name="logout" :size="15" />
						</button>
					</div>
				</div>
			</aside>
			<div class="cl-workspace">
				<header class="cl-topbar">
					<div class="cl-breadcrumb">
						<button
							class="cl-icon-button cl-mobile-toggle"
							aria-label="打开导航"
							@click="mobile = !mobile"
						>
							<CommerceIcon name="menu" /></button
						><span>工作空间</span><span>/</span><span>{{ title }}</span>
					</div>
					<div class="cl-topbar-right">
						<span>Asia/Shanghai</span
						><span v-if="context.dataset.value?.synthetic" class="cl-badge"
							>● 合成数据</span
						><NuxtLink
							to="/commerce/anomalies"
							class="cl-icon-button"
							aria-label="查看异常中心"
							><CommerceIcon name="bell" /></NuxtLink
						><span
							class="cl-avatar"
							style="background: #edf5f1; color: #528c7c"
							>{{ context.me.value?.displayName?.slice(0, 1) || '商' }}</span
						>
					</div>
				</header>
				<main class="cl-content">
					<div v-if="error" class="cl-card cl-empty" role="alert">
						<CommerceIcon name="alert" :size="30" />
						<h2>工作空间暂时无法加载</h2>
						<p>{{ error }}</p>
						<button class="cl-button primary" @click="initialize">
							重新连接
						</button>
					</div>
					<template v-else-if="context.ready.value"><slot /></template>
					<div v-else class="cl-stack" aria-label="正在加载工作空间">
						<div class="cl-skeleton" style="height: 48px; width: 40%" />
						<div class="cl-grid four">
							<div
								v-for="i in 4"
								:key="i"
								class="cl-skeleton cl-skeleton-card"
							/>
						</div>
						<div class="cl-skeleton" style="height: 360px" />
					</div>
				</main>
				<footer v-if="context.ready.value" class="cl-footer">
					CommerceLens · 基于 Spring AI Alibaba DataAgent 的经营分析工作台<span
						v-if="context.dataset.value?.synthetic"
						>　/　当前为合成演示数据</span
					>
				</footer>
			</div>
		</template>
	</div>
</template>
